package com.potatotv.pcu.platform;

import com.potatotv.pcu.PcuException;
import com.potatotv.pcu.UpdatePlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformAdapterTest {

    @Test
    void 客户端jar按语义版本取最高的那个(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("ptv-client-5.9.0.jar"));
        Files.createFile(dir.resolve("ptv-client-5.10.0.jar"));
        Files.createFile(dir.resolve("ptv-agent-5.10.0.jar"));
        Files.createFile(dir.resolve("ptv-client-5.10.0.jar.sha256"));

        assertEquals(dir.resolve("ptv-client-5.10.0.jar"),
                WindowsPlatformAdapter.resolveClientJar(dir));
    }

    @Test
    void 找不到客户端jar时不静默通过(@TempDir Path dir) {
        assertThrows(PcuException.class, () -> WindowsPlatformAdapter.resolveClientJar(dir));
    }

    @Test
    void 安装目录不存在时构造即失败(@TempDir Path root) {
        assertThrows(PcuException.class,
                () -> new WindowsPlatformAdapter(root.resolve("不存在的目录"), root));
    }

    @Test
    void windows停服脚本不把安装目录当通配符模式(@TempDir Path root) {
        // Windows 的路径解析不接受 * 和 ?（会直接抛 InvalidPath），用同样是 -like 模式字符的 [ ] 来验
        Path install = root.resolve("PACC's [x64] test");
        List<String> command = WindowsPlatformAdapter.stopScript(install);
        String script = command.get(command.size() - 1);

        // 单引号必须翻倍，否则路径里的引号会把 -Command 的字符串提前结束
        assertTrue(script.contains("PACC''s [x64] test"), script);
        // 匹配走的是 IndexOf 字面量，不再有 -like 的模式串
        assertFalse(script.contains("-like '*"), script);
        assertTrue(script.contains("[System.StringComparison]::OrdinalIgnoreCase"), script);
    }

    @Test
    void 只给一半命令直接拒绝() {
        assertThrows(PcuException.class, () -> new ProcessPlatformAdapter.Commands(
                List.of("stop"), List.of(), Duration.ofSeconds(1), null));
        assertThrows(PcuException.class, () -> new ProcessPlatformAdapter.Commands(
                List.of(), List.of("start"), Duration.ofSeconds(1), null));
    }

    @Test
    void 拒绝以横杠开头的systemd单元名(@TempDir Path root) {
        assertThrows(PcuException.class, () -> new LinuxPlatformAdapter(root, root, "-evil"));
        assertThrows(PcuException.class, () -> new LinuxPlatformAdapter(root, root, " "));
    }

    @Test
    void 拒绝以横杠开头的macos启动目标(@TempDir Path root) {
        assertThrows(PcuException.class,
                () -> new MacOsPlatformAdapter(root, root, Path.of("-rf")));
    }

    @Test
    void 桌面平台不需要申请存储权限(@TempDir Path root) throws Exception {
        ProcessPlatformAdapter adapter = new LinuxPlatformAdapter(root, root);
        assertTrue(adapter.requestStoragePermission().get(5, TimeUnit.SECONDS));
        assertEquals(root.toAbsolutePath().normalize(), adapter.getInstallDir());
    }

    @Test
    void ios不做停服重启只保留商店跳转(@TempDir Path root) {
        Path data = root.resolve("data");
        IosPlatformAdapter adapter = new IosPlatformAdapter(data, root.resolve("tmp"),
                "https://apps.apple.com/app/id123");

        // 数据目录里的配置热更新不需要停服窗口，不能因为「二进制不能自更新」把它一起挡掉
        adapter.stopPacc();
        adapter.startPacc();
        assertEquals(data.toAbsolutePath().normalize(), adapter.getInstallDir());
        // 没接入 UIKit 出口时只告警，不假装通知已发出
        adapter.showUpdateNotification("标题", "正文");
        adapter.openAppStore();
    }

    @Test
    void 移动端适配器只接受移动平台(@TempDir Path root) {
        assertThrows(PcuException.class, () -> new MobilePlatformAdapter(
                UpdatePlatform.WINDOWS, new NoopBridge(root)));
        MobilePlatformAdapter adapter = new MobilePlatformAdapter(UpdatePlatform.HARMONY,
                new NoopBridge(root));
        assertEquals(UpdatePlatform.HARMONY, adapter.platform());
        assertTrue(adapter instanceof PackageInstallerAdapter);
    }

    @Test
    void 空间检查在探测不到时放行(@TempDir Path root) {
        // 目录尚不存在也能探测（内部会先建目录）
        assertTrue(PlatformSupport.hasEnoughSpace(root.resolve("还没建"), 1));
        assertFalse(PlatformSupport.hasEnoughSpace(root, Long.MAX_VALUE));
    }

    private static final class NoopBridge implements MobilePlatformAdapter.Bridge {

        private final Path dir;

        NoopBridge(Path dir) {
            this.dir = dir;
        }

        @Override
        public void stopService() {
        }

        @Override
        public void startService() {
        }

        @Override
        public Path installDir() {
            return dir;
        }

        @Override
        public Path tempDir() {
            return dir;
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
        public void installPackage(Path artifact) {
        }
    }
}