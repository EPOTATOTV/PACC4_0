package com.potatotv.paccclient.plugin;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.spi.DetectionPlugin;
import com.potatotv.paccclient.spi.Detector;
import com.potatotv.paccclient.spi.FeatureProvider;
import com.potatotv.paccclient.spi.PluginMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 插件加载器（文档 §2.4 加载流程）。
 *
 * <p>流程：扫描 {@code <pluginsDir>/*.jar} → 验签 → 读 {@code META-INF/pacc-plugin.json} →
 * 版本兼容判断 → 独立 {@link PluginClassLoader} → 实例化入口类 → {@code onLoad}。
 * 单插件任何一步失败都只跳过它自己，绝不中断整体启动（一个坏插件不能让所有插件都加载不了）。</p>
 *
 * <p>注册结果通过 {@link #detectors()} / {@link #featureProviders()} 暴露，由宿主接到调度器与
 * 特征采集链；规则直接经 {@link RuleRegistrar} 落进 PRL 引擎。</p>
 */
public final class PluginManager implements AutoCloseable {

    /** 插件元信息条目名。 */
    private static final String METADATA_ENTRY = "META-INF/pacc-plugin.json";
    /** 信任锚文件名（放在插件目录下）。 */
    private static final String TRUST_FILE = "trusted-fingerprints.txt";

    private final Path pluginsDir;
    private final SystemProbe systemProbe;
    private final EventBus eventBus;
    private final RuleRegistrar ruleRegistrar;
    private final PluginLoadOptions options;
    private final PluginSandbox sandbox;
    private final Set<String> trustedFingerprints;

    private final List<Detector> detectors = new ArrayList<>();
    private final List<FeatureProvider> featureProviders = new ArrayList<>();
    private final Set<String> detectorIds = new HashSet<>();
    private final Set<String> pluginIds = new HashSet<>();
    private final List<LoadedPlugin> loaded = new ArrayList<>();

    public PluginManager(Path pluginsDir, SystemProbe systemProbe, RuleRegistrar ruleRegistrar) {
        this(pluginsDir, systemProbe, ruleRegistrar, new EventBus(), PluginLoadOptions.defaults());
    }

    public PluginManager(Path pluginsDir,
                         SystemProbe systemProbe,
                         RuleRegistrar ruleRegistrar,
                         EventBus eventBus,
                         PluginLoadOptions options) {
        this.pluginsDir = pluginsDir;
        this.systemProbe = systemProbe;
        this.eventBus = eventBus == null ? new EventBus() : eventBus;
        this.ruleRegistrar = ruleRegistrar == null ? (type, script) -> { } : ruleRegistrar;
        this.options = options == null ? PluginLoadOptions.defaults() : options;
        this.sandbox = new PluginSandbox();
        this.trustedFingerprints = mergeTrust(this.options.trustedFingerprints(),
                PluginSignatureVerifier.loadTrustedFingerprints(
                        pluginsDir == null ? null : pluginsDir.resolve(TRUST_FILE)));
    }

    /** 事件总线（供宿主接监听或复用）。 */
    public EventBus eventBus() {
        return eventBus;
    }

    /** 已加载插件注册的检测器（已套沙箱）。 */
    public List<Detector> detectors() {
        return List.copyOf(detectors);
    }

    /** 已加载插件注册的特征提供者。 */
    public List<FeatureProvider> featureProviders() {
        return List.copyOf(featureProviders);
    }

    /** 成功加载的插件数。 */
    public int loadedCount() {
        return loaded.size();
    }

    /** 成功加载的插件元信息（供诊断 / 上报）。 */
    public List<PluginMetadata> loadedPlugins() {
        return loaded.stream().map(p -> p.metadata()).toList();
    }

    /**
     * 扫描并加载插件目录下全部 JAR。
     *
     * @return 成功加载的插件数
     */
    public int loadAll() {
        if (pluginsDir == null || !Files.isDirectory(pluginsDir)) {
            return 0;
        }
        List<Path> jars = new ArrayList<>();
        try (Stream<Path> files = Files.list(pluginsDir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted()
                    .forEach(jars::add);
        } catch (IOException e) {
            System.err.println("[PTV-Plugin] 扫描插件目录失败: " + e.getMessage());
            return 0;
        }
        for (Path jar : jars) {
            try {
                loadOne(jar);
            } catch (RuntimeException e) {
                System.err.println("[PTV-Plugin] 加载失败 " + jar.getFileName() + ": " + e);
            }
        }
        return loaded.size();
    }

    private void loadOne(Path jar) {
        PluginDescriptor descriptor = readDescriptor(jar);
        if (descriptor == null) {
            System.err.println("[PTV-Plugin] 跳过（缺少或非法 " + METADATA_ENTRY + "）: " + jar.getFileName());
            return;
        }
        if (!signatureAcceptable(jar)) {
            return;
        }
        if (!pluginIds.add(descriptor.id())) {
            System.err.println("[PTV-Plugin] 跳过重复插件 id=" + descriptor.id());
            return;
        }

        PluginMetadata metadata = new PluginMetadata(descriptor.id(), descriptor.name(),
                descriptor.version(), descriptor.author(), descriptor.description(),
                descriptor.apiVersion(), descriptor.minClientBuild());
        if (!metadata.compatibleWith(options.hostApiVersion(), options.hostBuild())) {
            System.err.println("[PTV-Plugin] 跳过不兼容插件 id=" + descriptor.id()
                    + " apiVersion=" + descriptor.apiVersion()
                    + " minClientBuild=" + descriptor.minClientBuild());
            return;
        }

        PluginClassLoader loader = null;
        try {
            loader = new PluginClassLoader(jar, PluginManager.class.getClassLoader());
            DetectionPlugin plugin = instantiate(descriptor, loader);
            PluginContextImpl context = newContext(descriptor.id());
            plugin.onLoad(context);
            loaded.add(new LoadedPlugin(metadata, plugin, loader));
            System.out.println("[PTV-Plugin] 已加载 " + descriptor.id() + " v" + descriptor.version());
        } catch (ReflectiveOperationException | IOException e) {
            closeQuietly(loader);
            System.err.println("[PTV-Plugin] 实例化失败 " + descriptor.id() + ": " + e);
        } catch (RuntimeException e) {
            closeQuietly(loader);
            System.err.println("[PTV-Plugin] onLoad 失败 " + descriptor.id() + ": " + e);
        }
    }

    private PluginContextImpl newContext(String pluginId) {
        Path configFile = pluginsDir.resolve(pluginId).resolve("config.properties");
        return new PluginContextImpl(
                pluginId,
                systemProbe,
                new FilePluginConfig(configFile),
                new PrefixedPluginLogger(pluginId),
                eventBus,
                sandbox,
                detector -> {
                    if (detectorIds.add(detector.id())) {
                        detectors.add(detector);
                    } else {
                        System.err.println("[PTV-Plugin] 检测器 id 冲突，已忽略: " + detector.id());
                    }
                },
                featureProviders::add,
                ruleRegistrar);
    }

    private static DetectionPlugin instantiate(PluginDescriptor descriptor, PluginClassLoader loader)
            throws ReflectiveOperationException {
        Class<?> type = Class.forName(descriptor.entryClass(), true, loader);
        if (!DetectionPlugin.class.isAssignableFrom(type)) {
            throw new IllegalStateException("入口类未实现 DetectionPlugin: " + descriptor.entryClass());
        }
        return (DetectionPlugin) type.getDeclaredConstructor().newInstance();
    }

    private boolean signatureAcceptable(Path jar) {
        PluginSignatureVerifier.Result result =
                PluginSignatureVerifier.verify(jar, trustedFingerprints);
        String name = jar.getFileName().toString();
        return switch (result.status()) {
            case TRUSTED -> true;
            case INVALID -> {
                System.err.println("[PTV-Plugin] 拒绝（签名损坏或被篡改）: " + name);
                yield false;
            }
            case UNSIGNED, UNTRUSTED -> {
                if (options.devMode()) {
                    System.out.println("[PTV-Plugin] 开发者模式放行未受信任插件: " + name);
                    yield true;
                }
                System.err.println("[PTV-Plugin] 拒绝（"
                        + (result.status() == PluginSignatureVerifier.Status.UNSIGNED
                        ? "未签名" : "签名者不受信任") + "）: " + name);
                yield false;
            }
        };
    }

    private static PluginDescriptor readDescriptor(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(METADATA_ENTRY);
            if (entry == null) {
                return null;
            }
            try (InputStream in = file.getInputStream(entry)) {
                Map<String, Object> json =
                        Json.decodeObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                return PluginDescriptor.fromJson(json);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Set<String> mergeTrust(Set<String> fromOptions, Set<String> fromFile) {
        Set<String> out = new HashSet<>(fromOptions);
        out.addAll(fromFile);
        return out;
    }

    private static void closeQuietly(PluginClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (IOException ignored) {
            // 关闭失败不影响加载流程
        }
    }

    /** 卸载全部插件：先 {@code onUnload}，再关类加载器与沙箱。 */
    @Override
    public void close() {
        for (LoadedPlugin plugin : loaded) {
            try {
                plugin.plugin().onUnload();
            } catch (RuntimeException e) {
                System.err.println("[PTV-Plugin] onUnload 异常 " + plugin.metadata().id() + ": " + e);
            }
            closeQuietly(plugin.loader());
        }
        loaded.clear();
        detectors.clear();
        featureProviders.clear();
        detectorIds.clear();
        pluginIds.clear();
        eventBus.clear();
        sandbox.close();
    }

    /** 已加载插件句柄。 */
    private record LoadedPlugin(PluginMetadata metadata, DetectionPlugin plugin, PluginClassLoader loader) {
    }
}