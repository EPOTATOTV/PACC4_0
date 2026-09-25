package com.potatotv.pacc.service.plugin;

import com.potatotv.pacc.domain.Plugin;
import com.potatotv.pacc.domain.plugin.DetectionContext;
import com.potatotv.pacc.domain.plugin.DetectionPlugin;
import com.potatotv.pacc.domain.plugin.DetectionResult;
import com.potatotv.pacc.domain.plugin.PluginMetadata;
import com.potatotv.pacc.domain.plugin.PluginRuntime;
import com.potatotv.pacc.df.DfProperties;
import com.potatotv.pacc.repository.PluginRepository;
import com.potatotv.pacc.repository.PluginRuntimeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * §4.2.2 插件运行时管理器：热加载 / 卸载第三方检测插件，并在沙箱内执行。
 *
 * <p>加载使用子优先 {@link ChildFirstClassLoader}，卸载时调用 {@link DetectionPlugin#destroy()}
 * 并 {@link ChildFirstClassLoader#close()} 释放 jar 句柄，实现真正的热替换；
 * 运行时状态（LOADED / UNLOADED / ERROR / CPU / 错误数）持久化到 {@code t_plugin_runtime}，
 * 与插件市场（{@code t_plugin}）条目通过 {@code pluginId} 关联——市场审核通过的插件可由管理端一键加载。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null") // 反射/类加载路径存在 Eclipse JDT null 误报
public class PluginManager {

    private final PluginRepository pluginRepository;
    private final PluginRuntimeRepository runtimeRepository;
    private final PluginSandbox sandbox;
    private final DfProperties props;

    /** 已加载插件的内存登记：pluginId → 实例/类加载器/运行时状态。 */
    private final Map<String, Loaded> loaded = new ConcurrentHashMap<>();

    private record Loaded(DetectionPlugin plugin, ChildFirstClassLoader loader, PluginRuntime runtime) { }

    /**
     * 按相对路径热加载插件；插件标识取类名。
     *
     * @param path 相对 {@code pacc.df.plugin.dir} 的 jar 文件或 classes 目录路径
     * @return 运行时登记
     */
    public PluginRuntime loadPlugin(String path) {
        return loadPlugin(null, path);
    }

    /**
     * 热加载插件。
     *
     * @param pluginId 插件标识；为空时取插件元数据/类名。若命中插件市场条目则复用其名称与版本
     * @param path     相对 {@code pacc.df.plugin.dir} 的 jar 文件或 classes 目录路径；
     *                 绝对路径、越界路径与摘要不符的包一律拒绝（见 {@link #resolvePluginArtifact}）
     * @return 运行时登记
     */
    public PluginRuntime loadPlugin(String pluginId, String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("插件路径不能为空");
        }
        Optional<Plugin> market = pluginId == null ? Optional.empty() : pluginRepository.findById(pluginId);
        // 沙箱从加载那一刻就开始生效：initialize/getMetadata 同样吃超时与 CPU 预算，
        // 因此先用一个只用于记账的临时登记承接计数，登记正式落库后再并入。
        PluginRuntime pending = PluginRuntime.builder().pluginId(pluginId == null ? "pending" : pluginId).build();
        ChildFirstClassLoader loader = null;
        try {
            File target = resolvePluginArtifact(path, market);
            URL[] urls = { target.toURI().toURL() };
            loader = new ChildFirstClassLoader(urls, getClass().getClassLoader());
            Class<? extends DetectionPlugin> type = resolvePluginClass(loader, target);
            DetectionPlugin instance = type.getDeclaredConstructor().newInstance();
            sandbox.initialize(instance, pending);
            PluginMetadata meta = sandbox.metadata(instance, pending);
            String id = pluginId != null && !pluginId.isBlank() ? pluginId
                    : (meta.pluginId() != null && !meta.pluginId().isBlank()
                            ? meta.pluginId() : type.getSimpleName());
            PluginRuntime runtime = runtimeRepository.findById(id).orElseGet(() -> PluginRuntime.builder().pluginId(id).build());
            // 半途失败过（例如上一次 initialize 超时）也要把计入的 CPU 与错误数留下，便于管理端看到「这个插件有问题」
            runtime.setCpuMs(runtime.getCpuMs() + pending.getCpuMs());
            runtime.setErrorCount(runtime.getErrorCount() + pending.getErrorCount());
            String marketName = market.map(Plugin::getName).orElse(null);
            runtime.setName(marketName != null ? marketName : (meta.name() != null ? meta.name() : id));
            String marketVersion = market.map(Plugin::getPluginVersion).orElse(null);
            runtime.setVersion(marketVersion != null ? marketVersion
                    : (meta.version() != null ? meta.version() : "1.0.0"));
            runtime.setClassPath(path.length() > 512 ? path.substring(0, 512) : path);
            runtime.setState(PluginRuntime.ST_LOADED);
            runtime.setLoadedAt(Instant.now());
            runtime.setUpdatedAt(Instant.now());
            runtime.setDeclaredApis(String.join(",", meta.declaredApis()));
            Loaded prior = loaded.get(id);
            if (prior != null) {
                closeQuietly(prior);
            }
            loaded.put(id, new Loaded(instance, loader, runtime));
            log.info("插件已加载 id={} version={} path={}", id, runtime.getVersion(), path);
            return runtimeRepository.save(runtime);
        } catch (Exception e) {
            // 加载失败时类加载器不能留着：它持有 jar 文件句柄，反复失败会积压
            if (loader != null) {
                try {
                    loader.close();
                } catch (IOException closeFailed) {
                    log.warn("加载失败的插件类加载器关闭异常 err={}", closeFailed.getMessage());
                }
            }
            PluginRuntime failed = pluginId == null ? null : runtimeRepository.findById(pluginId).orElse(null);
            if (failed != null) {
                failed.setState(PluginRuntime.ST_ERROR);
                failed.setUpdatedAt(Instant.now());
                runtimeRepository.save(failed);
            }
            throw new IllegalArgumentException("插件加载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 把请求里的 path 解析成「插件目录内」的真实文件，并比对市场登记摘要。
     *
     * <p>这是插件加载唯一的安全边界。path 由管理端请求体提供，若直接 {@code new File(path)}
     * 交给类加载器，等于把「加载任意 jar 并执行其初始化代码」的能力交给任何持有
     * {@code system:update} 的账号——包括加载系统自带 jar 去改写进程行为。
     * 因此只接受相对路径，且解析后的真实位置必须仍在 {@code pacc.df.plugin.dir} 之内。</p>
     *
     * @param path   请求体给出的相对路径
     * @param market 插件市场条目（若存在，其 signatureSha256 不为空时必须匹配）
     * @return 插件目录内的真实文件或目录
     */
    private File resolvePluginArtifact(String path, Optional<Plugin> market) throws IOException {
        Path root = Path.of(props.getPlugin().getPluginDir()).toAbsolutePath().normalize();
        Path requested;
        try {
            requested = Path.of(path);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("插件路径非法: " + path);
        }
        if (requested.isAbsolute()) {
            throw new IllegalArgumentException("插件路径必须是插件目录内的相对路径");
        }
        Path candidate = root.resolve(requested).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("插件路径越界：不允许指向插件目录之外");
        }
        // normalize 只处理字面量，符号链接仍可能指到目录外，所以再用 realPath 复核一次。
        Path real = candidate.toRealPath();
        if (!real.startsWith(root.toRealPath())) {
            throw new IllegalArgumentException("插件路径越界：符号链接指向插件目录之外");
        }
        if (!Files.isRegularFile(real) && !Files.isDirectory(real)) {
            throw new IllegalArgumentException("插件路径不可读: " + path);
        }
        String expected = market.map(Plugin::getSignatureSha256).orElse(null);
        if (expected != null && !expected.isBlank() && Files.isRegularFile(real)) {
            String actual = sha256(real.toFile());
            if (!actual.equalsIgnoreCase(expected.trim())) {
                throw new IllegalArgumentException("插件包摘要与市场登记不一致，拒绝加载");
            }
        }
        return real.toFile();
    }

    /** 文件 SHA-256（小写十六进制）。 */
    private static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("运行环境缺少 SHA-256", e);
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 卸载插件：destroy + 关闭类加载器，运行时不复存在。 */
    public PluginRuntime unloadPlugin(String pluginId) {
        Loaded l = loaded.remove(pluginId);
        if (l != null) {
            closeQuietly(l);
        }
        PluginRuntime runtime = runtimeRepository.findById(pluginId)
                .orElseThrow(() -> new IllegalArgumentException("运行时插件不存在: " + pluginId));
        runtime.setState(PluginRuntime.ST_UNLOADED);
        runtime.setUpdatedAt(Instant.now());
        return runtimeRepository.save(runtime);
    }

    /** 运行时插件列表（含未加载的历史登记）。 */
    public List<PluginRuntime> listPlugins() {
        return runtimeRepository.findAllByOrderByUpdatedAtDesc();
    }

    /** 在沙箱内执行指定插件。 */
    public DetectionResult sandboxExecute(DetectionPlugin plugin, DetectionContext context) {
        PluginRuntime runtime = loaded.values().stream()
                .filter(x -> x.plugin() == plugin)
                .map(Loaded::runtime)
                .findFirst()
                .orElseGet(() -> PluginRuntime.builder().pluginId("ad-hoc").build());
        DetectionResult result = sandbox.execute(plugin, context, runtime);
        persistIfRegistered(runtime);
        return result;
    }

    /** 对全部已加载插件执行一次检测（单插件异常不影响其余）。 */
    public Map<String, DetectionResult> detectAll(DetectionContext context) {
        Map<String, DetectionResult> out = new LinkedHashMap<>();
        List<Loaded> snapshot = new ArrayList<>(loaded.values());
        for (Loaded l : snapshot) {
            DetectionResult r = sandbox.execute(l.plugin(), context, l.runtime());
            persistIfRegistered(l.runtime());
            out.put(l.runtime().getPluginId(), r);
        }
        return out;
    }

    private void persistIfRegistered(PluginRuntime runtime) {
        if (runtime.getPluginId() != null && loaded.containsKey(runtime.getPluginId())) {
            runtimeRepository.save(runtime);
        }
    }

    private void closeQuietly(Loaded l) {
        try {
            l.plugin().destroy();
        } catch (Throwable t) {
            log.warn("插件 destroy 异常 id={} err={}", l.runtime().getPluginId(), t.toString());
        }
        try {
            l.loader().close();
        } catch (IOException e) {
            log.warn("插件类加载器关闭异常 id={} err={}", l.runtime().getPluginId(), e.getMessage());
        }
    }

    /** 从 jar（优先清单属性 PACC-Plugin-Class）/ classes 目录解析插件主类。 */
    private Class<? extends DetectionPlugin> resolvePluginClass(ChildFirstClassLoader loader, File target)
            throws IOException, ClassNotFoundException {
        if (target.isFile() && target.getName().endsWith(".jar")) {
            try (JarFile jar = new JarFile(target)) {
                Manifest mf = jar.getManifest();
                if (mf != null) {
                    String cn = mf.getMainAttributes().getValue("PACC-Plugin-Class");
                    if (cn != null && !cn.isBlank()) {
                        return asPluginClass(loader.loadClass(cn.trim()));
                    }
                }
                int scanned = 0;
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements() && scanned < props.getPlugin().getMaxScanClasses()) {
                    JarEntry e = entries.nextElement();
                    String n = e.getName();
                    if (!n.endsWith(".class") || n.startsWith("META-INF/") || n.contains("module-info")) {
                        continue;
                    }
                    scanned++;
                    Class<?> c = tryLoad(loader, n.substring(0, n.length() - ".class".length()).replace('/', '.'));
                    if (c != null && DetectionPlugin.class.isAssignableFrom(c) && !c.isInterface()) {
                        return asPluginClass(c);
                    }
                }
            }
        } else if (target.isDirectory()) {
            List<File> files = new ArrayList<>();
            collectClasses(target, files, props.getPlugin().getMaxScanClasses());
            for (File f : files) {
                String rel = target.toPath().relativize(f.toPath()).toString();
                String cn = rel.substring(0, rel.length() - ".class".length())
                        .replace(File.separatorChar, '.');
                Class<?> c = tryLoad(loader, cn);
                if (c != null && DetectionPlugin.class.isAssignableFrom(c) && !c.isInterface()) {
                    return asPluginClass(c);
                }
            }
        }
        throw new ClassNotFoundException("未在 " + target + " 中找到 DetectionPlugin 实现（可在 MANIFEST 声明 PACC-Plugin-Class）");
    }

    private void collectClasses(File dir, List<File> out, int max) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            if (out.size() >= max) {
                return;
            }
            if (f.isDirectory()) {
                collectClasses(f, out, max);
            } else if (f.getName().endsWith(".class") && !f.getName().contains("module-info")) {
                out.add(f);
            }
        }
    }

    private Class<?> tryLoad(ChildFirstClassLoader loader, String className) {
        try {
            return loader.loadClass(className);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Class<? extends DetectionPlugin> asPluginClass(Class<?> c) {
        return (Class<? extends DetectionPlugin>) c;
    }
}