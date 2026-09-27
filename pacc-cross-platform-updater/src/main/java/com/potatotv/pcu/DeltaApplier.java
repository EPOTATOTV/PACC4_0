package com.potatotv.pcu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * 差分更新（设计文档 §4.5）：服务端用 bsdiff 生成 BSDIFF40 补丁（内层 bzip2），
 * 端侧把补丁打到当前版本制品上，得到新版本的完整制品。
 *
 * <p>补丁只对「单文件制品」有意义，所以 {@link PcuConfig#mainArtifact()} 为 null
 * （zip 按条目覆盖安装）时不启用差分。</p>
 *
 * <p>打完补丁的产物不会被单独信任：调用方必须再拿它过一遍
 * {@link UpdateVerifier#verifyArtifact}，那个 SHA-256 才是最终判据——补丁本身被换了、
 * 基线版本不对、算法实现有偏差，都会在那一刻暴露。</p>
 */
public final class DeltaApplier {

    private static final Logger LOG = Logger.getLogger(DeltaApplier.class.getName());

    /** 补丁文件后缀。 */
    static final String PATCH_SUFFIX = ".pcu-patch";

    private final PcuConfig config;
    private final UpdateDownloader downloader;

    public DeltaApplier(PcuConfig config, UpdateDownloader downloader) {
        this.config = config;
        this.downloader = downloader;
    }

    /**
     * 这份差分包能不能用：开关打开、服务端给了、制品是单文件、补丁的来源版本
     * 必须正好等于当前版本。
     *
     * <p>「来源版本正好等于当前版本」是硬条件：跨版本的补丁需要链式回放，端侧不做这件事，
     * 版本对不上就直接回退全量（设计文档 §4.5 的差分策略）。</p>
     */
    public boolean usable(UpdateManifest manifest) {
        if (!config.allowDelta() || manifest.delta() == null) {
            return false;
        }
        Path baseline = config.mainArtifact();
        if (baseline == null || !Files.isRegularFile(baseline)) {
            return false;
        }
        SemVer from = SemVer.tryParse(manifest.delta().fromVersion());
        if (from == null) {
            LOG.warning(() -> "差分包未给出可解析的 from_version，回退全量："
                    + manifest.delta().fromVersion());
            return false;
        }
        if (from.compareTo(config.currentSemVer()) != 0) {
            LOG.info(() -> "差分包来源版本 " + from + " 与当前版本 " + config.currentVersion()
                    + " 不一致，回退全量");
            return false;
        }
        return true;
    }

    /**
     * 下载补丁、打补丁、落地产出新制品，返回新制品文件。
     *
     * @param output 新制品的落地路径
     */
    public Path produce(UpdateManifest manifest, Path output, ProgressListener listener) {
        DeltaInfo delta = manifest.delta();
        if (delta == null) {
            throw new PcuException("清单里没有差分包信息");
        }
        Path baseline = config.mainArtifact();
        if (baseline == null) {
            throw new PcuException("未配置主制品文件，无法应用差分补丁");
        }

        Path patchFile = output.resolveSibling(output.getFileName() + PATCH_SUFFIX);
        downloader.download(delta.url(), patchFile, delta.checksum(), delta.size(), listener);

        byte[] oldData = readAll(baseline);
        byte[] patchData = readAll(patchFile);
        byte[] newData;
        try {
            newData = BsPatch.patch(oldData, patchData);
        } catch (RuntimeException e) {
            throw new PcuException("应用差分补丁失败：" + e.getMessage(), e);
        }

        // 补丁的产物先自校验一道：基线版本不对或补丁损坏在这里就能发现，
        // 不用等到调用方去做全量校验时才知道「差分这条路走不通」
        if (!Sha256.matches(manifest.checksum(), Sha256.hex(newData))) {
            throw new PcuException("差分产物 SHA-256 与清单不符，补丁或基线版本不正确");
        }
        AtomicReplace.write(output, newData);
        AtomicReplace.deleteQuietly(patchFile);
        LOG.info(() -> "差分更新完成：" + baseline.getFileName() + " + 补丁 → "
                + newData.length + " 字节");
        return output;
    }

    private static byte[] readAll(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new PcuException("读取文件失败：" + path, e);
        }
    }
}