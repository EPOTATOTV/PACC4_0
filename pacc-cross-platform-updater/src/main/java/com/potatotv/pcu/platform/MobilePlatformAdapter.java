package com.potatotv.pcu.platform;

import com.potatotv.pcu.PcuException;
import com.potatotv.pcu.PlatformAdapter;
import com.potatotv.pcu.UpdatePlatform;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Android / HarmonyOS 适配（设计文档 §4.7.2 的两条移动端适配）。
 *
 * <p>这两个平台的公共形状是一样的：服务生命周期、权限、通知都落在各自的宿主 API 上
 * （Android 用 JNI + Java，HarmonyOS 用 ArkTS 桥接），制品（APK/HAP）交给系统安装器。
 * 所以这里把差异收敛成一个 {@link Bridge}，平台侧只需要实现它，PCU 这层不含任何
 * Android/HarmonyOS SDK 依赖——这也是能在桌面开发机上编译、在 CI 与真机上验证的前提。</p>
 *
 * <p>因为制品由系统安装器接管，PCU 核心不会做文件替换，也不会为它建回滚备份：
 * 安装失败时旧版本仍由系统保留。</p>
 */
public final class MobilePlatformAdapter implements PackageInstallerAdapter {

    /** 平台侧桥接。实现类在 App 内，PCU 只按这个契约调用。 */
    public interface Bridge {

        void stopService();

        void startService();

        Path installDir();

        Path tempDir();

        CompletableFuture<Boolean> requestStoragePermission();

        void notify(String title, String message);

        boolean hasEnoughSpace(long requiredBytes);

        /** 交给系统包安装器安装（Android PackageInstaller / HarmonyOS 包管理器）。 */
        void installPackage(Path artifact);
    }

    private final UpdatePlatform platform;
    private final Bridge bridge;

    public MobilePlatformAdapter(UpdatePlatform platform, Bridge bridge) {
        this.platform = requireMobile(platform);
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    public UpdatePlatform platform() {
        return platform;
    }

    @Override
    public void stopPacc() {
        bridge.stopService();
    }

    @Override
    public void startPacc() {
        bridge.startService();
    }

    @Override
    public Path getInstallDir() {
        return bridge.installDir();
    }

    @Override
    public Path getTempDir() {
        return bridge.tempDir();
    }

    @Override
    public CompletableFuture<Boolean> requestStoragePermission() {
        return bridge.requestStoragePermission();
    }

    @Override
    public void showUpdateNotification(String title, String message) {
        bridge.notify(title, message);
    }

    @Override
    public boolean hasEnoughSpace(long requiredBytes) {
        return bridge.hasEnoughSpace(requiredBytes);
    }

    @Override
    public void installPackage(Path artifact) {
        bridge.installPackage(artifact);
    }

    private static UpdatePlatform requireMobile(UpdatePlatform platform) {
        Objects.requireNonNull(platform, "platform");
        if (platform != UpdatePlatform.ANDROID && platform != UpdatePlatform.HARMONY) {
            throw new PcuException("MobilePlatformAdapter 只用于移动端平台，收到：" + platform.wire());
        }
        return platform;
    }
}