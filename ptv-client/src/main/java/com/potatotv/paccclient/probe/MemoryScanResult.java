package com.potatotv.paccclient.probe;

import java.util.List;

/**
 * 用户态内存扫描结果（文档 §4.3）。
 *
 * @param signatureId 特征码标识
 * @param supported   本次扫描是否真正执行（探针不可用 / 平台不支持时为 false）
 * @param addresses   命中地址列表
 * @param note        补充说明（不可用时说明原因）
 */
public record MemoryScanResult(String signatureId, boolean supported, List<Long> addresses, String note) {

    public MemoryScanResult {
        addresses = addresses == null ? List.of() : List.copyOf(addresses);
    }

    /** 探针不可用 / 平台不支持。 */
    public static MemoryScanResult unsupported(String signatureId, String note) {
        return new MemoryScanResult(signatureId, false, List.of(), note);
    }

    /** 是否命中。 */
    public boolean hit() {
        return supported && !addresses.isEmpty();
    }
}