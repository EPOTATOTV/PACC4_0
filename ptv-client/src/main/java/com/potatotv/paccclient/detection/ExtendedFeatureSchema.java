package com.potatotv.paccclient.detection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DF Alpha 1.0.0 扩展特征维度权威表（文档 §7.1，新增 32 维）。
 *
 * <p>178 维核心特征（{@link FeatureSchema}）顺序固定、不可增删；本表描述的 {@code ext_} 前缀维度
 * 由各专项检测器（{@code detection.scanner}）产出，走 {@link FeatureVector#putExtended} 写入，
 * 与核心维度分开存放。分开的原因：核心维度是端侧 AI 与降维的唯一入参（顺序敏感，必须恒为 178），
 * 扩展维度只服务 PRL 规则与上报，动态增减不应影响模型输入。</p>
 *
 * <p>分组与文档 §7.1 一致：进程 6 / 模块 4 / 内存 3 / 文件 3 / 注册表 2 / 驱动 3 / 输入设备 3 /
 * 网络 4 / 行为 4 = 32。</p>
 */
public final class ExtendedFeatureSchema {

    /** 扩展维度分组（文档 §7.1 表）。 */
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
        BEHAVIOR
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

    /** 扩展维度总数（32）。 */
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
     * 校验表结构：总数 32、键唯一、全部 {@code ext_} 前缀、分组计数与文档一致。
     *
     * @throws IllegalStateException 校验失败
     */
    public static void validate() {
        if (DIMS.size() != 32) throw new IllegalStateException("扩展维度总数应为 32，实际 " + DIMS.size());
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
    }

    private static void expect(Group g, int count) {
        int actual = size(g);
        if (actual != count) throw new IllegalStateException(g + " 扩展维度数应为 " + count + "，实际 " + actual);
    }

    private static List<Dim> build() {
        List<Dim> d = new ArrayList<>(32);

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

        return List.copyOf(d);
    }

    private static Dim dim(String key, Group group, String description) {
        return new Dim(key, group, description);
    }
}