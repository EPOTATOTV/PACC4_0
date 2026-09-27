package com.potatotv.pcu.platform;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/** 平台适配层内部共用的小工具。 */
final class PlatformSupport {

    private static final Logger LOG = Logger.getLogger(PlatformSupport.class.getName());

    private PlatformSupport() {
    }

    /**
     * 目标分区可用空间是否够。
     *
     * <p>探测不到（目录建不出来、文件系统不上报容量）时返回 true 并告警：宁可让真正写盘的
     * 那一步报错，也不要因为拿不到容量数字就把整条更新链路卡死。</p>
     */
    static boolean hasEnoughSpace(Path dir, long requiredBytes) {
        if (requiredBytes <= 0) {
            return true;
        }
        try {
            Files.createDirectories(dir);
            FileStore store = Files.getFileStore(dir);
            long usable = store.getUsableSpace();
            if (usable >= requiredBytes) {
                return true;
            }
            LOG.warning(() -> "磁盘空间不足：需要 " + requiredBytes + " 字节，可用 " + usable + " 字节（" + dir + "）");
            return false;
        } catch (IOException e) {
            LOG.warning(() -> "无法探测可用空间（" + dir + "）：" + e.getMessage());
            return true;
        }
    }
}