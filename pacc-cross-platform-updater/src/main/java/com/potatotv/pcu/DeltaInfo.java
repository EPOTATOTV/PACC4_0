package com.potatotv.pcu;

/**
 * 差分包描述（设计文档 §4.11 的 {@code delta} 字段）。
 *
 * @param fromVersion 该差分包适用的起始版本，端侧只有当前版本与它一致才能用
 * @param url         差分包地址
 * @param checksum    差分包校验和，形如 {@code sha256:...}
 * @param size        差分包字节数
 */
public record DeltaInfo(String fromVersion, String url, String checksum, long size) {

    public DeltaInfo {
        if (url == null || url.isBlank()) {
            throw new PcuException("差分包缺少下载地址");
        }
    }
}