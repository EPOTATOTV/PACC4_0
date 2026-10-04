package com.potatotv.paccclient.detection.cheat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 作弊类型（文档 §3.2 在既有 AutoClicker/KillAura/Speed/Fly/Reach 之外新增 15 种，
 * §6.1 再补内存注入 / 系统级 / 行为异常 9 种，本次 DF Alpha 1.0.0 又补第三方作弊软件 /
 * 系统痕迹 / 行为异常 12 种，共 41 种；规则文件数即去重后的类型数）。
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
    BLINK("blink", "位置闪烁"),

    // ---- 文档 §6.1 新增 9 种（内存注入 / 系统级 / 行为异常三大类） ----
    /** 内存区域异常（内存注入类）。 */
    MEMORY_REGION("memory_region", "内存区域异常"),
    /** 可疑模块注入（内存注入类）。 */
    MODULE_INJECTION("module_injection", "可疑模块注入"),
    /** 调试器挂载（系统级）。 */
    DEBUGGER_PRESENT("debugger_present", "调试器挂载"),
    /** 注入工具进程（系统级）。 */
    INJECTOR_TOOL("injector_tool", "注入工具进程"),
    /** 虚拟化运行环境（系统级）。 */
    VIRTUAL_MACHINE("virtual_machine", "虚拟化运行环境"),
    /** 内核钩子与驱动异常（系统级）。 */
    KERNEL_HOOK("kernel_hook", "内核钩子与驱动异常"),
    /** 点击节拍机械化（行为异常）。 */
    CLICK_REGULARITY("click_regularity", "点击节拍机械化"),
    /** 移动轨迹异常（行为异常）。 */
    TRAJECTORY_ANOMALY("trajectory_anomaly", "移动轨迹异常"),
    /** 反应时间异常（行为异常）。 */
    REACTION_TIME("reaction_time", "反应时间异常"),

    // ---- 文档 §6.1 新增 12 种（第三方作弊软件 / 系统痕迹 / 行为异常） ----
    /** 作弊软件进程（CE / Horion 等）。 */
    CHEAT_PROCESS("cheat_process", "作弊软件进程"),
    /** 可疑窗口标题。 */
    SUSPICIOUS_WINDOW("suspicious_window", "可疑窗口标题"),
    /** 已知作弊模块注入。 */
    KNOWN_CHEAT_MODULE("known_cheat_module", "已知作弊模块"),
    /** 可疑未知模块。 */
    SUSPICIOUS_MODULE("suspicious_module", "可疑模块"),
    /** 内存特征码命中。 */
    MEMORY_SIGNATURE("memory_signature", "内存特征码命中"),
    /** 作弊软件文件痕迹。 */
    CHEAT_FILE_TRACE("cheat_file_trace", "作弊文件痕迹"),
    /** 注册表痕迹。 */
    CHEAT_REGISTRY_TRACE("cheat_registry_trace", "注册表痕迹"),
    /** 作弊驱动 / 服务。 */
    CHEAT_DRIVER("cheat_driver", "作弊驱动/服务"),
    /** 可疑输入设备（宏 / MCU）。 */
    SUSPICIOUS_INPUT_DEVICE("suspicious_input_device", "可疑输入设备"),
    /** 异常网络行为。 */
    SUSPICIOUS_NETWORK("suspicious_network", "异常网络行为"),
    /** 行为异常（AI 判定）。 */
    BEHAVIOR_ANOMALY("behavior_anomaly", "行为异常"),
    /** IFEO 镜像劫持。 */
    IFEO_HIJACK("ifeo_hijack", "镜像劫持");

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

    /**
     * 按 {@link #code()} 反查类型。
     *
     * <p>规则由服务端下发，规则文件里写的 type 是外部输入，认不出来就返回空让调用方跳过，
     * 不能凭空造一个类型出来。枚举值数量固定，映射表建一次即可。</p>
     */
    public static Optional<CheatType> fromCode(String code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }

    private static final Map<String, CheatType> BY_CODE = byCode();

    private static Map<String, CheatType> byCode() {
        Map<String, CheatType> map = new LinkedHashMap<>();
        for (CheatType type : values()) {
            map.put(type.code, type);
        }
        return Map.copyOf(map);
    }

    /** 中文名（用于管理端展示）。 */
    public String displayName() {
        return displayName;
    }
}
