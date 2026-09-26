package com.potatotv.pcu;

import com.potatotv.pcu.platform.PackageInstallerAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 应用更新（设计文档 §4.4 第 4 步）：备份 → 停服务 → 原子替换 → 重启。
 *
 * <p>两个制品形态：</p>
 * <ul>
 *   <li>配了 {@link PcuConfig#mainArtifact()}（非 zip 的单文件制品，如客户端 JAR）：
 *       制品整体替换那一个文件，这也是差分更新的落点；</li>
 *   <li>没配（zip 制品）：按条目覆盖安装目录下的同名相对路径，条目名会被校验不能越出
 *       安装目录（Zip Slip）。</li>
 * </ul>
 *
 * <p>应用阶段自己兜底：替换过程中任何一步失败，都会用刚建的备份把文件写回并重启旧版本。
 * 这样交给上层的失败一定是「旧版本还在跑」的状态，回滚逻辑不必再猜安装目录是不是半截。
 * 覆盖安装目录后新版本起不来（健康检查失败）属于另一个阶段，由
 * {@link UpdateRollbacker} 处理。</p>
 */
public final class UpdateApplier {

    private static final Logger LOG = Logger.getLogger(UpdateApplier.class.getName());

    private final PcuConfig config;
    private final BackupManager backups;

    public UpdateApplier(PcuConfig config, BackupManager backups) {
        this.config = config;
        this.backups = backups;
    }

    /** 一次应用的产物：备份句柄、被替换的文件、是否交给了系统安装器。 */
    public record ApplyOutcome(BackupManager.BackupHandle backup, List<Path> targets,
                               boolean delegatedToInstaller) {

        public ApplyOutcome {
            targets = List.copyOf(targets);
        }

        /** 没有可回滚的本地备份（制品由系统安装器接管，或本来就没有旧文件）。 */
        public boolean rollbackable() {
            return backup != null && !backup.isEmpty();
        }
    }

    /** 应用已校验过的制品。 */
    public ApplyOutcome apply(Path artifact, String version) {
        if (config.adapter() instanceof PackageInstallerAdapter installer) {
            // 移动端：整包交给系统安装器，PCU 不碰文件，也没有可回滚的本地备份
            installer.installPackage(artifact);
            LOG.info("制品已交给系统包安装器");
            return new ApplyOutcome(null, List.of(), true);
        }

        List<Path> targets = targetsFor(artifact);
        BackupManager.BackupHandle handle = backups.create(version, targets);
        try {
            config.adapter().stopPacc();
            install(artifact, targets);
            config.adapter().startPacc();
            return new ApplyOutcome(handle, targets, false);
        } catch (RuntimeException e) {
            String note = restoreAndRestart(handle);
            throw new PcuException("应用更新失败（" + note + "）：" + e.getMessage(), e);
        }
    }

    /** 本次更新会覆盖哪些文件：单文件制品就一个，zip 制品是各条目的落地路径。 */
    private List<Path> targetsFor(Path artifact) {
        Path main = config.mainArtifact();
        if (main != null) {
            return List.of(main);
        }
        Path installDir = installDir();
        List<Path> targets = new ArrayList<>();
        for (String name : zipEntryNames(artifact)) {
            targets.add(resolveUnder(installDir, name));
        }
        if (targets.isEmpty()) {
            throw new PcuException("更新包里没有任何文件：" + artifact.getFileName());
        }
        return targets;
    }

    private void install(Path artifact, List<Path> targets) {
        Path main = config.mainArtifact();
        if (main != null) {
            AtomicReplace.write(main, readAll(artifact));
            LOG.info(() -> "已替换主制品：" + main);
            return;
        }
        Path installDir = installDir();
        int written = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(artifact))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                Path target = resolveUnder(installDir, entry.getName());
                Path temp = AtomicReplace.tempSibling(target);
                try {
                    Files.createDirectories(target.toAbsolutePath().normalize().getParent());
                    Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    AtomicReplace.deleteQuietly(temp);
                    throw new PcuException("释放更新包条目失败：" + entry.getName(), e);
                }
                AtomicReplace.move(temp, target);
                written++;
            }
        } catch (IOException e) {
            throw new PcuException("读取更新包失败：" + artifact.getFileName(), e);
        }
        if (written != targets.size()) {
            // 同一个文件读了两遍却对不上，说明中间被改过
            throw new PcuException("更新包条目数与预期不符：预期 " + targets.size() + "，实际 " + written);
        }
        int count = written;
        LOG.info(() -> "已用更新包覆盖 " + count + " 个文件");
    }

    /** 用备份把文件写回并重新拉起旧版本，返回一句可供上报的说明。 */
    private String restoreAndRestart(BackupManager.BackupHandle handle) {
        StringBuilder note = new StringBuilder();
        try {
            backups.restore(handle);
            note.append("已恢复旧版本文件");
        } catch (RuntimeException restoreFailure) {
            LOG.log(Level.SEVERE, "恢复备份失败，安装目录可能处于不一致状态", restoreFailure);
            note.append("恢复备份同样失败，需人工介入：").append(restoreFailure.getMessage());
        }
        try {
            config.adapter().startPacc();
            note.append("，并已重启");
        } catch (RuntimeException startFailure) {
            LOG.log(Level.SEVERE, "重启旧版本失败", startFailure);
            note.append("，重启旧版本失败：").append(startFailure.getMessage());
        }
        return note.toString();
    }

    private Path installDir() {
        return config.adapter().getInstallDir().toAbsolutePath().normalize();
    }

    /** 只读条目名（第一遍），用来在任何破坏性动作之前算出影响面并备份。 */
    private static List<String> zipEntryNames(Path artifact) {
        requireZip(artifact);
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(artifact))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    names.add(entry.getName());
                }
            }
        } catch (IOException e) {
            throw new PcuException("读取更新包失败：" + artifact.getFileName(), e);
        }
        return names;
    }

    private static void requireZip(Path artifact) {
        try (InputStream in = Files.newInputStream(artifact)) {
            byte[] magic = new byte[4];
            int n = in.readNBytes(magic, 0, 4);
            // zip 的四个本地文件头签名：普通条目、空归档、跨卷、数据描述符
            boolean zip = n == 4 && magic[0] == 'P' && magic[1] == 'K'
                    && (magic[2] == 3 || magic[2] == 5 || magic[2] == 7);
            if (!zip) {
                throw new PcuException("未配置主制品文件时，制品必须是 zip 包："
                        + artifact.getFileName());
            }
        } catch (IOException e) {
            throw new PcuException("读取更新包失败：" + artifact, e);
        }
    }

    /** 把条目名解析到安装目录内；越界一律拒绝（Zip Slip）。 */
    static Path resolveUnder(Path installDir, String entryName) {
        if (entryName == null || entryName.isBlank()) {
            throw new PcuException("更新包条目名为空");
        }
        Path resolved = installDir.resolve(entryName).normalize();
        if (!resolved.startsWith(installDir)) {
            throw new PcuException("更新包条目越出安装目录（Zip Slip）：" + entryName);
        }
        if (resolved.equals(installDir)) {
            throw new PcuException("更新包条目指向安装目录本身：" + entryName);
        }
        return resolved;
    }

    private static byte[] readAll(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new PcuException("读取文件失败：" + path, e);
        }
    }
}