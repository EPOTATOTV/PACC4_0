package com.potatotv.paccclient.ops;

import com.potatotv.paccclient.ClientConfig;
import com.potatotv.pcu.DeltaApplier;
import com.potatotv.pcu.PcuConfig;
import com.potatotv.pcu.PcuException;
import com.potatotv.pcu.PlatformAdapter;
import com.potatotv.pcu.ProgressListener;
import com.potatotv.pcu.SemVer;
import com.potatotv.pcu.Sha256;
import com.potatotv.pcu.UpdateChannel;
import com.potatotv.pcu.UpdateChecker;
import com.potatotv.pcu.UpdateDownloader;
import com.potatotv.pcu.UpdateManifest;
import com.potatotv.pcu.UpdatePlatform;
import com.potatotv.pcu.UpdateVerifier;
import com.potatotv.pcu.platform.LinuxPlatformAdapter;
import com.potatotv.pcu.platform.MacOsPlatformAdapter;
import com.potatotv.pcu.platform.WindowsPlatformAdapter;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.PublicKey;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 玩家端的 PCU 接入（设计文档第四章，验收项 I03）。
 *
 * <p>这里只做五步流程的前四步里的前三步——检查更新、下载（优先差分）、校验，然后把
 * 通过校验的制品暂存到安装目录的 {@code pcu-staging/<版本>/}。第四步「应用」刻意不在这里做，
 * 原因是进程自己换不掉自己：Windows 上运行中的 JAR 被本进程锁定，Linux 上 systemd 的
 * restart 又会把当前进程一起杀掉，两条路都会把更新做成半个。暂停服务、备份、原子替换、
 * 重启、健康检查、回滚这套动作必须由安装目录之外的管理器进程
 * （{@code tools/windows-gui} 的 PaccManager、systemd 单元）拿着同一套 PCU 去执行。
 *
 * <p>因此这个类也不向服务端上报更新结果：没有真正应用就报 SUCCESS 会污染灰度成功率统计，
 * 上报由实际执行应用的那一方负责。</p>
 *
 * <p>失败一律只记日志：更新只是旁路，检测与上报链路不能因为拿不到更新清单而受影响。</p>
 */
public final class UpdateService {

    /** 暂存目录名，与设计文档 §4.6.3 的 {@code backup/} 并列。 */
    private static final String STAGING_DIR = "pcu-staging";

    /** 暂存制品文件名，与 {@code UpdateOrchestrator} 保持一致。 */
    private static final String ARTIFACT_NAME = "pacc-update.bin";

    private static final String TAG = "[PTV-Client] ";

    private static final int CHANGELOG_LIMIT = 120;

    private final ClientConfig cfg;
    private final String pteid;
    private final String appVersion;

    /** 配置无法构造（如当前平台不支持、安装目录不可用）时置位，避免每轮都重试同一件必然失败的事。 */
    private boolean disabled;

    /** 已经通知过的新版本号：每 6 小时复查一次，同一个版本不再反复弹通知。 */
    private String notifiedVersion;

    public UpdateService(ClientConfig cfg, String pteid, String appVersion) {
        this.cfg = cfg;
        this.pteid = pteid;
        this.appVersion = appVersion;
    }

    /**
     * 跑一轮：检查更新 → 下载（差分优先，失败回退全量）→ 校验 → 暂存。
     *
     * @return 是否暂存了一份通过校验的新版本制品
     */
    public boolean runOnce() {
        if (!cfg.updateEnabled || disabled) {
            return false;
        }
        PcuConfig pcu;
        try {
            pcu = buildConfig();
        } catch (Throwable e) {
            disabled = true;
            System.out.println(TAG + "更新功能不可用，已停用：" + describe(e));
            return false;
        }
        try {
            return stage(pcu);
        } catch (Throwable e) {
            // 必须兜到 Throwable：这个方法是 scheduleWithFixedDelay 的任务体，任何逃出去的
            // Throwable（包括 Error）都会让周期任务被永久取消，之后再也不复查更新。
            System.out.println(TAG + "本轮更新跳过（不影响检测）: " + describe(e));
            return false;
        }
    }

    private boolean stage(PcuConfig pcu) {
        UpdateChecker checker = new UpdateChecker(pcu);
        UpdateVerifier verifier = new UpdateVerifier(pcu);
        UpdateDownloader downloader = new UpdateDownloader(pcu);
        DeltaApplier deltaApplier = new DeltaApplier(pcu, downloader);

        UpdateManifest manifest = checker.check();
        if (!manifest.hasUpdate()) {
            System.out.println(TAG + "已是最新版本 " + appVersion);
            return false;
        }
        // 清单先过一道：平台不符、版本号倒退、最低版本要求不满足都在这里拦下，不下载任何东西
        verifier.verifyManifest(manifest);

        // 目录名用解析后的规范版本号，服务端返回什么原样字符串都不进路径
        SemVer target = SemVer.parse(manifest.latestVersion());
        if (!target.toString().equals(notifiedVersion)) {
            notifiedVersion = target.toString();
            pcu.adapter().showUpdateNotification("PACC 有新版本 " + target, changeSummary(manifest));
        }

        Path work = pcu.adapter().getInstallDir().resolve(STAGING_DIR).resolve(target.toString());
        Path artifact = work.resolve(ARTIFACT_NAME);
        createDirectories(work);

        if (alreadyStaged(artifact, manifest)) {
            System.out.println(TAG + "新版本 " + target + " 已暂存且摘要一致，跳过重复下载 路径=" + artifact);
            return true;
        }

        boolean deltaApplied = false;
        if (deltaApplier.usable(manifest)) {
            try {
                artifact = deltaApplier.produce(manifest, artifact, ProgressListener.NOOP);
                verifier.verifyArtifact(artifact, manifest);
                deltaApplied = true;
            } catch (RuntimeException e) {
                // 差分走不通不算更新失败，回退全量（设计文档 §4.5）
                System.out.println(TAG + "差分更新失败，回退全量：" + describe(e));
            }
        }
        if (!deltaApplied) {
            downloader.download(manifest.downloadUrl(), artifact, manifest.checksum(), manifest.size());
            verifier.verifyArtifact(artifact, manifest);
        }

        System.out.println(TAG + "新版本已就绪并暂存 " + appVersion + " → " + target
                + "（" + (deltaApplied ? "差分" : "全量") + "，SHA-256 与平台校验通过）"
                + " 路径=" + artifact);
        System.out.println(TAG + "应用（停服/备份/替换/重启）需由安装目录外的管理器进程执行，本次不自替换");
        return true;
    }

    private PcuConfig buildConfig() {
        UpdatePlatform platform = UpdatePlatform.current();
        Path selfJar = resolveSelfJar();
        Path installDir = selfJar != null ? selfJar.getParent() : Path.of(System.getProperty("user.dir"));
        return PcuConfig.builder()
                .baseUrl(cfg.updateBaseUri)
                .platform(platform)
                .currentVersion(appVersion)
                .channel(UpdateChannel.fromWire(cfg.updateChannel))
                .pteid(pteid)
                .adapter(createAdapter(platform, installDir))
                .signaturePublicKey(publicKey())
                // 单文件制品才谈得上差分：拿运行中的 JAR 当基线，恢复/替换的目标也是它
                .mainArtifact(selfJar)
                .allowDelta(selfJar != null)
                .backupRoot(installDir.resolve("backup"))
                .downloadHosts(downloadHosts())
                .build();
    }

    /**
     * 制品下载主机白名单；没配就返回 null，让 PCU 用它自己的默认值
     * （{@code pacc.potatotv.asia} + base.uri 的主机）。
     */
    private Set<String> downloadHosts() {
        String raw = cfg.updateDownloadHosts;
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Set<String> hosts = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                hosts.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        return hosts.isEmpty() ? null : hosts;
    }

    private PlatformAdapter createAdapter(UpdatePlatform platform, Path installDir) {
        Path temp = Path.of(System.getProperty("java.io.tmpdir"));
        return switch (platform) {
            case WINDOWS -> new WindowsPlatformAdapter(installDir, temp);
            case LINUX -> new LinuxPlatformAdapter(installDir, temp);
            case MACOS -> new MacOsPlatformAdapter(installDir, temp);
            default -> throw new PcuException("玩家端在当前平台不支持端侧自更新：" + platform);
        };
    }

    /** 运行中的发行 JAR；从 IDE 或 target/classes 启动时没有 JAR，此时不启用差分。 */
    private static Path resolveSelfJar() {
        try {
            CodeSource source = UpdateService.class.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }
            Path path = Path.of(source.getLocation().toURI());
            return Files.isRegularFile(path) ? path.toAbsolutePath().normalize() : null;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    private PublicKey publicKey() {
        String base64 = cfg.updatePublicKey;
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        return UpdateVerifier.decodePublicKey(base64.trim());
    }

    private static void createDirectories(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new PcuException("创建暂存目录失败：" + dir, e);
        }
    }

    /**
     * 同一个版本已经暂存过、摘要也还对得上，就不必每 6 小时重下一遍整包。
     * 读不动或算不出摘要时按「没暂存」处理，让正常下载链路去覆盖它。
     */
    private static boolean alreadyStaged(Path artifact, UpdateManifest manifest) {
        if (!Files.isRegularFile(artifact)) {
            return false;
        }
        try {
            return Sha256.matches(manifest.checksum(), Sha256.hexOfFile(artifact));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String changeSummary(UpdateManifest manifest) {
        String changelog = manifest.changelog();
        if (changelog == null || changelog.isBlank()) {
            return "有新版本可用，将在下次启动时应用。";
        }
        String flat = changelog.replaceAll("\\s+", " ").trim();
        return flat.length() <= CHANGELOG_LIMIT ? flat : flat.substring(0, CHANGELOG_LIMIT) + "…";
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}