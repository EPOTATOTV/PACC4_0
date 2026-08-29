package com.potatotv.pacc.domain;

import java.util.Set;

/**
 * v4.1 外挂类型（密封层级，封闭检测模块的继承层次）。
 * <p>覆盖暴力外挂与隐身外挂全类型，与技术设计文档专章一一对应。</p>
 */
public sealed interface CheatType {

    String code();
    String displayName();
    Category category();

    /** 暴力外挂（功能类）：KillAura / Reach / Fly 等。 */
    enum BruteForce implements CheatType {
        KILLAURA("killaura", "KillAura 杀戮光环"),
        CRYSTAL_AURA("crystal_aura", "Crystal Aura 水晶光环"),
        AUTO_TOTEM("auto_totem", "AutoTotem 自动图腾"),
        REACH("reach", "Reach 范围攻击"),
        VELOCITY("velocity", "Velocity 反击退"),
        CRITICALS("criticals", "Criticals 强制暴击"),
        FLY("fly", "Fly 飞行"),
        SPEED("speed", "Speed 加速"),
        NO_FALL("nofall", "NoFall 免疫摔落"),
        SCAFFOLD("scaffold", "Scaffold 搭路"),
        AUTO_CLICKER("autoclicker", "AutoClicker 自动点击"),
        FAST_PLACE("fastplace", "FastPlace 快速放置"),
        FAST_BREAK("fastbreak", "FastBreak 快速挖掘"),
        NUKER("nuker", "Nuker 范围挖掘"),
        AUTO_ARMOR("autoarmor", "AutoArmor 自动护甲"),
        AUTO_POT("autopot", "AutoPot 自动喝药"),
        AUTO_EAT("autoeat", "AutoEat 自动进食");

        private final String code;
        private final String displayName;

        BruteForce(String code, String displayName) {
            this.code = code;
            this.displayName = displayName;
        }

        @Override public String code() { return code; }
        @Override public String displayName() { return displayName; }
        @Override public Category category() { return Category.BRUTE_FORCE; }
    }

    /** 隐身外挂（对抗类）：DMA / 幽灵客户端 / 注入等。 */
    enum Stealth implements CheatType {
        DMA_CHEAT("dma_cheat", "DMA 硬件作弊"),
        KERNEL_DRIVER("kernel_driver", "内核级作弊驱动"),
        GHOST_CLIENT("ghost_client", "幽灵客户端"),
        REFLECTIVE_DLL("reflective_dll", "反射式 DLL 注入"),
        PACKET_CHEAT("packet_cheat", "数据包级作弊"),
        LAG_CHEAT("lag_cheat", "延迟作弊"),
        CLOUD_CHEAT("cloud_cheat", "云作弊 / Proxy"),
        MICRO_AIMBOT("micro_aimbot", "微自瞄"),
        SLOW_HACK("slow_hack", "慢加速 / 参数微调"),
        ANTI_SCREENSHOT("anti_screenshot", "反截图 / 反检测"),
        VIRTUALIZATION("virtualization", "虚拟化作弊"),
        AI_DRIVEN_CHEAT("ai_driven", "AI 驱动作弊");

        private final String code;
        private final String displayName;

        Stealth(String code, String displayName) {
            this.code = code;
            this.displayName = displayName;
        }

        @Override public String code() { return code; }
        @Override public String displayName() { return displayName; }
        @Override public Category category() { return Category.STEALTH; }
    }

    /** 检测分类：暴力外挂 / 隐身外挂 / 底层环境。 */
    enum Category { BRUTE_FORCE, STEALTH, LOW_LEVEL }

    /** 全部暴力外挂类型。 */
    Set<CheatType> BRUTE_FORCE_TYPES = Set.of(BruteForce.values());

    /** 全部隐身外挂类型。 */
    Set<CheatType> STEALTH_TYPES = Set.of(Stealth.values());

    /** 按 code 解析；未知返回 null。 */
    static CheatType fromCode(String code) {
        if (code == null) return null;
        for (BruteForce t : BruteForce.values()) {
            if (t.code.equalsIgnoreCase(code)) return t;
        }
        for (Stealth t : Stealth.values()) {
            if (t.code.equalsIgnoreCase(code)) return t;
        }
        return null;
    }
}