package com.potatotv.pcu;

/**
 * 更新结果状态，对应 {@code POST /v1/update/report} 的 {@code status} 字段。
 */
public enum UpdateStatus {

    /** 更新成功（新版本已启动并通过健康检查）。 */
    SUCCESS("success"),
    /** 更新失败但旧版本仍可用。 */
    FAILED("failed"),
    /** 新版本启动异常，已自动回滚到旧版本。 */
    ROLLED_BACK("rolled_back"),
    /** 用户跳过（仅非强制更新会出现）。 */
    SKIPPED("skipped");

    private final String wire;

    UpdateStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}