package com.potatotv.pacc.service.detection.df.multimodal;

import java.util.Locale;

/**
 * DF §4.1.3 五模态定义：输入 / 内存 / 网络 / 行为 / 图像，各自的数据源与检测目标见常量的 Javadoc。
 *
 * <p>关键模态（{@link #INPUT}、{@link #MEMORY}、{@link #BEHAVIOR}）在混合融合中走早期（特征拼接），
 * 辅助模态（{@link #NETWORK}、{@link #IMAGE}）走晚期（独立评分后加权）。</p>
 */
public enum Modality {

    /** 输入模态：鼠标/键盘原始事件 → 连点器 / 自瞄 / 脚本。 */
    INPUT("input", "连点器/自瞄/脚本"),
    /** 内存模态：游戏进程内存快照 → 内存修改 / 注入。 */
    MEMORY("memory", "内存修改/注入"),
    /** 网络模态：本地网络栈采样 → 代理 / VPN / 异常连接。 */
    NETWORK("network", "代理/VPN/异常连接"),
    /** 行为模态：移动 / 战斗统计 → 速度 / 飞行 / 杀戮光环。 */
    BEHAVIOR("behavior", "速度/飞行/杀戮光环"),
    /** 图像模态：屏幕截图 OCR → 透视 / 自瞄界面。 */
    IMAGE("image", "透视/自瞄界面");

    private final String key;
    private final String target;

    Modality(String key, String target) {
        this.key = key;
        this.target = target;
    }

    /** 对外键名（小写，用于 JSON 与配置）。 */
    public String key() {
        return key;
    }

    /** 检测目标描述。 */
    public String target() {
        return target;
    }

    /** 是否为混合融合中的关键模态（走早期融合）。 */
    public boolean keyModality() {
        return this == INPUT || this == MEMORY || this == BEHAVIOR;
    }

    /** 由对外键名解析；未知返回 null（调用方据此判为无效输入）。 */
    public static Modality fromKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        for (Modality m : values()) {
            if (m.key.equals(v) || m.name().equalsIgnoreCase(v)) {
                return m;
            }
        }
        return null;
    }
}