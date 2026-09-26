package com.potatotv.pcu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateApplierTest {

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static Path zip(Path target, Map<String, String> entries) throws IOException {
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return target;
    }

    private static PcuConfig.Builder base(Path install, Path temp, Path backup, StubAdapter adapter) {
        return PcuConfig.builder()
                .currentVersion("5.4.0")
                .adapter(adapter)
                .backupRoot(backup);
    }

    @Test
    void 单文件制品直接替换并先停后起(@TempDir Path root) throws IOException {
        Path install = Files.createDirectories(root.resolve("install"));
        Path temp = Files.createDirectories(root.resolve("temp"));
        Path jar = install.resolve("ptv-client-5.4.0.jar");
        write(jar, "旧版本");
        Path artifact = root.resolve("new.bin");
        write(artifact, "新版本");

        StubAdapter adapter = new StubAdapter(install, temp);
        PcuConfig config = base(install, temp, root.resolve("backup"), adapter)
                .mainArtifact(jar)
                .build();
        BackupManager backups = new BackupManager(config);
        UpdateApplier applier = new UpdateApplier(config, backups);

        UpdateApplier.ApplyOutcome outcome = applier.apply(artifact, "5.5.0");

        assertEquals("新版本", Files.readString(jar, StandardCharsets.UTF_8));
        assertEquals(List.of("stop", "start"), adapter.calls);
        assertTrue(outcome.rollbackable());
        assertEquals("5.5.0", outcome.backup().version());
        assertEquals(List.of(jar), outcome.targets());
    }

    @Test
    void zip制品按条目覆盖安装目录(@TempDir Path root) throws IOException {
        Path install = Files.createDirectories(root.resolve("install"));
        Path temp = Files.createDirectories(root.resolve("temp"));
        write(install.resolve("a.txt"), "旧 A");
        write(install.resolve("sub/b.txt"), "旧 B");
        write(install.resolve("untouched.txt"), "不该被动");

        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("a.txt", "新 A");
        entries.put("sub/b.txt", "新 B");
        Path artifact = zip(root.resolve("pkg.zip"), entries);

        StubAdapter adapter = new StubAdapter(install, temp);
        PcuConfig config = base(install, temp, root.resolve("backup"), adapter).build();
        UpdateApplier applier = new UpdateApplier(config, new BackupManager(config));

        applier.apply(artifact, "5.5.0");

        assertEquals("新 A", Files.readString(install.resolve("a.txt"), StandardCharsets.UTF_8));
        assertEquals("新 B", Files.readString(install.resolve("sub/b.txt"), StandardCharsets.UTF_8));
        assertEquals("不该被动", Files.readString(install.resolve("untouched.txt"), StandardCharsets.UTF_8));
        assertEquals(List.of("stop", "start"), adapter.calls);
    }

    @Test
    void 起服务失败时自动恢复旧文件(@TempDir Path root) throws IOException {
        Path install = Files.createDirectories(root.resolve("install"));
        Path temp = Files.createDirectories(root.resolve("temp"));
        Path jar = install.resolve("ptv-client-5.4.0.jar");
        write(jar, "旧版本");
        Path artifact = root.resolve("new.bin");
        write(artifact, "新版本");

        StubAdapter adapter = new StubAdapter(install, temp);
        adapter.failStart = true;
        PcuConfig config = base(install, temp, root.resolve("backup"), adapter)
                .mainArtifact(jar)
                .build();
        UpdateApplier applier = new UpdateApplier(config, new BackupManager(config));

        PcuException e = assertThrows(PcuException.class, () -> applier.apply(artifact, "5.5.0"));

        assertEquals("旧版本", Files.readString(jar, StandardCharsets.UTF_8));
        assertTrue(e.getMessage().contains("已恢复旧版本文件"), "实际：" + e.getMessage());
    }

    @Test
    void 停服务失败时不改文件也不留临时文件(@TempDir Path root) throws IOException {
        Path install = Files.createDirectories(root.resolve("install"));
        Path temp = Files.createDirectories(root.resolve("temp"));
        Path jar = install.resolve("ptv-client-5.4.0.jar");
        write(jar, "旧版本");
        Path artifact = root.resolve("new.bin");
        write(artifact, "新版本");

        StubAdapter adapter = new StubAdapter(install, temp);
        adapter.failStop = true;
        PcuConfig config = base(install, temp, root.resolve("backup"), adapter)
                .mainArtifact(jar)
                .build();
        UpdateApplier applier = new UpdateApplier(config, new BackupManager(config));

        assertThrows(PcuException.class, () -> applier.apply(artifact, "5.5.0"));

        assertEquals("旧版本", Files.readString(jar, StandardCharsets.UTF_8));
        try (var entries = Files.list(install)) {
            assertFalse(entries.anyMatch(p -> p.getFileName().toString().endsWith(".pcu-new")),
                    "临时文件应被清理");
        }
    }

    @Test
    void 拒绝越出安装目录的zip条目(@TempDir Path root) throws IOException {
        Path install = Files.createDirectories(root.resolve("install"));
        Path temp = Files.createDirectories(root.resolve("temp"));
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("../evil.txt", "越界");
        Path artifact = zip(root.resolve("pkg.zip"), entries);

        StubAdapter adapter = new StubAdapter(install, temp);
        PcuConfig config = base(install, temp, root.resolve("backup"), adapter).build();
        UpdateApplier applier = new UpdateApplier(config, new BackupManager(config));

        PcuException e = assertThrows(PcuException.class, () -> applier.apply(artifact, "5.5.0"));

        assertTrue(e.getMessage().contains("Zip Slip"), "实际：" + e.getMessage());
        // 校验发生在任何破坏性动作之前：不该已经停过服务
        assertTrue(adapter.calls.isEmpty(), "不该在条目校验失败前停服务");
        assertFalse(Files.exists(root.resolve("evil.txt")), "不该写出安装目录外的文件");
    }

    @Test
    void 未配置主制品时非zip包直接拒绝(@TempDir Path root) throws IOException {
        Path install = Files.createDirectories(root.resolve("install"));
        Path temp = Files.createDirectories(root.resolve("temp"));
        Path artifact = root.resolve("pkg.bin");
        write(artifact, "这根本不是 zip");

        StubAdapter adapter = new StubAdapter(install, temp);
        PcuConfig config = base(install, temp, root.resolve("backup"), adapter).build();
        UpdateApplier applier = new UpdateApplier(config, new BackupManager(config));

        PcuException e = assertThrows(PcuException.class, () -> applier.apply(artifact, "5.5.0"));

        assertTrue(e.getMessage().contains("必须是 zip 包"), "实际：" + e.getMessage());
        assertTrue(adapter.calls.isEmpty());
    }

    @Test
    void 移动端交给系统安装器且不建备份(@TempDir Path root) throws IOException {
        Path install = Files.createDirectories(root.resolve("install"));
        Path temp = Files.createDirectories(root.resolve("temp"));
        Path artifact = root.resolve("app.apk");
        write(artifact, "apk");

        StringBuilder installed = new StringBuilder();
        var adapter = new com.potatotv.pcu.platform.MobilePlatformAdapter(
                UpdatePlatform.ANDROID, new com.potatotv.pcu.platform.MobilePlatformAdapter.Bridge() {
            @Override
            public void stopService() {
                throw new AssertionError("移动端不该走文件替换流程");
            }

            @Override
            public void startService() {
                throw new AssertionError("移动端不该走文件替换流程");
            }

            @Override
            public Path installDir() {
                return install;
            }

            @Override
            public Path tempDir() {
                return temp;
            }

            @Override
            public java.util.concurrent.CompletableFuture<Boolean> requestStoragePermission() {
                return java.util.concurrent.CompletableFuture.completedFuture(true);
            }

            @Override
            public void notify(String title, String message) {
            }

            @Override
            public boolean hasEnoughSpace(long requiredBytes) {
                return true;
            }

            @Override
            public void installPackage(Path p) {
                installed.append(p.getFileName());
            }
        });

        PcuConfig config = PcuConfig.builder()
                .currentVersion("5.4.0")
                .platform(UpdatePlatform.ANDROID)
                .adapter(adapter)
                .backupRoot(root.resolve("backup"))
                .build();
        UpdateApplier applier = new UpdateApplier(config, new BackupManager(config));

        UpdateApplier.ApplyOutcome outcome = applier.apply(artifact, "5.5.0");

        assertEquals("app.apk", installed.toString());
        assertTrue(outcome.delegatedToInstaller());
        assertFalse(outcome.rollbackable());
    }
}