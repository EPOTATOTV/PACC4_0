package com.potatotv.pcu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.logging.Logger;

/**
 * 「先写临时文件再 rename」的落盘工具。
 *
 * <p>更新替换、差分产物落地、备份恢复三处都要同一个保证：中途失败时目标文件要么是旧的、
 * 要么是新的，不能是半截。所以先写到同目录的临时文件，再尽量原子地替换过去——
 * 同目录是为了不让 rename 跨分区。</p>
 */
final class AtomicReplace {

    /** 临时文件后缀：不会与业务文件重名，出错时也一眼看得出是谁留下的。 */
    static final String TEMP_SUFFIX = ".pcu-new";

    private static final Logger LOG = Logger.getLogger(AtomicReplace.class.getName());

    private AtomicReplace() {
    }

    /** 目标文件的同目录临时路径。 */
    static Path tempSibling(Path target) {
        return target.resolveSibling(target.getFileName() + TEMP_SUFFIX);
    }

    /** 把 source 移到 target，尽量走原子替换。 */
    static void move(Path source, Path target) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | UnsupportedOperationException e) {
            // 少数文件系统不支持 ATOMIC_MOVE，退化为普通替换（同目录，风险窗口极小）
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                throw new PcuException("文件落地失败：" + target, e2);
            }
        }
    }

    /** 写入字节内容：临时文件 + 原子替换。 */
    static void write(Path target, byte[] content) {
        Path temp = tempSibling(target);
        try {
            Files.createDirectories(target.toAbsolutePath().normalize().getParent());
            Files.write(temp, content, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new PcuException("写入临时文件失败：" + temp, e);
        }
        move(temp, target);
    }

    /** 删除文件或整个目录树，失败只记日志。 */
    static void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            if (Files.isDirectory(path)) {
                try (var walk = Files.walk(path)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            LOG.warning(() -> "删除失败：" + p);
                        }
                    });
                }
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOG.warning(() -> "删除失败：" + path + "：" + e.getMessage());
        }
    }
}