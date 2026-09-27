package com.potatotv.prl.engine;

import java.util.Locale;

/** 规则的发布状态（设计文档 §2.13.1 版本表的 {@code status} 字段）。 */
public enum RuleStatus {

    /** 刚提交，未进入灰度。 */
    DRAFT,

    /** 灰度测试中：只有一部分流量走这个版本（§2.13.2）。 */
    TESTING,

    /** 线上生效的版本，一个规则同时只应有一个。 */
    ACTIVE,

    /** 被新版本取代，保留以便回滚。 */
    DEPRECATED,

    /** 因故障被摘掉的版本，不能再被发布或回滚选中。 */
    DISABLED;

    /** 是否正在生效（含灰度）。 */
    public boolean isLive() {
        return this == ACTIVE || this == TESTING;
    }

    public static RuleStatus fromKeyword(String keyword) {
        return valueOf(keyword.trim().toUpperCase(Locale.ROOT));
    }
}