package com.potatotv.paccclient.plugin;

import com.potatotv.paccclient.spi.Detector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PluginClassLoader} 隔离规则测试（文档 §2.3 约束 1、2）。 */
class PluginClassLoaderTest {

    @TempDir
    Path tmp;

    private PluginClassLoader newLoader() throws IOException {
        Path jar = tmp.resolve("empty.jar");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar))) {
            // 空 JAR：本测试只验证类加载委派规则
        }
        return new PluginClassLoader(jar, PluginClassLoaderTest.class.getClassLoader());
    }

    @Test
    void sharedContractClassComesFromHost() throws Exception {
        try (PluginClassLoader loader = newLoader()) {
            // 插件与宿主必须用同一份 SPI 契约类，否则 instanceof / 注册表会失配
            assertSame(Detector.class, loader.loadClass("com.potatotv.paccclient.spi.Detector"));
        }
    }

    @Test
    void hostInternalClassBlocked() throws Exception {
        try (PluginClassLoader loader = newLoader()) {
            assertThrows(ClassNotFoundException.class,
                    () -> loader.loadClass("com.potatotv.paccclient.control.DetectionController"));
        }
    }

    @Test
    void jdkClassLoads() throws Exception {
        try (PluginClassLoader loader = newLoader()) {
            assertSame(java.util.List.class, loader.loadClass("java.util.List"));
        }
    }

    @Test
    void nonPluginClassFallsBackToParent() throws Exception {
        try (PluginClassLoader loader = newLoader()) {
            Class<?> type = loader.loadClass("com.example.pacc.sample.SamplePlugin");
            assertTrue(type.getName().endsWith("SamplePlugin"));
        }
    }
}