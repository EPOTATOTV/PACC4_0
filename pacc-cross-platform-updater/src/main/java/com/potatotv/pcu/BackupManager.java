package com.potatotv.pcu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 更新前备份与回滚恢复（设计文档 §4.6.3）。
 *
 * <p>备份落点 {@code {backupRoot}/{version}/}，只收「被本次更新覆盖的核心文件」——
 * 更新本来就不碰日志与检测数据，所以不需要靠排除规则去挡它们。</p>
 *
 * <p>备份目录里的内容按序号存放，另有 {@code pcu-backup.manifest.json} 记录
 * 「序号 → 原始绝对路径」，恢复时据此写回原位。这样安装目录可以搬家，
 * 备份也不必跟着复制一份目录结构。</p>
 */
public final class BackupManager {

    private static final String MANIFEST_NAME = "pcu-backup.manifest.json";

    private static final Logger LOG = Logger.getLogger(BackupManager.class.getName());

    private final PcuConfig config;

    public BackupManager(PcuConfig config) {
        this.config = config;
    }

    /**
     * 备份一组文件，返回可用于恢复的句柄。
     *
     * <p>不存在的文件会被跳过（首次安装时本来就没有旧文件可备），但会记进清单的
     * {@code added} 列表——这些是本次更新新增的文件，回滚时要删掉。</p>
     */
    public BackupHandle create(String version, List<Path> sources) {
        Path dir = backupDir(version);
        List<Path> backedUp = new ArrayList<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        List<Path> added = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            int index = 0;
            for (Path source : sources) {
                Path abs = source.toAbsolutePath().normalize();
                if (!Files.isRegularFile(abs)) {
                    // 现在没有、本次更新会创建的文件：单独记一份，回滚时要把它们删掉。
                    // 光靠备份写回是不够的——新版本新增的文件没有任何旧版本内容可覆盖，
                    // 不删就会留在安装目录里被旧版本读到。
                    added.add(abs);
                    LOG.fine(() -> "备份跳过（文件不存在，记为新版本新增）：" + abs);
                    continue;
                }
                Path stored = dir.resolve(storedName(index));
                Files.copy(abs, stored, StandardCopyOption.REPLACE_EXISTING);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("index", index);
                entry.put("source", abs.toString());
                entry.put("sha256", Sha256.hexOfFile(stored));
                entry.put("size", Files.size(stored));
                entries.add(entry);
                backedUp.add(abs);
                index++;
            }
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("version", version);
            manifest.put("created_at", Instant.now().toString());
            manifest.put("entries", entries);
            manifest.put("added", added.stream().map(Path::toString).toList());
            Files.writeString(dir.resolve(MANIFEST_NAME), PcuJson.write(manifest), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 备份不完整就不能留在那里冒充「可回滚」，清掉再报错
            AtomicReplace.deleteQuietly(dir);
            throw new PcuException("备份当前版本失败：" + dir, e);
        }
        LOG.info(() -> "已备份 " + backedUp.size() + " 个文件到 " + dir);
        return new BackupHandle(version, dir, backedUp, added);
    }

    /**
     * 按备份清单把文件写回原路径（先写临时文件再原子替换），并清掉本次更新新增的文件。
     *
     * <p>写回前逐个核对清单里的 SHA-256：备份被动过就整体失败，不写回任何东西。</p>
     */
    public void restore(BackupHandle handle) {
        Map<String, Object> manifest = readManifest(handle.dir());
        Object raw = manifest.get("entries");
        if (!(raw instanceof List<?> entries)) {
            throw new PcuException("备份清单缺少 entries：" + handle.dir());
        }
        // 先把所有条目验完、排好，再动安装目录：多文件备份里只要有一块被动过，
        // 就在还没有写回任何文件时失败，不会留下「一半旧版本一半新版本」的安装目录。
        List<PendingRestore> plan = new ArrayList<>();
        for (Object item : entries) {
            if (!(item instanceof Map<?, ?> entry)) {
                throw new PcuException("备份清单条目非法：" + handle.dir());
            }
            long index = PcuJson.num(entry, "index", -1L);
            String source = PcuJson.str(entry, "source");
            if (index < 0 || source == null) {
                throw new PcuException("备份清单条目缺少 index/source：" + handle.dir());
            }
            Path stored = handle.dir().resolve(storedName(index));
            if (!Files.isRegularFile(stored)) {
                throw new PcuException("备份内容缺失：" + stored);
            }
            // 回滚是最后一道保险，写回去的字节必须是当初备份下来的字节：备份落盘后被改过、
            // 被别的清理工具截断，或者清单跟内容对不上，都在这里拦下，绝不能把坏文件写回安装目录。
            String expectedSha = PcuJson.str(entry, "sha256");
            if (expectedSha == null || expectedSha.isBlank()) {
                throw new PcuException("备份清单条目缺少 sha256，拒绝恢复：" + handle.dir());
            }
            if (!Sha256.matches(expectedSha, Sha256.hexOfFile(stored))) {
                throw new PcuException("备份内容 SHA-256 与清单不符，拒绝恢复：" + stored);
            }
            plan.add(new PendingRestore(stored, Path.of(source)));
        }

        int restored = 0;
        for (PendingRestore pending : plan) {
            Path target = pending.target();
            Path temp = AtomicReplace.tempSibling(target);
            try {
                Files.createDirectories(target.toAbsolutePath().normalize().getParent());
                Files.copy(pending.stored(), temp, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                AtomicReplace.deleteQuietly(temp);
                throw new PcuException("写回备份文件失败：" + target, e);
            }
            AtomicReplace.move(temp, target);
            restored++;
        }
        int restoredCount = restored;
        LOG.info(() -> "已从 " + handle.dir() + " 恢复 " + restoredCount + " 个文件");
        removeAdded(handle.added(), handle.dir());
    }

    /** 校验通过、等待写回的一对「备份块 → 原始路径」。 */
    private record PendingRestore(Path stored, Path target) {
    }

    /** 删掉本次更新新增的文件；旧版本没有这些文件，留着会让旧版本读到不匹配的内容。 */
    private static void removeAdded(List<Path> added, Path dir) {
        if (added.isEmpty()) {
            return;
        }
        int removed = 0;
        for (Path path : added) {
            // 只删文件：路径上可能已经有同名目录（新版本自己建的），那不是「新增的文件」
            if (Files.isRegularFile(path)) {
                AtomicReplace.deleteQuietly(path);
                removed++;
                LOG.info(() -> "回滚删除新版本新增的文件：" + path);
            }
        }
        int removedCount = removed;
        LOG.info(() -> "回滚已从 " + dir + " 删除 " + removedCount + " 个新增文件");
    }

    /** 删除某个版本的备份目录。 */
    public void delete(String version) {
        AtomicReplace.deleteQuietly(backupDir(version));
    }

    /**
     * 只保留最近 {@code keepBackups} 个版本，返回删除的数量（设计文档 §4.6.3）。
     *
     * <p>更新成功后才调用。排序优先按版本号，版本号解析不出来时退回按修改时间。</p>
     *
     * <p>只清理「带清单的目录」：备份根目录默认就在安装目录里，谁往这儿放了个别的子目录
     * （日志、下载缓存、用户自己建的），都不能被这里的递归删除顺手带走。</p>
     */
    public int prune() {
        Path root = config.backupRoot().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return 0;
        }
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root)) {
            entries.filter(Files::isDirectory).filter(BackupManager::hasManifest).forEach(dirs::add);
        } catch (IOException e) {
            LOG.warning(() -> "读取备份目录失败，跳过清理：" + e.getMessage());
            return 0;
        }
        if (dirs.size() <= config.keepBackups()) {
            return 0;
        }
        dirs.sort(RECENCY.reversed());
        int removed = 0;
        for (int i = config.keepBackups(); i < dirs.size(); i++) {
            Path stale = dirs.get(i);
            AtomicReplace.deleteQuietly(stale);
            removed++;
            LOG.info(() -> "清理过期备份：" + stale);
        }
        int removedCount = removed;
        LOG.info(() -> "备份保留最近 " + config.keepBackups() + " 个版本，本次清理 " + removedCount + " 个");
        return removed;
    }

    /** 某版本的备份目录是否存在且带清单。 */
    public boolean hasBackup(String version) {
        return hasManifest(backupDir(version));
    }

    /** 打开已有备份，供「进程重启后继续回滚」这类场景使用。 */
    public BackupHandle open(String version) {
        Path dir = backupDir(version);
        if (!Files.isRegularFile(dir.resolve(MANIFEST_NAME))) {
            throw new PcuException("备份不存在：" + dir);
        }
        Map<String, Object> manifest = readManifest(dir);
        List<Path> sources = new ArrayList<>();
        if (manifest.get("entries") instanceof List<?> entries) {
            for (Object item : entries) {
                if (item instanceof Map<?, ?> entry) {
                    String source = PcuJson.str(entry, "source");
                    if (source != null) {
                        sources.add(Path.of(source));
                    }
                }
            }
        }
        List<Path> added = new ArrayList<>();
        if (manifest.get("added") instanceof List<?> addedRaw) {
            for (Object item : addedRaw) {
                if (item instanceof String raw && !raw.isBlank()) {
                    added.add(Path.of(raw));
                }
            }
        }
        return new BackupHandle(version, dir, sources, added);
    }

    /** 一次备份的句柄。 */
    public record BackupHandle(String version, Path dir, List<Path> sources, List<Path> added) {

        public BackupHandle {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(dir, "dir");
            sources = List.copyOf(sources);
            added = List.copyOf(added);
        }

        /** 没有任何可回滚的内容：既没有旧文件可写回，也没有新版本新增的文件要删。 */
        public boolean isEmpty() {
            return sources.isEmpty() && added.isEmpty();
        }
    }

    private Path backupDir(String version) {
        return config.backupRoot().toAbsolutePath().normalize().resolve(sanitize(version));
    }

    /**
     * 版本号要落成目录名，而它来自服务端，必须先收紧：只放行字母数字与 {@code . _ -}，
     * 且不允许 {@code .} / {@code ..}。宁可因为格式怪异失败，也不要让
     * {@code ../../} 这类版本号把文件写到备份目录外面去。
     */
    static String sanitize(String version) {
        if (version == null || version.isBlank()) {
            throw new PcuException("备份版本号为空");
        }
        String trimmed = version.trim();
        if (".".equals(trimmed) || "..".equals(trimmed)) {
            throw new PcuException("非法的备份版本号：" + version);
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!allowed) {
                throw new PcuException("备份版本号含非法字符：" + version);
            }
        }
        return trimmed;
    }

    private static String storedName(long index) {
        return String.format(Locale.ROOT, "%04d.bin", index);
    }

    private static boolean hasManifest(Path dir) {
        return Files.isRegularFile(dir.resolve(MANIFEST_NAME));
    }

    private static Map<String, Object> readManifest(Path dir) {
        Path file = dir.resolve(MANIFEST_NAME);
        if (!Files.isRegularFile(file)) {
            throw new PcuException("备份清单不存在：" + file);
        }
        Object parsed;
        try {
            parsed = PcuJson.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PcuException("读取备份清单失败：" + file, e);
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new PcuException("备份清单不是 JSON 对象：" + file);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    /** 备份新旧排序：优先按版本号，解析不出来时按修改时间。 */
    private static final Comparator<Path> RECENCY = (a, b) -> {
        SemVer va = SemVer.tryParse(a.getFileName().toString());
        SemVer vb = SemVer.tryParse(b.getFileName().toString());
        if (va != null && vb != null) {
            return va.compareTo(vb);
        }
        return Long.compare(lastModified(a), lastModified(b));
    };

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}