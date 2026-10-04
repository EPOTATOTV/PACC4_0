package com.potatotv.paccclient.plugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Set;

/**
 * 插件类加载器（文档 §2.3 约束 1、2）：child-first 隔离 + 宿主类白名单。
 *
 * <p>三条规则：</p>
 * <ol>
 *   <li><b>契约类共享</b>：{@code java.*} / {@code javax.*} / {@code jdk.*} / {@code sun.*} 以及
 *       宿主 SPI 与探针/事件契约类一律由父加载器提供。插件与宿主必须使用<em>同一份</em>
 *       {@code Detector}、{@code DetectionEvent} 等类型，否则 {@code instanceof} 与注册表都会失配。</li>
 *   <li><b>插件类 child-first</b>：插件自己的类优先从本 JAR 加载，实现不同插件之间的类隔离
 *       （同名工具类互不干扰）。</li>
 *   <li><b>宿主内部类封堵</b>：凡是 {@code com.potatotv.paccclient.} 下、又不在白名单里的类，
 *       直接抛 {@code ClassNotFoundException}，不允许插件顺着父加载器摸到宿主内部实现。</li>
 * </ol>
 *
 * <p><b>能力边界</b>：类加载隔离只能约束「能不能引用某个类」，不能阻止插件用 JDK 自带的
 * {@code java.io.File} / {@code java.net.Socket} 做副作用操作 —— Java 21 已无 SecurityManager，
 * 进程内无法做能力级硬隔离。文档 §2.3 的「权限白名单」在本项目中以「API 白名单 + 网络/文件
 * 能力不暴露」的方式实现，剩余风险由签名校验（只加载受信任插件）兜底。</p>
 */
public final class PluginClassLoader extends URLClassLoader {

    private static final String HOST_PACKAGE = "com.potatotv.paccclient.";

    /** 允许插件与宿主共享的宿主类包前缀。 */
    private static final String[] SHARED_PREFIXES = {
            "com.potatotv.paccclient.spi.",
            "com.potatotv.paccclient.probe.",
            "com.potatotv.paccclient.detection.",
    };

    /** 允许共享的宿主类精确名（无包前缀通配）。 */
    private static final Set<String> SHARED_EXACT = Set.of(
            "com.potatotv.paccclient.Json");

    static {
        registerAsParallelCapable();
    }

    public PluginClassLoader(Path jar, ClassLoader parent) throws IOException {
        super(new URL[]{jar.toUri().toURL()}, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (isShared(name)) {
                    loaded = getParent().loadClass(name);
                } else if (name.startsWith(HOST_PACKAGE)) {
                    // 宿主内部类：不共享、也不从插件 JAR 找，直接拒绝
                    throw new ClassNotFoundException("宿主内部类不对插件开放：" + name);
                } else {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException notInPlugin) {
                        loaded = getParent().loadClass(name);
                    }
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private static boolean isShared(String name) {
        if (SHARED_EXACT.contains(name)) {
            return true;
        }
        if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                || name.startsWith("sun.") || name.startsWith("com.sun.")
                || name.startsWith("org.w3c.") || name.startsWith("org.xml.")) {
            return true;
        }
        for (String prefix : SHARED_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}