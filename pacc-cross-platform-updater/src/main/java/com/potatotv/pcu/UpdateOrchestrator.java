package com.potatotv.pcu;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * 更新编排（设计文档 §4.4 的五步流程）：
 * 检查更新 → 下载 → 校验 → 应用 → 验证启动（失败则回滚）。
 *
 * <p>各环节都收在同一条链路里，是为了让「什么时候上报、上报什么状态」只有一个判断点：
 * 服务端靠这些状态统计成功率，并在回滚时暂停对应版本的灰度。</p>
 *
 * <p>几个容易含糊的地方在这里定死：</p>
 * <ul>
 *   <li>检查更新本身失败不产生上报——没有目标版本，报 FAILED 只会污染成功率统计，
 *       下次重试即可（设计文档 §4.10）。</li>
 *   <li>差分失败不算更新失败，自动回退全量（设计文档 §4.5）。</li>
 *   <li>静默更新失败不弹通知（设计文档 §4.10），但结果照常上报。</li>
 * </ul>
 */
public final class UpdateOrchestrator {

    private static final Logger LOG = Logger.getLogger(UpdateOrchestrator.class.getName());

    /** 下载到临时目录后的制品文件名。 */
    static final String ARTIFACT_NAME = "pacc-update.bin";

    private static final String WORK_DIR_PREFIX = "pcu-";

    /** 通知正文长度上限，够看一行就行。 */
    private static final int CHANGELOG_LIMIT = 120;

    private final PcuConfig config;
    private final UpdateChecker checker;
    private final UpdateDownloader downloader;
    private final UpdateVerifier verifier;
    private final DeltaApplier deltaApplier;
    private final BackupManager backups;
    private final UpdateApplier applier;
    private final UpdateRollbacker rollbacker;
    private final UpdateReporter reporter;

    public UpdateOrchestrator(PcuConfig config) {
        this(config, HttpClients.create(config), HttpClients.createForDownload(config));
    }

    /**
     * @param controlHttp  检查与上报用的客户端
     * @param downloadHttp 下载用的客户端（强制 HTTP/1.1，见 {@link HttpClients#createForDownload}）
     */
    public UpdateOrchestrator(PcuConfig config, HttpClient controlHttp, HttpClient downloadHttp) {
        this.config = config;
        this.checker = new UpdateChecker(config, controlHttp);
        this.downloader = new UpdateDownloader(config, downloadHttp);
        this.verifier = new UpdateVerifier(config);
        this.deltaApplier = new DeltaApplier(config, downloader);
        this.backups = new BackupManager(config);
        this.applier = new UpdateApplier(config, backups);
        this.rollbacker = new UpdateRollbacker(config, backups);
        this.reporter = new UpdateReporter(config, controlHttp);
    }

    /** 只检查更新，不做任何下载或安装。 */
    public UpdateManifest check() {
        return checker.check();
    }

    /** 检查更新并走完整个流程。 */
    public UpdateResult runOnce() {
        return runOnce(ProgressListener.NOOP);
    }

    public UpdateResult runOnce(ProgressListener listener) {
        String from = config.currentVersion();
        UpdateManifest manifest;
        try {
            manifest = checker.check();
        } catch (RuntimeException e) {
            LOG.warning(() -> "检查更新失败，本次跳过：" + e.getMessage());
            return UpdateResult.skipped(from, null, false, describe("检查更新失败", e));
        }
        if (!manifest.hasUpdate()) {
            return UpdateResult.noUpdate(from);
        }

        String to = manifest.latestVersion();
        try {
            verifier.verifyManifest(manifest);
        } catch (RuntimeException e) {
            return fail(from, to, false, false, null, describe("清单校验失败", e));
        }
        boolean force = verifier.forceRequired(manifest);

        boolean deltaUsable = deltaApplier.usable(manifest);
        long required = requiredSpace(manifest, deltaUsable);
        if (!config.adapter().hasEnoughSpace(required)) {
            return fail(from, to, false, force, null, "存储空间不足：需要约 " + required + " 字节");
        }
        if (!config.silentUpdate()) {
            config.adapter().showUpdateNotification("PACC 有新版本 " + to, changeSummary(manifest, force));
        }

        Path work = workDir(to);
        Path artifact;
        boolean deltaApplied = false;
        try {
            if (deltaUsable) {
                try {
                    artifact = deltaApplier.produce(manifest, work.resolve(ARTIFACT_NAME), listener);
                    verifier.verifyArtifact(artifact, manifest);
                    deltaApplied = true;
                } catch (RuntimeException e) {
                    LOG.warning(() -> "差分更新失败，回退全量：" + e.getMessage());
                    artifact = downloadFull(manifest, work, listener);
                }
            } else {
                artifact = downloadFull(manifest, work, listener);
            }
        } catch (RuntimeException e) {
            return fail(from, to, deltaApplied, force, null, describe("下载或校验失败", e));
        }

        UpdateApplier.ApplyOutcome outcome;
        try {
            outcome = applier.apply(artifact, to);
        } catch (RuntimeException e) {
            return fail(from, to, deltaApplied, force, artifact, describe("应用更新失败", e));
        }

        if (outcome.delegatedToInstaller()) {
            // 整包已交给系统安装器，后续替换与重启由系统决定，端侧到此为止
            reporter.reportQuietly(UpdateStatus.SUCCESS, from, to, null);
            return UpdateResult.success(from, to, deltaApplied, force, artifact);
        }

        if (!awaitHealthy()) {
            return rollback(from, to, deltaApplied, force, artifact, outcome);
        }

        // 健康检查通过才按保留策略清备份（设计文档 §4.6.3 保留最近 2 个版本）
        backups.prune();
        reporter.reportQuietly(UpdateStatus.SUCCESS, from, to, null);
        return UpdateResult.success(from, to, deltaApplied, force, artifact);
    }

    /** 全量下载 + 校验。 */
    private Path downloadFull(UpdateManifest manifest, Path work, ProgressListener listener) {
        Path target = work.resolve(ARTIFACT_NAME);
        downloader.download(manifest.downloadUrl(), target, manifest.checksum(), manifest.size(), listener);
        verifier.verifyArtifact(target, manifest);
        return target;
    }

    /**
     * 在健康检查窗口内轮询探针（设计文档 §4.4 第 5 步，默认 30 秒）。
     * 窗口内一次都不健康即判定新版本不可用。
     */
    private boolean awaitHealthy() {
        long deadline = System.nanoTime() + config.healthCheckTimeout().toNanos();
        while (true) {
            if (config.healthProbe().healthy()) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(config.healthCheckInterval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warning("健康检查等待被中断，按不健康处理");
                return false;
            }
        }
    }

    /** 健康检查没过：回滚并上报回滚事件。 */
    private UpdateResult rollback(String from, String to, boolean deltaApplied, boolean force,
                                  Path artifact, UpdateApplier.ApplyOutcome outcome) {
        String reason = "新版本启动后未通过健康检查（窗口 "
                + config.healthCheckTimeout().toSeconds() + " 秒）";
        if (!outcome.rollbackable()) {
            String message = reason + "，且没有可用备份";
            reporter.reportQuietly(UpdateStatus.FAILED, from, to, message);
            return UpdateResult.failed(from, to, deltaApplied, force, artifact, message);
        }
        try {
            rollbacker.rollback(outcome.backup());
        } catch (RuntimeException e) {
            String message = reason + "；回滚失败：" + e.getMessage();
            reporter.reportQuietly(UpdateStatus.FAILED, from, to, message);
            return UpdateResult.failed(from, to, deltaApplied, force, artifact, message);
        }
        reporter.reportQuietly(UpdateStatus.ROLLED_BACK, from, to, reason);
        return UpdateResult.rolledBack(from, to, deltaApplied, force, artifact, reason);
    }

    /** 失败收口：静默更新不弹通知，但都要上报，服务端需要看到失败率。 */
    private UpdateResult fail(String from, String to, boolean deltaApplied, boolean force,
                              Path artifact, String message) {
        if (!config.silentUpdate()) {
            config.adapter().showUpdateNotification("PACC 更新失败", message);
        }
        reporter.reportQuietly(UpdateStatus.FAILED, from, to, message);
        return UpdateResult.failed(from, to, deltaApplied, force, artifact, message);
    }

    /**
     * 粗略估算这次更新要占多少空间：制品一份、替换前的备份一份（zip 解开后可能比原包大，
     * 所以不按压缩包大小抠），差分时再加一份补丁。
     */
    private static long requiredSpace(UpdateManifest manifest, boolean deltaUsable) {
        long required = Math.max(0L, manifest.size()) * 2;
        if (deltaUsable && manifest.delta() != null) {
            required += Math.max(0L, manifest.delta().size());
        }
        return required;
    }

    /** 临时工作目录；版本号来自服务端，落成目录名同样要收紧。 */
    private Path workDir(String version) {
        Path dir = config.adapter().getTempDir().toAbsolutePath().normalize()
                .resolve(WORK_DIR_PREFIX + BackupManager.sanitize(version));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new PcuException("创建临时工作目录失败：" + dir, e);
        }
        return dir;
    }

    private static String changeSummary(UpdateManifest manifest, boolean force) {
        StringBuilder sb = new StringBuilder();
        if (force) {
            sb.append("此版本为强制更新。");
        }
        String changelog = manifest.changelog();
        if (changelog == null || changelog.isBlank()) {
            return sb.append("有新版本可用。").toString();
        }
        String flat = changelog.replaceAll("\\s+", " ").trim();
        return sb.append(flat.length() <= CHANGELOG_LIMIT ? flat
                : flat.substring(0, CHANGELOG_LIMIT) + "…").toString();
    }

    private static String describe(String prefix, RuntimeException e) {
        String message = e.getMessage();
        return prefix + "：" + (message == null || message.isBlank()
                ? e.getClass().getSimpleName() : message);
    }
}