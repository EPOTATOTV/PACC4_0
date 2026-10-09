package com.potatotv.paccclient.plugin;

import com.example.pacc.sample.SamplePlugin;
import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.probe.SystemProbes;
import com.potatotv.paccclient.spi.PluginMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PluginManager} 加载流程测试（文档 §2.4）。 */
class PluginManagerTest {

    private static final String ENTRY_CLASS = "com.example.pacc.sample.SamplePlugin";

    @TempDir
    Path tmp;

    private final List<String> registeredRules = new ArrayList<>();

    @BeforeEach
    void reset() {
        SamplePlugin.reset();
        registeredRules.clear();
    }

    private Path pluginsDir() throws IOException {
        Path dir = tmp.resolve("plugins");
        Files.createDirectories(dir);
        return dir;
    }

    private static PluginLoadOptions devOptions() {
        return new PluginLoadOptions(true, Set.of(), PluginMetadata.CURRENT_API_VERSION, Integer.MAX_VALUE);
    }

    private static PluginLoadOptions strictOptions() {
        return new PluginLoadOptions(false, Set.of(), PluginMetadata.CURRENT_API_VERSION, Integer.MAX_VALUE);
    }

    private PluginManager manager(Path dir, PluginLoadOptions options) {
        return new PluginManager(dir, SystemProbes.create(),
                (type, script) -> registeredRules.add(type), new EventBus(), options);
    }

    private static Path writeJar(Path dir, String fileName, String id, int apiVersion) throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", id);
        meta.put("name", "Sample");
        meta.put("version", "1.0.0");
        meta.put("author", "tester");
        meta.put("description", "test plugin");
        meta.put("apiVersion", apiVersion);
        meta.put("minClientBuild", 0);
        meta.put("entryClass", ENTRY_CLASS);
        Path jar = dir.resolve(fileName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/pacc-plugin.json"));
            out.write(Json.encode(meta).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    @Test
    void loadsUnsignedJarInDevMode() throws IOException {
        Path dir = pluginsDir();
        writeJar(dir, "a.jar", "com.example.sample", 1);
        try (PluginManager manager = manager(dir, devOptions())) {
            assertEquals(1, manager.loadAll());
            assertTrue(SamplePlugin.loaded);
            assertEquals(1, manager.loadedCount());
            assertEquals(List.of("sample_detector"),
                    manager.detectors().stream().map(d -> d.id()).toList());
            assertEquals(1, manager.featureProviders().size());
            assertEquals(List.of("speed"), registeredRules);
        }
    }

    @Test
    void rejectsUnsignedJarByDefault() throws IOException {
        Path dir = pluginsDir();
        writeJar(dir, "a.jar", "com.example.sample", 1);
        try (PluginManager manager = manager(dir, strictOptions())) {
            assertEquals(0, manager.loadAll());
            assertFalse(SamplePlugin.loaded);
        }
    }

    @Test
    void rejectsIncompatibleApiVersion() throws IOException {
        Path dir = pluginsDir();
        writeJar(dir, "a.jar", "com.example.sample", 99);
        try (PluginManager manager = manager(dir, devOptions())) {
            assertEquals(0, manager.loadAll());
            assertFalse(SamplePlugin.loaded);
        }
    }

    @Test
    void rejectsDuplicatePluginId() throws IOException {
        Path dir = pluginsDir();
        writeJar(dir, "a.jar", "com.example.sample", 1);
        writeJar(dir, "b.jar", "com.example.sample", 1);
        try (PluginManager manager = manager(dir, devOptions())) {
            assertEquals(1, manager.loadAll());
        }
    }

    @Test
    void callsOnUnloadOnClose() throws IOException {
        Path dir = pluginsDir();
        writeJar(dir, "a.jar", "com.example.sample", 1);
        PluginManager manager = manager(dir, devOptions());
        manager.loadAll();
        manager.close();
        assertTrue(SamplePlugin.unloaded);
    }

    @Test
    void missingMetadataIsSkipped() throws IOException {
        Path dir = pluginsDir();
        Path jar = dir.resolve("bad.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("readme.txt"));
            out.write("no metadata".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        try (PluginManager manager = manager(dir, devOptions())) {
            assertEquals(0, manager.loadAll());
        }
    }

    @Test
    void descriptorParsing() {
        PluginDescriptor descriptor = PluginDescriptor.fromJson(Map.of(
                "id", "net.potatotv.ce", "name", "CE", "version", "1.2.0",
                "author", "POTATOTV", "apiVersion", 1, "entryClass", "net.potatotv.ce.Plugin"));
        assertEquals("net.potatotv.ce", descriptor.id());
        assertEquals("net.potatotv.ce.Plugin", descriptor.entryClass());
        assertEquals(1, descriptor.apiVersion());
    }

    @Test
    void rejectsPathTraversalId() throws IOException {
        Path dir = pluginsDir();
        writeJar(dir, "a.jar", "../../evil", 1);
        try (PluginManager manager = manager(dir, devOptions())) {
            assertEquals(0, manager.loadAll());
            assertFalse(SamplePlugin.loaded);
        }
    }

    @Test
    void plainJarIsUnsigned() throws IOException {
        Path dir = pluginsDir();
        Path jar = writeJar(dir, "a.jar", "com.example.sample", 1);
        assertEquals(PluginSignatureVerifier.Status.UNSIGNED,
                PluginSignatureVerifier.verify(jar, Set.of()).status());
    }
}