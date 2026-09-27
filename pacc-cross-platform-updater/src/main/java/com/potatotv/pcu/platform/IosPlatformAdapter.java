package com.potatotv.pcu.platform;

import com.potatotv.pcu.PlatformAdapter;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * iOS 适配（设计文档 §4.7.3）：iOS 不允许应用替换自己的二进制，二进制只能走 App Store。
 *
 * <p>所以这个适配器只做两件在 iOS 上确实成立的事：</p>
 * <ol>
 *   <li>配置文件 / 规则文件 / 模型文件的热更新——这些不是二进制，可以就地替换，
 *       更新流程照常走备份、原子替换、健康检查；</li>
 *   <li>提示用户去 App Store 升级二进制——发通知、跳转商店页。</li>
 * </ol>
 *
 * <p>通知与跳转必须落到 UIKit，PCU 这层拿不到，因此由宿主注入
 * {@link NotificationSink} 与 {@link StoreOpener}。不注入时只写日志，
 * 不假装通知已经发出去了。</p>
 */
public final class IosPlatformAdapter implements PlatformAdapter {

    /** 通知出口，实现方在 iOS 侧（UNUserNotificationCenter）。 */
    @FunctionalInterface
    public interface NotificationSink {
        void notify(String title, String message);
    }

    /** 商店跳转出口，实现方在 iOS 侧（UIApplication.open）。 */
    @FunctionalInterface
    public interface StoreOpener {
        void open(String appStoreUrl);
    }

    private static final Logger LOG = Logger.getLogger(IosPlatformAdapter.class.getName());

    private final Path dataDir;
    private final Path tempDir;
    private final String appStoreUrl;
    private final NotificationSink notifications;
    private final StoreOpener storeOpener;

    public IosPlatformAdapter(Path dataDir, Path tempDir, String appStoreUrl) {
        this(dataDir, tempDir, appStoreUrl, null, null);
    }

    public IosPlatformAdapter(Path dataDir, Path tempDir, String appStoreUrl,
                              NotificationSink notifications, StoreOpener storeOpener) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize();
        this.tempDir = Objects.requireNonNull(tempDir, "tempDir").toAbsolutePath().normalize();
        this.appStoreUrl = appStoreUrl;
        this.notifications = notifications;
        this.storeOpener = storeOpener;
    }

    /**
     * 不做停服：iOS 上应用既不能自杀式退出等着被自己拉起，也没有「停服窗口」这个动作。
     * 能就地替换的只有数据目录里的配置 / 规则 / 模型，改完即生效；二进制由 App Store 升级，
     * 不经过 PCU 的应用流程。这里抛异常只会把本来能做的配置热更新一起挡掉。
     */
    @Override
    public void stopPacc() {
        LOG.fine("iOS 不做停服：本平台只热更新数据目录里的配置 / 规则 / 模型");
    }

    @Override
    public void startPacc() {
        LOG.fine("iOS 不做重启：配置热更新不需要重启进程");
    }

    /** iOS 上的「安装目录」实际是应用数据目录：可热更新的配置 / 规则 / 模型都放这里。 */
    @Override
    public Path getInstallDir() {
        return dataDir;
    }

    @Override
    public Path getTempDir() {
        return tempDir;
    }

    /** iOS 没有运行期存储权限申请，沙箱目录随时可写。 */
    @Override
    public CompletableFuture<Boolean> requestStoragePermission() {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public void showUpdateNotification(String title, String message) {
        if (notifications == null) {
            LOG.warning(() -> "未接入 iOS 通知出口，通知未发出：" + title + "：" + message);
            return;
        }
        notifications.notify(title, message);
    }

    @Override
    public boolean hasEnoughSpace(long requiredBytes) {
        return PlatformSupport.hasEnoughSpace(tempDir, requiredBytes);
    }

    /** 跳转 App Store 商店页（提示用户手动升级二进制）。 */
    public void openAppStore() {
        if (appStoreUrl == null || appStoreUrl.isBlank()) {
            LOG.warning("未配置 App Store 链接，无法跳转商店");
            return;
        }
        if (storeOpener == null) {
            LOG.warning(() -> "未接入 iOS 跳转出口，请手动前往 App Store 升级：" + appStoreUrl);
            return;
        }
        storeOpener.open(appStoreUrl);
    }

    public String appStoreUrl() {
        return appStoreUrl;
    }
}