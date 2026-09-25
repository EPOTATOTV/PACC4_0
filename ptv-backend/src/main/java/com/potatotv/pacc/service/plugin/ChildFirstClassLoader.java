package com.potatotv.pacc.service.plugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Set;

/**
 * 子优先（child-first）类加载器：优先从插件自身的 jar / 目录加载类，实现同版本类的热加载替换；
 * 仅对 JVM 核心包与 PACC 插件 SPI 包委派给父加载器，避免插件自带类污染平台类型。
 */
public class ChildFirstClassLoader extends URLClassLoader {

    /** 必须委派父加载器的包前缀（核心库 + 插件 SPI，保证接口类型一致）。 */
    private static final Set<String> PARENT_FIRST_PREFIXES = Set.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "jakarta.", "org.w3c.", "org.xml.",
            "com.potatotv.pacc.domain.plugin.", "com.potatotv.pacc.domain.FeatureVector");

    static {
        registerAsParallelCapable();
    }

    public ChildFirstClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (isParentFirst(name)) {
                    loaded = super.loadClass(name, false);
                } else {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException e) {
                        loaded = super.loadClass(name, false);
                    }
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private static boolean isParentFirst(String name) {
        for (String prefix : PARENT_FIRST_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 关闭并释放底层 jar 句柄（卸载时调用）。 */
    @Override
    public void close() throws IOException {
        super.close();
    }
}