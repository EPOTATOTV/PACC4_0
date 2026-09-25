package com.potatotv.pacc.service.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.df.DfProperties;
import com.potatotv.pacc.domain.Plugin;
import com.potatotv.pacc.domain.plugin.PluginRuntime;
import com.potatotv.pacc.repository.PluginRepository;
import com.potatotv.pacc.repository.PluginRuntimeRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 插件热加载路径边界单测。
 *
 * <p>热加载请求体里的 {@code path} 决定了类加载器去读哪个文件，这一层如果失守，
 * 任何持有插件管理权限的账号都能让进程加载插件目录之外的 jar（含系统自带 jar）并执行其代码。
 * 因此这里逐条钉住 {@code resolvePluginArtifact} 的拒绝分支，同时保留一条放行用例，
 * 避免边界被收得过头把正常插件一起挡掉。</p>
 */
class PluginManagerPathBoundaryTest {

    @TempDir
    Path tmp;

    private PluginRepository pluginRepository;
    private PluginRuntimeRepository runtimeRepository;
    private PluginManager manager;
    private Path pluginDir;

    @BeforeEach
    void setUp() throws IOException {
        pluginDir = Files.createDirectories(tmp.resolve("plugins"));
        pluginRepository = mock(PluginRepository.class);
        runtimeRepository = mock(PluginRuntimeRepository.class);
        when(runtimeRepository.findById(anyString())).thenReturn(Optional.empty());
        when(runtimeRepository.save(any(PluginRuntime.class))).thenAnswer(inv -> inv.getArgument(0));
        DfProperties props = new DfProperties();
        props.getPlugin().setPluginDir(pluginDir.toString());
        manager = new PluginManager(pluginRepository, runtimeRepository, new PluginSandbox(props), props);
    }

    @Test
    void blankPathRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> manager.loadPlugin(null, "  "));
        assertTrue(e.getMessage().contains("插件路径不能为空"), e.getMessage());
    }

    @Test
    void absolutePathRejected() throws IOException {
        Path outside = Files.writeString(tmp.resolve("outside.jar"), "not a plugin");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> manager.loadPlugin(null, outside.toString()));
        assertTrue(e.getMessage().contains("必须是插件目录内的相对路径"), e.getMessage());
    }

    @Test
    void traversalOutsidePluginDirRejected() throws IOException {
        Files.writeString(tmp.resolve("outside.jar"), "not a plugin");

        for (String path : new String[]{"../outside.jar", "nested/../../outside.jar"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> manager.loadPlugin(null, path), "应拒绝越界路径: " + path);
            assertTrue(e.getMessage().contains("越界"), e.getMessage());
        }
    }

    @Test
    void digestMismatchWithMarketEntryRejected() throws IOException {
        Files.write(pluginDir.resolve("tampered.jar"), new byte[]{0x50, 0x4b, 0x03, 0x04});
        Plugin market = Plugin.builder()
                .pluginId("p1")
                .name("市场条目")
                .pluginVersion("1.0.0")
                .signatureSha256("0000000000000000000000000000000000000000000000000000000000000000")
                .build();
        when(pluginRepository.findById("p1")).thenReturn(Optional.of(market));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> manager.loadPlugin("p1", "tampered.jar"));
        assertTrue(e.getMessage().contains("摘要与市场登记不一致"), e.getMessage());
    }

    @Test
    void relativeDirectoryInsidePluginDirLoads() throws IOException {
        Path packaged = Files.createDirectories(pluginDir.resolve("fixture")
                .resolve("com/potatotv/pacc/service/plugin"));
        try (InputStream in = FixtureDetectionPlugin.class.getResourceAsStream("FixtureDetectionPlugin.class")) {
            assertNotNull(in, "取不到测试用插件字节码");
            Files.copy(in, packaged.resolve("FixtureDetectionPlugin.class"));
        }

        PluginRuntime runtime = manager.loadPlugin(null, "fixture");

        assertEquals("fixture", runtime.getPluginId());
        assertEquals(PluginRuntime.ST_LOADED, runtime.getState());
        assertEquals("Fixture Plugin", runtime.getName());
        assertEquals("log", runtime.getDeclaredApis());
    }
}