package com.potatotv.paccclient.detection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DF Alpha 1.0.0 扩展特征维度权威表：系统专项批次 32 维 + 三层检测架构批次 42 维。
 *
 * <p>178 维核心特征（{@link FeatureSchema}）顺序固定、不可增删；本表描述的 {@code ext_} 前缀维度
 * 由各专项检测器（{@code detection.scanner}、{@code detection.network}、{@code detection.vision}、
 * {@code detection.input}）产出，走 {@link FeatureVector#putExtended} 写入，与核心维度分开存放。
 * 分开的原因：核心维度是端侧 AI 与降维的唯一入参（顺序敏感，必须恒为 178），
 * 扩展维度只服务 PRL 规则与上报，动态增减不应影响模型输入。</p>
 *
 * <p>分组计数（三层检测架构批次，文档 §2.7 / §3.7 / §4.6）：</p>
 * <ul>
 *   <li>网络代理层 {@link Group#NET_PROXY} 13 维：文档 12 项 + {@code ext_net_score} 本层综合分；</li>
 *   <li>屏幕层 {@link Group#VISION} 7 维：文档 6 项 + {@code ext_vision_score} 本层综合分；</li>
 *   <li>输入时序 {@link Group#INPUT_TIMING} 6 维：文档 5 项 + {@code ext_input_timing_score} 本层综合分；</li>
 *   <li>板载宏 {@link Group#MACRO} 2 维；</li>
 *   <li>系统进程层增强 {@link Group#SYSTEM_DEEP} 13 维：文档 12 项 + {@code ext_sys_score} 本层综合分；</li>
 *   <li>融合 {@link Group#FUSION} 1 维：{@code ext_fusion_score}（文档 §5.3 三层加权风险分）。</li>
 * </ul>
 *
 * <p>每层综合分与融合分是文档本身没逐条列出、但 §5.3「权重 35/25/40 + 阈值 85/50/20」加权判定
 * 必须要有的入参：没有它们，{@code LayeredDecision} 只能回读各分项自己再拼一遍权重，规则里也
 * 无法直接引用。除此之外所有维度键与文档 §2.7 / §3.7 / §4.6 逐条对应。</p>
 */
public final class ExtendedFeatureSchema {

    /** 系统专项批次（文档 §7.1）扩展维度数。 */
    public static final int SYSTEM_BATCH_SIZE = 32;
    /** 三层检测架构批次（文档 §2.7 / §3.7 / §4.6）扩展维度数。 */
    public static final int LAYER_BATCH_SIZE = 42;

    /** 扩展维度分组。 */
    public enum Group {
        /** 进程检测 6 维（ProcessScanner）。 */
        PROCESS,
        /** 模块检测 4 维（ModuleScanner）。 */
        MODULE,
        /** 内存检测 3 维（MemoryScanner）。 */
        MEMORY,
        /** 文件检测 3 维（FileScanner）。 */
        FILE,
        /** 注册表检测 2 维（RegistryScanner）。 */
        REGISTRY,
        /** 驱动检测 3 维（DriverScanner）。 */
        DRIVER,
        /** 输入设备 3 维（InputDeviceScanner）。 */
        INPUT,
        /** 网络检测 4 维（NetworkScanner）。 */
        NETWORK,
        /** 行为 AI 4 维（BehaviorAIScanner）。 */
        BEHAVIOR,
        /** 网络代理层 13 维（NetworkBehaviorScanner，文档 §2.7）。 */
        NET_PROXY,
        /** 屏幕视觉层 7 维（ScreenVisionScanner，文档 §3.7）。 */
        VISION,
        /** 输入时序 6 维（InputTimingScanner，文档 §3.7）。 */
        INPUT_TIMING,
        /** 板载宏 2 维（InputTimingScanner，文档 §3.5 / §3.7）。 */
        MACRO,
        /** 系统进程层增强 13 维（DllSignature / Injection / UnsignedExecutable / KernelCallback，文档 §4.6）。 */
        SYSTEM_DEEP,
        /** 三层融合 1 维（LayeredDecision，文档 §5.1 / §5.3）。 */
        FUSION
    }

    /**
     * 单个扩展维度定义。
     *
     * @param key         特征键（{@code ext_} 前缀）
     * @param group       所属分组
     * @param description 物理含义
     */
    public record Dim(String key, Group group, String description) {
    }

    /** 权威维度表，顺序即上报顺序。 */
    public static final List<Dim> DIMS = build();

    private static final Map<String, Integer> INDEX = index();

    private ExtendedFeatureSchema() {
    }

    private static Map<String, Integer> index() {
        Map<String, Integer> m = new LinkedHashMap<>(DIMS.size() * 2);
        for (int i = 0; i < DIMS.size(); i++) m.put(DIMS.get(i).key(), i);
        return Collections.unmodifiableMap(m);
    }

    /** 全部扩展维度键。 */
    public static List<String> keys() {
        List<String> out = new ArrayList<>(DIMS.size());
        for (Dim d : DIMS) out.add(d.key());
        return out;
    }

    /** 扩展维度总数（74 = 32 + 42）。 */
    public static int size() {
        return DIMS.size();
    }

    /** 指定分组的维度数。 */
    public static int size(Group group) {
        int n = 0;
        for (Dim d : DIMS) {
            if (d.group() == group) n++;
        }
        return n;
    }

    /** 是否为扩展维度键。 */
    public static boolean isExtendedKey(String key) {
        return key != null && INDEX.containsKey(key);
    }

    /**
     * 校验表结构：总数、键唯一、全部 {@code ext_} 前缀、分组计数与文档一致。
     *
     * @throws IllegalStateException 校验失败
     */
    public static void validate() {
        if (DIMS.size() != SYSTEM_BATCH_SIZE + LAYER_BATCH_SIZE) {
            throw new IllegalStateException("扩展维度总数应为 " + (SYSTEM_BATCH_SIZE + LAYER_BATCH_SIZE)
                    + "，实际 " + DIMS.size());
        }
        Set<String> seen = new HashSet<>();
        for (Dim d : DIMS) {
            if (!d.key().startsWith("ext_")) throw new IllegalStateException("非法扩展键前缀: " + d.key());
            if (!seen.add(d.key())) throw new IllegalStateException("重复扩展键: " + d.key());
            if (d.description() == null || d.description().isBlank()) throw new IllegalStateException("缺少说明: " + d.key());
        }
        expect(Group.PROCESS, 6);
        expect(Group.MODULE, 4);
        expect(Group.MEMORY, 3);
        expect(Group.FILE, 3);
        expect(Group.REGISTRY, 2);
        expect(Group.DRIVER, 3);
        expect(Group.INPUT, 3);
        expect(Group.NETWORK, 4);
        expect(Group.BEHAVIOR, 4);
        expect(Group.NET_PROXY, 13);
        expect(Group.VISION, 7);
        expect(Group.INPUT_TIMING, 6);
        expect(Group.MACRO, 2);
        expect(Group.SYSTEM_DEEP, 13);
        expect(Group.FUSION, 1);
    }

    private static void expect(Group g, int count) {
        int actual = size(g);
        if (actual != count) throw new IllegalStateException(g + " 扩展维度数应为 " + count + "，实际 " + actual);
    }

    private static List<Dim> build() {
        List<Dim> d = new ArrayList<>(SYSTEM_BATCH_SIZE + LAYER_BATCH_SIZE);
        buildSystemBatch(d);
        buildLayerBatch(d);
        return List.copyOf(d);
    }

    /** 系统专项批次 32 维（文档 §7.1）。 */
    private static void buildSystemBatch(List<Dim> d) {
        // ---- 进程检测 6 维（ProcessScanner，文档 §4.1） ----
        d.add(dim("ext_process_score", Group.PROCESS, "进程检测综合分（0-100）"));
        d.add(dim("ext_process_hits", Group.PROCESS, "命中作弊进程总数"));
        d.add(dim("ext_process_client_hits", Group.PROCESS, "作弊客户端命中数（基岩/Java）"));
        d.add(dim("ext_process_injector_hits", Group.PROCESS, "注入工具命中数"));
        d.add(dim("ext_process_debugger_hits", Group.PROCESS, "调试器命中数"));
        d.add(dim("ext_process_window_hits", Group.PROCESS, "可疑窗口标题命中数"));

        // ---- 模块检测 4 维（ModuleScanner，文档 §4.2） ----
        d.add(dim("ext_module_score", Group.MODULE, "模块检测综合分（0-100）"));
        d.add(dim("ext_module_known_cheat_hits", Group.MODULE, "已知作弊模块命中数"));
        d.add(dim("ext_module_unknown_count", Group.MODULE, "白名单外的未知模块数"));
        d.add(dim("ext_module_whitelist_ratio", Group.MODULE, "白名单模块占比"));

        // ---- 内存检测 3 维（MemoryScanner，文档 §4.3） ----
        d.add(dim("ext_memory_score", Group.MEMORY, "内存特征码检测综合分（0-100）"));
        d.add(dim("ext_memory_signature_hits", Group.MEMORY, "内存特征码命中数"));
        d.add(dim("ext_memory_supported", Group.MEMORY, "用户态内存扫描是否可用（1/0）"));

        // ---- 文件检测 3 维（FileScanner，文档 §4.4） ----
        d.add(dim("ext_file_score", Group.FILE, "文件痕迹检测综合分（0-100）"));
        d.add(dim("ext_file_trace_hits", Group.FILE, "作弊软件路径命中数"));
        d.add(dim("ext_file_mod_hits", Group.FILE, "作弊 mod 文件命中数"));

        // ---- 注册表检测 2 维（RegistryScanner，文档 §4.5） ----
        d.add(dim("ext_registry_score", Group.REGISTRY, "注册表痕迹检测综合分（0-100）"));
        d.add(dim("ext_registry_ifeo_hits", Group.REGISTRY, "IFEO 镜像劫持 / AppInit_DLLs 命中数"));

        // ---- 驱动检测 3 维（DriverScanner，文档 §4.6） ----
        d.add(dim("ext_driver_score", Group.DRIVER, "驱动 / 服务检测综合分（0-100）"));
        d.add(dim("ext_driver_cheat_hits", Group.DRIVER, "已知作弊驱动命中数"));
        d.add(dim("ext_driver_service_hits", Group.DRIVER, "可疑服务命中数"));

        // ---- 输入设备 3 维（InputDeviceScanner，文档 §4.7） ----
        d.add(dim("ext_input_score", Group.INPUT, "输入设备检测综合分（0-100）"));
        d.add(dim("ext_input_mcu_hits", Group.INPUT, "MCU / HID 模拟设备命中数"));
        d.add(dim("ext_input_macro_software_hits", Group.INPUT, "宏软件进程命中数"));

        // ---- 网络检测 4 维（NetworkScanner，文档 §4.8） ----
        d.add(dim("ext_network_score", Group.NETWORK, "网络行为检测综合分（0-100）"));
        d.add(dim("ext_network_c2_hits", Group.NETWORK, "C2 域名 / IP 命中数"));
        d.add(dim("ext_network_suspicious_port_hits", Group.NETWORK, "异常端口连接命中数"));
        d.add(dim("ext_network_mc_nonstandard_hits", Group.NETWORK, "游戏进程非标准端口连接数"));

        // ---- 行为 AI 4 维（BehaviorAIScanner，文档 §4.9） ----
        d.add(dim("ext_behavior_score", Group.BEHAVIOR, "行为异常综合分（0-100）"));
        d.add(dim("ext_behavior_click_entropy", Group.BEHAVIOR, "点击间隔香农熵"));
        d.add(dim("ext_behavior_trajectory_smoothness", Group.BEHAVIOR, "鼠标轨迹平滑度（越高越像脚本）"));
        d.add(dim("ext_behavior_periodicity", Group.BEHAVIOR, "操作节律周期性（越高越像脚本）"));
    }

    /** 三层检测架构批次 42 维（文档 §2.7 / §3.7 / §4.6 + §5.3 融合入参）。 */
    private static void buildLayerBatch(List<Dim> d) {
        // ---- 网络代理层 13 维（文档 §2.7） ----
        d.add(dim("ext_net_speed_ratio", Group.NET_PROXY, "速度倍率（当前速度 / 基准速度）"));
        d.add(dim("ext_net_vertical_speed", Group.NET_PROXY, "垂直速度（格/s）"));
        d.add(dim("ext_net_fly_duration", Group.NET_PROXY, "持续飞行时间（秒）"));
        d.add(dim("ext_net_teleport_count", Group.NET_PROXY, "瞬移次数"));
        d.add(dim("ext_net_rotation_speed", Group.NET_PROXY, "旋转角速度（度/s）"));
        d.add(dim("ext_net_attack_cps", Group.NET_PROXY, "攻击频率（次/秒）"));
        d.add(dim("ext_net_place_rate", Group.NET_PROXY, "放置频率（次/秒）"));
        d.add(dim("ext_net_break_speed", Group.NET_PROXY, "破坏速度倍率"));
        d.add(dim("ext_net_server_correction", Group.NET_PROXY, "服务器纠正次数"));
        d.add(dim("ext_net_nofall_suspect", Group.NET_PROXY, "无坠落嫌疑（0/1）"));
        d.add(dim("ext_net_byte_anomaly_count", Group.NET_PROXY, "字节级异常数"));
        d.add(dim("ext_net_packet_loss_rate", Group.NET_PROXY, "丢包率（异常高可能是篡改）"));
        d.add(dim("ext_net_score", Group.NET_PROXY, "网络层综合分（0-100，融合入参）"));

        // ---- 屏幕视觉层 7 维（文档 §3.7） ----
        d.add(dim("ext_vision_hud_box_count", Group.VISION, "检测到的自瞄方框数"));
        d.add(dim("ext_vision_esp_lines", Group.VISION, "透视 ESP 线条数"));
        d.add(dim("ext_vision_killaura_circle", Group.VISION, "杀戮光环圆圈（0/1）"));
        d.add(dim("ext_vision_cheat_menu_text", Group.VISION, "作弊菜单文字区域数"));
        d.add(dim("ext_vision_motion_mismatch", Group.VISION, "画面运动与网络速度不匹配（0/1）"));
        d.add(dim("ext_vision_screenshot_anomaly", Group.VISION, "截屏异常（黑屏 / 被遮挡）（0/1）"));
        d.add(dim("ext_vision_score", Group.VISION, "屏幕层综合分（0-100，融合入参）"));

        // ---- 输入时序 6 维（文档 §3.7） ----
        d.add(dim("ext_input_click_entropy", Group.INPUT_TIMING, "点击间隔香农熵"));
        d.add(dim("ext_input_fixed_interval", Group.INPUT_TIMING, "固定间隔点击（0/1）"));
        d.add(dim("ext_input_burst_pattern", Group.INPUT_TIMING, "爆发点击模式（0/1）"));
        d.add(dim("ext_input_click_jitter_ms", Group.INPUT_TIMING, "点击抖动（ms）"));
        d.add(dim("ext_input_key_click_sync", Group.INPUT_TIMING, "键鼠同步率（0-1）"));
        d.add(dim("ext_input_timing_score", Group.INPUT_TIMING, "输入时序综合分（0-100，融合入参）"));

        // ---- 板载宏 2 维（文档 §3.5 / §3.7） ----
        d.add(dim("ext_macro_device_score", Group.MACRO, "板载宏设备综合得分（0-100）"));
        d.add(dim("ext_macro_file_recent", Group.MACRO, "最近 7 天修改的宏配置文件数"));

        // ---- 系统进程层增强 13 维（文档 §4.6） ----
        d.add(dim("ext_sys_unsigned_module_count", Group.SYSTEM_DEEP, "Minecraft 进程中未签名模块数"));
        d.add(dim("ext_sys_blacklisted_publisher", Group.SYSTEM_DEEP, "黑名单发布者模块数"));
        d.add(dim("ext_sys_remote_thread_count", Group.SYSTEM_DEEP, "远程线程数（起始地址不在已加载模块内）"));
        d.add(dim("ext_sys_exec_rw_region_count", Group.SYSTEM_DEEP, "可执行读写内存区域数"));
        d.add(dim("ext_sys_pending_apc", Group.SYSTEM_DEEP, "待处理 APC（0/1；用户态不可见时恒为 0）"));
        d.add(dim("ext_sys_suspicious_handle_count", Group.SYSTEM_DEEP, "可疑句柄数"));
        d.add(dim("ext_sys_ssdt_hooks", Group.SYSTEM_DEEP, "SSDT hook 数（需内核能力，用户态版本恒为 0）"));
        d.add(dim("ext_sys_idt_hooks", Group.SYSTEM_DEEP, "IDT hook 数（需内核能力，用户态版本恒为 0）"));
        d.add(dim("ext_sys_unsigned_driver_count", Group.SYSTEM_DEEP, "未签名驱动数"));
        d.add(dim("ext_sys_suspicious_callback_count", Group.SYSTEM_DEEP, "可疑内核回调数"));
        d.add(dim("ext_sys_unsigned_exe_count", Group.SYSTEM_DEEP, "未签名可执行文件数"));
        d.add(dim("ext_sys_blacklisted_exe_count", Group.SYSTEM_DEEP, "黑名单发布者可执行文件数"));
        d.add(dim("ext_sys_score", Group.SYSTEM_DEEP, "系统进程层综合分（0-100，融合入参）"));

        // ---- 三层融合 1 维（文档 §5.1 / §5.3） ----
        d.add(dim("ext_fusion_score", Group.FUSION, "三层加权融合风险分（0-100，权重 35/25/40）"));
    }

    private static Dim dim(String key, Group group, String description) {
        return new Dim(key, group, description);
    }
}