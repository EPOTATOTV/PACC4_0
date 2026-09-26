package com.potatotv.pcu;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * 平台适配接口（设计文档 §4.7.1 原文）。
 *
 * <p>PCU 核心只依赖这 8 个动作，各平台的差异全部收敛在实现类里：Windows/macOS/Linux
 * 用 {@code ProcessBuilder} 直接实现；Android 由 App 侧用 JNI/Java 桥接实现；
 * iOS 受限只能跳 App Store，见 {@code IosPlatformAdapter}。</p>
 */
public interface PlatformAdapter {

    /** 停止 PACC 进程。 */
    void stopPacc();

    /** 启动 PACC 进程。 */
    void startPacc();

    /** 重启 PACC：默认实现是 stop 后 start，平台需要额外等待时可覆写。 */
    default void restartPacc() {
        stopPacc();
        startPacc();
    }

    /** 获取安装目录。 */
    Path getInstallDir();

    /** 获取临时目录。 */
    Path getTempDir();

    /** 请求存储权限（移动端）；桌面平台直接返回 true。 */
    CompletableFuture<Boolean> requestStoragePermission();

    /** 显示更新通知（系统通知栏）。 */
    void showUpdateNotification(String title, String message);

    /**
     * 是否有足够存储空间放下 requiredBytes。
     *
     * <p>判断依据是临时目录所在分区，因为下载与解包都发生在那里。</p>
     */
    boolean hasEnoughSpace(long requiredBytes);
}