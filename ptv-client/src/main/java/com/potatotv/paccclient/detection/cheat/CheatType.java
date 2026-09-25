package com.potatotv.paccclient.detection.cheat;

/**
 * 作弊类型（文档 §3.2：在既有 AutoClicker/KillAura/Speed/Fly/Reach 之外新增 15 种）。
 *
 * <p>每种类型有独立特征维度与判定逻辑（{@code detection.cheat.rules} 下一条规则对应一个类型），
 * 端侧命中后由 {@code LayeredDecision} 决定直接处置 / 上报云端。</p>
 */
public enum CheatType {

    // ---- 既有 5 种（文档 §1.1 现状） ----
    /** 自动点击。 */
    AUTOCLICKER("autoclicker", "自动点击"),
    /** 自动瞄准。 */
    KILLAURA("killaura", "自瞄"),
    /** 飞行。 */
    FLY("fly", "飞行"),
    /** 加速。 */
    SPEED("speed", "加速"),
    /** 超远攻击距离。 */
    REACH("reach", "超距攻击"),

    // ---- 文档 §3.2 新增 15 种 ----
    /** 搭路（含 telly/scaffold 类）。 */
    SCAFFOLD("scaffold", "搭路"),
    /** 快速放置。 */
    FASTPLACE("fastplace", "快速放置"),
    /** 快速破坏。 */
    FASTBREAK("fastbreak", "快速破坏"),
    /** 范围破坏。 */
    NUKER("nuker", "范围破坏"),
    /** 非法暴击。 */
    CRITICALS("criticals", "非法暴击"),
    /** 击退抑制。 */
    VELOCITY("velocity", "击退抑制"),
    /** 无坠落伤害。 */
    NOFALL("nofall", "无坠落伤害"),
    /** 自动上台阶。 */
    STEP("step", "自动上台阶"),
    /** 冲刺违规（OmniSprint 等）。 */
    SPRINT("sprint", "冲刺违规"),
    /** 自动格挡。 */
    AUTOBLOCK("autoblock", "自动格挡"),
    /** 快速箱子。 */
    CHESTSTEALER("cheststealer", "快速箱子"),
    /** 背包管理。 */
    INVMANAGER("invmanager", "背包管理"),
    /** 自动护甲。 */
    AUTOARMOR("autoarmor", "自动护甲"),
    /** 快速进食。 */
    FASTEAT("fasteat", "快速进食"),
    /** 位置闪烁。 */
    BLINK("blink", "位置闪烁");

    private final String code;
    private final String displayName;

    CheatType(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /** 机器可读标识（用于上报与规则引擎）。 */
    public String code() {
        return code;
    }

    /** 中文名（用于管理端展示）。 */
    public String displayName() {
        return displayName;
    }
}
