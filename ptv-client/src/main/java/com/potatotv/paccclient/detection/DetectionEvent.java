package com.potatotv.paccclient.detection;

/**
 * 检测事件（端侧）。仅采集本地全维度数据，绝不读取游戏服务器信息。
 *
 * @param eventType      检测类型前缀，如 memory_tamper / killaura / java_mod
 * @param severity       low / medium / high / critical
 * @param clientRiskScore 端侧预评分 0-100
 * @param processName    命中进程名（取证）
 * @param memoryRegion   命中内存区（底层检测）
 * @param signatureHit   命中特征码
 * @param osInfo         系统信息摘要（脱敏）
 * @param detailJson     v4.1 行为特征向量 JSON（128 维 feature_xxx），供 PTV AI 画像
 */
public record DetectionEvent(
        String eventType,
        String severity,
        int clientRiskScore,
        String processName,
        String memoryRegion,
        String signatureHit,
        String osInfo,
        String detailJson) {

    public DetectionEvent(String eventType, String severity, int clientRiskScore) {
        this(eventType, severity, clientRiskScore, null, null, null, null, null);
    }

    /** 兼容旧调用：无特征向量。 */
    public DetectionEvent(String eventType, String severity, int clientRiskScore,
                          String processName, String memoryRegion, String signatureHit, String osInfo) {
        this(eventType, severity, clientRiskScore, processName, memoryRegion, signatureHit, osInfo, null);
    }
}