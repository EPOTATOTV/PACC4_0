package com.potatotv.pcu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupManagerTest {

    private static PcuConfig config(Path installDir, Path backupRoot) {
        return PcuConfig.builder()
                .currentVersion("5.4.0")
                .adapter(new StubAdapter(installDir, installDir.resolve("tmp")))
                .backupRoot(backupRoot)
                .build();
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void 备份后恢复原内容(@TempDir Path root) throws IOException {
        Path install = Files.createDirectories(root.resolve("install"));
        Path jar = install.resolve("ptv-client-5.4.0.jar");
        write(jar, "旧内容");

        BackupManager manager = new BackupManager(config(install, root.resolve("backup")));
        BackupManager.BackupHandle handle = manager.create("5.4.0", List.of(jar));
        assertEquals(List.of(jar), handle.sources());
        assertTrue(manager.hasBackup("5.4.0"));

        // 模拟更新把文件覆盖掉
        write(jar, "新内容");
        manager.restore(handle);

        assertEquals("旧内容", Files.readString(jar, StandardCharsets.UTF_8));
    }

    @Test
    void 不存在的文件不算备份但记为新增(@TempDir Path root) throws IOException {
        Path install = Files.createDirectories(root.resolve("install"));
        BackupManager manager = new BackupManager(config(install, root.resolve("backup")));
        Path missing = install.resolve("没这个文件");

        BackupManager.BackupHandle handle = manager.create("5.4.0", List.of(missing));

        assertTrue(handle.sources().isEmpty(), "没有旧文件可备份");
        assertEquals(List.of(missing), handle.added(), "要记下来，回滚时删掉");
    }

    @Test
    void 回滚删除新版本新增的文件(@TempDir Path root) throws IOException {
        Path install = Files.createDirectories(root.resolve("install"));
        Path jar = install.resolve("ptv-client.jar");
        Path added = install.resolve("libs").resolve("新组件.jar");
        write(jar, "旧内容");

        BackupManager manager = new BackupManager(config(install, root.resolve("backup")));
        BackupManager.BackupHandle handle = manager.create("5.4.0", List.of(jar, added));

        // 模拟更新：覆盖旧文件，并且新增一个旧版本没有的文件
        write(jar, "新内容");
        write(added, "新版本才有的字节");
        manager.restore(handle);

        assertEquals("旧内容", Files.readString(jar, StandardCharsets.UTF_8));
        assertFalse(Files.exists(added), "新版本新增的文件必须删掉，否则旧版本会读到它");
    }

    @Test
    void 备份内容被改动过时拒绝恢复(@TempDir Path root) throws IOException {
        Path install = Files.createDirectories(root.resolve("install"));
        Path jar = install.resolve("ptv-client.jar");
        write(jar, "旧内容");

        BackupManager manager = new BackupManager(config(install, root.resolve("backup")));
        BackupManager.BackupHandle handle = manager.create("5.4.0", List.of(jar));
        write(handle.dir().resolve("0000.bin"), "被篡改的备份内容");
        write(jar, "新内容");

        assertThrows(PcuException.class, () -> manager.restore(handle));
        assertEquals("新内容", Files.readString(jar, StandardCharsets.UTF_8), "校验不过就不能写回");
    }

    @Test
    void 清理时不动没有清单的目录(@TempDir Path root) throws IOException {
        Path install = Files.createDirectories(root.resolve("install"));
        Path jar = install.resolve("ptv-client.jar");
        Path backupRoot = root.resolve("backup");
        write(jar, "内容");
        BackupManager manager = new BackupManager(config(install, backupRoot));

        for (String version : List.of("5.4.0", "5.5.0", "5.6.0", "5.7.0")) {
            manager.create(version, List.of(jar));
        }
        // 备份根目录就在安装目录里，别人放的子目录不能被连带删掉
        Path outsider = Files.createDirectories(backupRoot.resolve("logs"));
        write(outsider.resolve("app.log"), "别删我");

        assertEquals(2, manager.prune());
        assertTrue(Files.exists(outsider.resolve("app.log")));
    }

    @Test
    void 只保留最近的两个版本(@TempDir Path root) throws IOException {
        Path install = Files.createDirectories(root.resolve("install"));
        Path jar = install.resolve("ptv-client.jar");
        write(jar, "内容");
        BackupManager manager = new BackupManager(config(install, root.resolve("backup")));

        for (String version : List.of("5.4.0", "5.5.0", "5.6.0", "5.7.0")) {
            manager.create(version, List.of(jar));
        }
        int removed = manager.prune();

        assertEquals(2, removed);
        assertFalse(manager.hasBackup("5.4.0"), "最旧的两个应被清理");
        assertFalse(manager.hasBackup("5.5.0"), "最旧的两个应被清理");
        assertTrue(manager.hasBackup("5.6.0"));
        assertTrue(manager.hasBackup("5.7.0"));
    }

    @Test
    void 版本号排序按语义版本而非字符串(@TempDir Path root) throws IOException {
        Path install = Files.createDirectories(root.resolve("install"));
        Path jar = install.resolve("ptv-client.jar");
        write(jar, "内容");
        BackupManager manager = new BackupManager(config(install, root.resolve("backup")));

        // 字符串序会把 5.9.0 排在 5.10.0 之后，语义版本序不会
        for (String version : List.of("5.9.0", "5.10.0", "5.11.0")) {
            manager.create(version, List.of(jar));
        }
        manager.prune();

        assertFalse(manager.hasBackup("5.9.0"));
        assertTrue(manager.hasBackup("5.10.0"));
        assertTrue(manager.hasBackup("5.11.0"));
    }

    @Test
    void 拒绝含路径穿越的版本号(@TempDir Path root) throws IOException {
        Path install = Files.createDirectories(root.resolve("install"));
        BackupManager manager = new BackupManager(config(install, root.resolve("backup")));

        assertThrows(PcuException.class, () -> BackupManager.sanitize("../../etc"));
        assertThrows(PcuException.class, () -> BackupManager.sanitize(".."));
        assertThrows(PcuException.class, () -> BackupManager.sanitize("5.4.0/../evil"));
        assertThrows(PcuException.class, () -> BackupManager.sanitize(" "));
        assertEquals("5.4.0-beta.1", BackupManager.sanitize(" 5.4.0-beta.1 "));
    }

    @Test
    void 重新打开已有备份可恢复(@TempDir Path root) throws IOException {
        Path install = Files.createDirectories(root.resolve("install"));
        Path jar = install.resolve("ptv-client.jar");
        write(jar, "旧内容");
        BackupManager manager = new BackupManager(config(install, root.resolve("backup")));
        manager.create("5.4.0", List.of(jar));

        write(jar, "新内容");
        manager.restore(manager.open("5.4.0"));

        assertEquals("旧内容", Files.readString(jar, StandardCharsets.UTF_8));
    }

    @Test
    void 清单缺失时报错(@TempDir Path root) throws IOException {
        Path install = Files.createDirectories(root.resolve("install"));
        BackupManager manager = new BackupManager(config(install, root.resolve("backup")));
        assertThrows(PcuException.class, () -> manager.open("9.9.9"));
    }
}