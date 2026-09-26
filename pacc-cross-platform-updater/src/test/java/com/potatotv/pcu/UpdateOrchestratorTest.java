package com.potatotv.pcu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateOrchestratorTest {

    private static final String OLD = "旧客户端";
    private static final String NEW = "新客户端";

    private record Fixture(StubAdapter adapter, PcuConfig config, Path jar, FakeUpdateServer server)
            implements AutoCloseable {

        @Override
        public void close() {
            server.close();
        }
    }

    private static Fixture fixture(Path root, HealthProbe probe, Consumer<PcuConfig.Builder> customizer)
            throws IOException {
        Path install = Files.createDirectories(root.resolve("install"));
        Path temp = Files.createDirectories(root.resolve("temp"));
        Path jar = install.resolve("ptv-client-5.4.0.jar");
        Files.writeString(jar, OLD, StandardCharsets.UTF_8);

        FakeUpdateServer server = new FakeUpdateServer();
        server.artifact = NEW.getBytes(StandardCharsets.UTF_8);

        StubAdapter adapter = new StubAdapter(install, temp);
        PcuConfig.Builder builder = PcuConfig.builder()
                .baseUrl(server.baseUrl())
                .currentVersion("5.4.0")
                .platform(UpdatePlatform.WINDOWS)
                .pteid("PT0000000001")
                .adapter(adapter)
                .backupRoot(root.resolve("backup"))
                .mainArtifact(jar)
                .maxRetries(0)
                .healthCheckTimeout(Duration.ofMillis(300))
                .healthCheckInterval(Duration.ofMillis(50));
        if (probe != null) {
            builder.healthProbe(probe);
        }
        customizer.accept(builder);
        return new Fixture(adapter, builder.build(), jar, server);
    }

    private static Fixture fixture(Path root, HealthProbe probe) throws IOException {
        return fixture(root, probe, b -> { });
    }

    @Test
    void 走完全流程并上报success(@TempDir Path root) throws IOException {
        try (Fixture f = fixture(root, null)) {
            UpdateResult result = new UpdateOrchestrator(f.config()).runOnce();

            assertEquals(UpdateResult.Outcome.SUCCESS, result.outcome());
            assertFalse(result.deltaApplied());
            assertEquals(NEW, Files.readString(f.jar(), StandardCharsets.UTF_8));
            assertEquals(List.of("stop", "start"), f.adapter().calls);

            assertEquals(1, f.server().reports.size());
            assertEquals("success", f.server().reports.get(0).get("status"));
            assertEquals("5.4.0", f.server().reports.get(0).get("from_version"));
            assertEquals("5.5.0", f.server().reports.get(0).get("to_version"));
            assertEquals("PT0000000001", f.server().reports.get(0).get("pteid"));
        }
    }

    @Test
    void 健康检查不通过时回滚并上报rolled_back(@TempDir Path root) throws IOException {
        try (Fixture f = fixture(root, () -> false)) {
            UpdateResult result = new UpdateOrchestrator(f.config()).runOnce();

            assertEquals(UpdateResult.Outcome.ROLLED_BACK, result.outcome());
            assertEquals(OLD, Files.readString(f.jar(), StandardCharsets.UTF_8));
            // 应用阶段停/起一次，回滚阶段再停/起一次
            assertEquals(List.of("stop", "start", "stop", "start"), f.adapter().calls);
            assertEquals("rolled_back", f.server().reports.get(0).get("status"));
        }
    }

    @Test
    void 没有新版本时不上报(@TempDir Path root) throws IOException {
        try (Fixture f = fixture(root, null)) {
            f.server().hasUpdate = false;
            UpdateResult result = new UpdateOrchestrator(f.config()).runOnce();

            assertEquals(UpdateResult.Outcome.NO_UPDATE, result.outcome());
            assertNull(result.toVersion());
            assertEquals(OLD, Files.readString(f.jar(), StandardCharsets.UTF_8));
            assertTrue(f.adapter().calls.isEmpty());
            assertTrue(f.server().reports.isEmpty(), "没有更新就不该产生上报");
            assertTrue(f.adapter().notifications.isEmpty(), "没有更新就不该弹通知");
        }
    }

    @Test
    void 强制更新标记透传到结果(@TempDir Path root) throws IOException {
        try (Fixture f = fixture(root, null)) {
            f.server().forceUpdate = true;
            UpdateResult result = new UpdateOrchestrator(f.config()).runOnce();

            assertTrue(result.forceRequired());
            assertEquals(UpdateResult.Outcome.SUCCESS, result.outcome());
        }
    }

    @Test
    void 最低版本限制也算强制更新(@TempDir Path root) throws IOException {
        try (Fixture f = fixture(root, null)) {
            f.server().minAppVersion = "5.5.0";
            UpdateResult result = new UpdateOrchestrator(f.config()).runOnce();

            assertTrue(result.forceRequired(), "当前版本低于最低版本要求，应判为强制更新");
        }
    }

    @Test
    void 包校验不过则不替换文件(@TempDir Path root) throws IOException {
        try (Fixture f = fixture(root, null)) {
            f.server().checksumOverride = "0".repeat(64);
            UpdateResult result = new UpdateOrchestrator(f.config()).runOnce();

            assertEquals(UpdateResult.Outcome.FAILED, result.outcome());
            assertEquals(OLD, Files.readString(f.jar(), StandardCharsets.UTF_8));
            assertTrue(f.adapter().calls.isEmpty(), "校验没过就不该动服务");
            assertEquals("failed", f.server().reports.get(0).get("status"));
        }
    }

    @Test
    void 静默更新失败不弹通知但照常上报(@TempDir Path root) throws IOException {
        try (Fixture f = fixture(root, null, b -> b.silentUpdate(true))) {
            f.server().checksumOverride = "0".repeat(64);
            UpdateResult result = new UpdateOrchestrator(f.config()).runOnce();

            assertEquals(UpdateResult.Outcome.FAILED, result.outcome());
            assertTrue(f.adapter().notifications.isEmpty(), "静默更新失败不打扰用户");
            assertEquals("failed", f.server().reports.get(0).get("status"));
        }
    }

    @Test
    void 降级包被拒绝且不上报成功(@TempDir Path root) throws IOException {
        try (Fixture f = fixture(root, null)) {
            f.server().latestVersion = "5.4.0";
            UpdateResult result = new UpdateOrchestrator(f.config()).runOnce();

            assertEquals(UpdateResult.Outcome.FAILED, result.outcome());
            assertEquals(OLD, Files.readString(f.jar(), StandardCharsets.UTF_8));
            assertTrue(result.message().contains("降级"), "实际：" + result.message());
        }
    }

    @Test
    void 空间不足时提前失败(@TempDir Path root) throws IOException {
        try (Fixture f = fixture(root, null)) {
            f.adapter().enoughSpace = false;
            UpdateResult result = new UpdateOrchestrator(f.config()).runOnce();

            assertEquals(UpdateResult.Outcome.FAILED, result.outcome());
            assertTrue(result.message().contains("存储空间不足"), "实际：" + result.message());
            assertTrue(f.adapter().calls.isEmpty());
        }
    }

    @Test
    void 检查更新失败不产生上报(@TempDir Path root) throws IOException {
        try (Fixture f = fixture(root, null)) {
            // 把基址指向一个没人监听的端口，检查更新必然失败
            f.server().close();
            UpdateResult result = new UpdateOrchestrator(f.config()).runOnce();

            assertEquals(UpdateResult.Outcome.SKIPPED, result.outcome());
            assertEquals(OLD, Files.readString(f.jar(), StandardCharsets.UTF_8));
        }
    }
}