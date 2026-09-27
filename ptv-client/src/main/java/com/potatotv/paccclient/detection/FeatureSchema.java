package com.potatotv.paccclient.detection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v5.2 特征向量维度权威表（文档 §2.2.1/2.2.2/2.2.3，共 178 维）。
 *
 * <p>维度分组与文档一致：战斗 52 / 移动 44 / 环境设备 30 / JVM 20 / 模组 15 / 网络 17 = 178。
 * 本表是端侧采集、端侧降维（{@link PcaProjector}）与端云上报（{@link FeatureReport}）的唯一顺序来源。</p>
 *
 * <p>与文档的差异（均为兼容既有消费方而保留）：</p>
 * <ul>
 *   <li>{@code feature_jitter_entropy}：后端 {@code ZeroDayAnomalyService} 消费的旧名，与文档名
 *       {@code feature_aim_micro_jitter_entropy} 并存（legacy 别名）。</li>
 *   <li>{@code feature_trajectory_curvature}：后端消费的旧名，与文档名
 *       {@code feature_aim_trajectory_curvature} 并存（legacy 别名）。</li>
 *   <li>{@code feature_semantic_killaura} / {@code feature_human_likeness} / {@code feature_velocity_ratio}
 *       等既有消费方（{@code BruteForceDetector}/{@code StealthDetector}）产出的键全部保留。</li>
 * </ul>
 */
public final class FeatureSchema {

    /** 维度分组（文档 §2.2.1 表）。 */
    public enum Group {
        /** 战斗特征 52 维（鼠标 Hook + 游戏内存只读采样）。 */
        COMBAT,
        /** 移动特征 44 维（键盘 Hook + 位置采样）。 */
        MOVEMENT,
        /** 环境设备特征 30 维（WMI/USB 枚举/进程列表 + 遗留隐身硬件维度）。 */
        ENVIRONMENT,
        /** JVM 特征 20 维（Java Agent 探针 / JMX 内省）。 */
        JVM,
        /** 模组特征 15 维（模组列表 + 签名校验，Java 版）。 */
        MOD,
        /** 网络特征 17 维（本地网络栈只读采样）。 */
        NETWORK
    }

    /**
     * 单个维度定义。
     *
     * @param key         特征键（{@code feature_} 前缀）
     * @param group       所属分组
     * @param description 物理含义
     * @param formula     计算方式
     */
    public record Dim(String key, Group group, String description, String formula) {
    }

    /** 文档中的中性默认值：这些键的合法默认是非零值，{@link FeatureVector#nonPlaceholderCount()} 据此剔除占位。 */
    static final Map<String, Double> NEUTRAL_DEFAULTS = Map.of(
            "feature_speed_ratio", 1.0,
            "feature_velocity_ratio", 1.0);

    /** 权威维度表，顺序即 {@link FeatureVector#toArray()} 顺序。 */
    public static final List<Dim> DIMS = build();

    private static final Map<String, Integer> INDEX = index();

    private FeatureSchema() {
    }

    private static Map<String, Integer> index() {
        Map<String, Integer> m = new LinkedHashMap<>(DIMS.size() * 2);
        for (int i = 0; i < DIMS.size(); i++) m.put(DIMS.get(i).key(), i);
        return Collections.unmodifiableMap(m);
    }

    /** 全部维度键（schema 顺序）。 */
    public static List<String> keys() {
        List<String> out = new ArrayList<>(DIMS.size());
        for (Dim d : DIMS) out.add(d.key());
        return out;
    }

    /** 指定分组的维度定义。 */
    public static List<Dim> group(Group group) {
        List<Dim> out = new ArrayList<>();
        for (Dim d : DIMS) {
            if (d.group() == group) out.add(d);
        }
        return out;
    }

    /** 指定分组的维度数。 */
    public static int size(Group group) {
        int n = 0;
        for (Dim d : DIMS) {
            if (d.group() == group) n++;
        }
        return n;
    }

    /** 维度总数（178）。 */
    public static int size() {
        return DIMS.size();
    }

    /** 键的 schema 下标；未知键返回 -1。 */
    public static int indexOf(String key) {
        Integer i = INDEX.get(key);
        return i == null ? -1 : i;
    }

    /**
     * 校验表结构：总数 178、键唯一、全部 {@code feature_} 前缀、分组计数与文档一致。
     *
     * @throws IllegalStateException 校验失败
     */
    public static void validate() {
        if (DIMS.size() != 178) throw new IllegalStateException("维度总数应为 178，实际 " + DIMS.size());
        Set<String> seen = new HashSet<>();
        for (Dim d : DIMS) {
            if (!d.key().startsWith("feature_")) throw new IllegalStateException("非法键前缀: " + d.key());
            if (!seen.add(d.key())) throw new IllegalStateException("重复键: " + d.key());
            if (d.description() == null || d.description().isBlank()) throw new IllegalStateException("缺少说明: " + d.key());
            if (d.formula() == null || d.formula().isBlank()) throw new IllegalStateException("缺少公式: " + d.key());
        }
        expect(Group.COMBAT, 52);
        expect(Group.MOVEMENT, 44);
        expect(Group.ENVIRONMENT, 30);
        expect(Group.JVM, 20);
        expect(Group.MOD, 15);
        expect(Group.NETWORK, 17);
    }

    private static void expect(Group g, int count) {
        int actual = size(g);
        if (actual != count) throw new IllegalStateException(g + " 维度数应为 " + count + "，实际 " + actual);
    }

    private static List<Dim> build() {
        List<Dim> d = new ArrayList<>(178);

        // ================= 战斗 52 维（文档 §2.2.2 + 15 种作弊类型所需的交互/时序扩展） =================
        d.add(dim("feature_click_cps", Group.COMBAT, "点击频率", "滑动窗口 1s 内点击数"));
        d.add(dim("feature_click_interval_mean", Group.COMBAT, "点击间隔均值", "最近 100 次点击间隔的算术平均"));
        d.add(dim("feature_click_interval_var", Group.COMBAT, "点击间隔方差", "最近 100 次点击间隔的二阶中心矩"));
        d.add(dim("feature_click_interval_cv", Group.COMBAT, "点击间隔变异系数", "std/mean"));
        d.add(dim("feature_click_interval_skew", Group.COMBAT, "点击间隔偏度", "三阶标准化中心矩"));
        d.add(dim("feature_click_interval_kurt", Group.COMBAT, "点击间隔峰度", "四阶标准化中心矩减 3"));
        d.add(dim("feature_click_burst_count", Group.COMBAT, "爆发点击次数", "间隔 <30ms 的连续点击数"));
        d.add(dim("feature_click_burst_ratio", Group.COMBAT, "爆发点击占比", "burstCount/总点击数"));
        d.add(dim("feature_killaura_angle_speed", Group.COMBAT, "瞄准角速度", "视角变化率（度/秒）"));
        d.add(dim("feature_killaura_mean", Group.COMBAT, "瞄准角度均值", "最近 50 次攻击的瞄准偏差均值"));
        d.add(dim("feature_killaura_std", Group.COMBAT, "瞄准角度标准差", "最近 50 次攻击瞄准偏差的标准差"));
        d.add(dim("feature_aim_smoothness", Group.COMBAT, "瞄准平滑度", "1 - 归一化二阶导数"));
        d.add(dim("feature_aim_snap_count", Group.COMBAT, "瞄准瞬移次数", "角速度 > 阈值的次数"));
        d.add(dim("feature_aim_snap_ratio", Group.COMBAT, "瞄准瞬移占比", "瞬移次数/总瞄准变化次数"));
        d.add(dim("feature_aim_target_attraction", Group.COMBAT, "目标吸引力", "瞄准方向与敌人方向的余弦相似度"));
        d.add(dim("feature_aim_micro_jitter_entropy", Group.COMBAT, "微抖动熵（文档名）", "鼠标微抖动位移分布的香农熵"));
        d.add(dim("feature_jitter_entropy", Group.COMBAT, "微抖动熵（legacy 别名）", "同 feature_aim_micro_jitter_entropy，后端旧名"));
        d.add(dim("feature_aim_trajectory_curvature", Group.COMBAT, "轨迹曲率（文档名）", "鼠标轨迹平均曲率"));
        d.add(dim("feature_trajectory_curvature", Group.COMBAT, "轨迹曲率（legacy 别名）", "同 feature_aim_trajectory_curvature，后端旧名"));
        d.add(dim("feature_aim_bezier_fit_error", Group.COMBAT, "贝塞尔拟合误差", "轨迹与三次贝塞尔曲线的 RMSE"));
        d.add(dim("feature_reach_distance", Group.COMBAT, "攻击距离", "玩家与被攻击实体距离"));
        d.add(dim("feature_reach_distance_mean", Group.COMBAT, "平均攻击距离", "最近 20 次攻击距离均值"));
        d.add(dim("feature_reach_distance_max", Group.COMBAT, "最大攻击距离", "最近 20 次攻击距离最大值"));
        d.add(dim("feature_criticals_rate", Group.COMBAT, "暴击率", "暴击次数/总攻击次数"));
        d.add(dim("feature_criticals_impossible", Group.COMBAT, "不可能暴击", "非跳跃/下落状态的暴击次数"));
        d.add(dim("feature_swing_animation_speed", Group.COMBAT, "挥臂动画速度", "手臂动画帧速率"));
        d.add(dim("feature_hit_select_consistency", Group.COMBAT, "命中选择一致性", "每次攻击选中实体的一致性"));
        d.add(dim("feature_semantic_killaura", Group.COMBAT, "语义层自瞄分（PTV 回填）", "语义画像模型输出，端侧置 0"));
        d.add(dim("feature_human_likeness", Group.COMBAT, "人类行为模拟度", "微抖动熵/3.5 归一化，越低越像机器"));
        d.add(dim("feature_ai_pattern_anomaly", Group.COMBAT, "AI 模式异常分（PTV 回填）", "云端 AI 画像异常度，端侧置 0"));
        d.add(dim("feature_slow_accel_drift", Group.COMBAT, "缓慢加速漂移", "持续加速度累积均值，人类近 0"));
        d.add(dim("feature_attack_rate", Group.COMBAT, "攻击频率", "滑动窗口 1s 内攻击次数"));
        d.add(dim("feature_attack_interval_cv", Group.COMBAT, "攻击间隔变异系数", "攻击间隔 std/mean"));
        d.add(dim("feature_autoblock_ratio", Group.COMBAT, "自动格挡触发占比", "攻击后 1 tick 内恢复格挡的占比"));
        d.add(dim("feature_autoblock_switch_time", Group.COMBAT, "格挡切换延迟", "格挡↔攻击切换平均耗时（ms）"));
        d.add(dim("feature_fasteat_duration_mean", Group.COMBAT, "进食动画时长均值", "最近 10 次进食耗时均值（ms），原版约 1600ms"));
        d.add(dim("feature_fasteat_interval_cv", Group.COMBAT, "进食间隔变异系数", "进食起止间隔 std/mean"));
        d.add(dim("feature_cheststealer_items_per_sec", Group.COMBAT, "快速箱子取物速率", "箱子界面每秒物品移动数"));
        d.add(dim("feature_invmanager_ops_per_sec", Group.COMBAT, "背包管理操作速率", "背包界面每秒操作数"));
        d.add(dim("feature_autoarmor_equip_delay", Group.COMBAT, "自动护甲穿戴延迟", "护甲破损到新护甲穿戴的耗时（ms）"));
        d.add(dim("feature_swing_animation_cv", Group.COMBAT, "挥臂动画速度波动", "挥臂速率 std/mean，机器近 0"));
        d.add(dim("feature_aim_rotation_jerk", Group.COMBAT, "瞄准加加速度", "视角变化率的三阶差分均方根"));
        d.add(dim("feature_aim_lock_time", Group.COMBAT, "目标锁定占比", "被锁目标时长/总交战时长的比例"));
        d.add(dim("feature_aim_post_hit_correction", Group.COMBAT, "命中后修正量", "命中后视角回正角度均值，人类偏高"));
        d.add(dim("feature_attack_pitch_mean", Group.COMBAT, "攻击俯仰角均值", "攻击瞬间 pitch 均值（度），机器常锁定恒定值"));
        d.add(dim("feature_attack_pitch_std", Group.COMBAT, "攻击俯仰角标准差", "攻击瞬间 pitch 离散度（度），人类波动大"));
        d.add(dim("feature_criticals_airborne_ratio", Group.COMBAT, "暴击离地占比", "暴击中处于离地状态的比例，人类暴击必须离地"));
        d.add(dim("feature_killaura_target_switch_rate", Group.COMBAT, "目标切换速率", "每秒切换攻击目标次数"));
        d.add(dim("feature_target_lock_duration", Group.COMBAT, "目标锁定持续时长", "同一目标连续被攻击的平均时长（s）"));
        d.add(dim("feature_combo_interval_cv", Group.COMBAT, "连击间隔变异系数", "连击间隔 std/mean"));
        d.add(dim("feature_hit_select_entropy", Group.COMBAT, "命中选择熵", "命中实体选择的香农熵，稳定单目标偏低"));
        d.add(dim("feature_aim_entropy", Group.COMBAT, "瞄准位移熵", "瞄准位移方向分布的香农熵"));

        // ================= 移动 44 维（文档 §2.2.3 + 15 种作弊类型所需扩展） =================
        d.add(dim("feature_speed_ratio", Group.MOVEMENT, "速度倍率", "实际速度/基准速度 5.6 方块每秒"));
        d.add(dim("feature_speed_mean", Group.MOVEMENT, "平均速度", "滑动窗口 1s 内平均速度"));
        d.add(dim("feature_speed_max", Group.MOVEMENT, "最大速度", "滑动窗口 1s 内最大速度"));
        d.add(dim("feature_speed_variance", Group.MOVEMENT, "速度方差", "速度离散度"));
        d.add(dim("feature_fly_vertical_speed", Group.MOVEMENT, "垂直飞行速度", "Y 轴无支撑上升速度"));
        d.add(dim("feature_fly_sustain_time", Group.MOVEMENT, "飞行持续时间", "无支撑滞空最长时长（s）"));
        d.add(dim("feature_nofall_violations", Group.MOVEMENT, "无掉落违规次数", "超过 3 格坠落却无伤害的次数"));
        d.add(dim("feature_nofall_void", Group.MOVEMENT, "虚空无掉落", "Y<0 仍无伤害的次数"));
        d.add(dim("feature_scaffold_block_per_sec", Group.MOVEMENT, "搭路速度", "每秒放置方块数"));
        d.add(dim("feature_scaffold_sneak_consistency", Group.MOVEMENT, "搭路潜行一致性", "搭路时保持潜行的采样占比"));
        d.add(dim("feature_fastplace_block_per_sec", Group.MOVEMENT, "快速放置速率", "每秒放置方块数（>2 异常）"));
        d.add(dim("feature_fastbreak_block_per_sec", Group.MOVEMENT, "快速破坏速率", "每秒破坏方块数"));
        d.add(dim("feature_nuker_break_radius", Group.MOVEMENT, "nuker 破坏半径", "单 tick 同时破坏方块的最远距离"));
        d.add(dim("feature_velocity_horizontal", Group.MOVEMENT, "水平击退", "受击后水平速度变化"));
        d.add(dim("feature_velocity_vertical", Group.MOVEMENT, "垂直击退", "受击后垂直速度变化"));
        d.add(dim("feature_velocity_reduction_ratio", Group.MOVEMENT, "击退减少比例", "实际击退/预期击退"));
        d.add(dim("feature_step_height", Group.MOVEMENT, "台阶高度", "无跳跃净上升高度"));
        d.add(dim("feature_sprint_consistency", Group.MOVEMENT, "冲刺一致性", "冲刺状态与饥饿值/朝向的匹配度"));
        d.add(dim("feature_velocity_ratio", Group.MOVEMENT, "击退比率（legacy 别名）", "既有消费方旧名，等于实际/预期击退"));
        d.add(dim("feature_speed_accel", Group.MOVEMENT, "速度加速度", "速度一阶差分均值"));
        d.add(dim("feature_speed_jerk", Group.MOVEMENT, "速度加加速度", "速度二阶差分均方根，机器近 0"));
        d.add(dim("feature_ground_time_ratio", Group.MOVEMENT, "落地时间占比", "采样中 onGround 的占比"));
        d.add(dim("feature_air_time_ratio", Group.MOVEMENT, "滞空时间占比", "采样中离地的占比"));
        d.add(dim("feature_jump_frequency", Group.MOVEMENT, "跳跃频率", "每秒跳跃次数"));
        d.add(dim("feature_jump_height_mean", Group.MOVEMENT, "跳跃高度均值", "起跳至落地的最大上升高度均值"));
        d.add(dim("feature_strafe_ratio", Group.MOVEMENT, "侧移占比", "侧向速度分量/总水平速度"));
        d.add(dim("feature_omnisprint_violations", Group.MOVEMENT, "OmniSprint 违规次数", "后退/侧移仍保持冲刺的采样次数"));
        d.add(dim("feature_sprint_food_mismatch", Group.MOVEMENT, "冲刺空中违规占比", "空中仍保持冲刺的采样占比（OmniSprint/持续冲刺特征）"));
        d.add(dim("feature_step_violations", Group.MOVEMENT, "Step 违规次数", "无跳跃一次上升 >0.6 格且持续位移的次数"));
        d.add(dim("feature_step_height_max", Group.MOVEMENT, "最大自动上台阶高度", "采样窗口内无跳跃最大净上升高度"));
        d.add(dim("feature_fly_hover_ratio", Group.MOVEMENT, "悬停占比", "垂直速度近 0 且离地的采样占比"));
        d.add(dim("feature_position_delta_per_tick_max", Group.MOVEMENT, "单 tick 最大位移", "相邻位置采样最大距离（格）"));
        d.add(dim("feature_teleport_count", Group.MOVEMENT, "瞬移次数", "单 tick 位移 >8 格的次数"));
        d.add(dim("feature_blink_position_jump", Group.MOVEMENT, "Blink 位置跳变", "位移与网络 RTT 不匹配的跳变幅度"));
        d.add(dim("feature_movement_correlation", Group.MOVEMENT, "输入位移相关性", "键盘输入方向与实际位移方向的相关系数"));
        d.add(dim("feature_movement_input_lag", Group.MOVEMENT, "路径直线度", "位移弦长/累计路径长，越高越接近直线"));
        d.add(dim("feature_path_smoothness", Group.MOVEMENT, "路径平滑度", "1 - 归一化航向角二阶差分"));
        d.add(dim("feature_path_curvature", Group.MOVEMENT, "路径曲率", "水平轨迹平均曲率（1/格）"));
        d.add(dim("feature_diagonal_speed_ratio", Group.MOVEMENT, "斜向速度倍率", "斜向移动速度/轴向移动速度，原版约 1.0"));
        d.add(dim("feature_slowdown_ratio", Group.MOVEMENT, "减速比例", "停止输入后的实际减速/预期减速"));
        d.add(dim("feature_collision_ignored_ratio", Group.MOVEMENT, "空中操控比例", "空中水平速度/地面水平速度，穿墙/无减速会偏高"));
        d.add(dim("feature_void_time", Group.MOVEMENT, "虚空滞留时长", "Y<0 的累计时长（s）"));
        d.add(dim("feature_fall_distance_max", Group.MOVEMENT, "最大坠落距离", "采样窗口内单次最大下落高度（格）"));
        d.add(dim("feature_scaffold_pitch_angle", Group.MOVEMENT, "搭路视角夹角", "视角方向与放置方块面法线的夹角（度）"));

        // ================= 环境设备 30 维（文档 §2.2.1 + 遗留隐身硬件/内核/虚拟化维度） =================
        d.add(dim("feature_pcie_dma_present", Group.ENVIRONMENT, "PCIe DMA 设备", "枚举到可疑 DMA 设备（Xilinx/Altera 等）置 1"));
        d.add(dim("feature_iommu_disabled", Group.ENVIRONMENT, "IOMMU 关闭", "VT-d/AMD-Vi 未启用置 1"));
        d.add(dim("feature_hw_perf_counter_anomaly", Group.ENVIRONMENT, "硬件性能计数器异常", "非 CPU 发起的内存总线事务比例"));
        d.add(dim("feature_unknown_kernel_drivers", Group.ENVIRONMENT, "未知内核驱动数", "无签名/不在白名单的驱动数量"));
        d.add(dim("feature_ssdt_hooks", Group.ENVIRONMENT, "SSDT 钩子数", "系统服务描述表被改写条目数"));
        d.add(dim("feature_ghost_client_mem", Group.ENVIRONMENT, "幽灵客户端内存", "命中幽灵客户端内存区置 1"));
        d.add(dim("feature_remote_threads", Group.ENVIRONMENT, "异常远程线程数", "起始地址不在模块范围内的线程数"));
        d.add(dim("feature_non_image_mem_ratio", Group.ENVIRONMENT, "非镜像内存占比", "可执行非镜像内存区/总内存"));
        d.add(dim("feature_vm_detected", Group.ENVIRONMENT, "虚拟化环境", "检测到 VMware/VBox/Hyper-V/KVM 置 1"));
        d.add(dim("feature_cpu_core_count", Group.ENVIRONMENT, "可用处理器数", "Runtime.availableProcessors()"));
        d.add(dim("feature_cpu_hypervisor_bit", Group.ENVIRONMENT, "CPUID 超管位", "运行时探测到 hypervisor 置 1，纯 JDK 下不可得置 0"));
        d.add(dim("feature_total_memory_gb", Group.ENVIRONMENT, "物理内存总量", "OperatingSystemMXBean.totalMemorySize（GB）"));
        d.add(dim("feature_free_memory_ratio", Group.ENVIRONMENT, "空闲内存占比", "freeMemorySize/totalMemorySize"));
        d.add(dim("feature_disk_free_ratio", Group.ENVIRONMENT, "系统盘可用占比", "FileStore usable/total"));
        d.add(dim("feature_disk_total_gb", Group.ENVIRONMENT, "系统盘总容量", "FileStore total（GB）"));
        d.add(dim("feature_system_uptime_hours", Group.ENVIRONMENT, "系统运行时长", "RuntimeMXBean uptime（小时）"));
        d.add(dim("feature_os_build_age_days", Group.ENVIRONMENT, "系统安装时长", "文件系统根目录创建时间至今天数（近似）"));
        d.add(dim("feature_process_count", Group.ENVIRONMENT, "进程数", "ProcessHandle.allProcesses() 计数"));
        d.add(dim("feature_suspicious_process_count", Group.ENVIRONMENT, "可疑进程数", "命中作弊进程名/路径黑名单的进程数"));
        d.add(dim("feature_signed_process_ratio", Group.ENVIRONMENT, "已知进程占比", "命中进程白名单的比例"));
        d.add(dim("feature_sandbox_indicator_count", Group.ENVIRONMENT, "沙箱特征计数", "低核数/低内存/低磁盘/短运行时长等命中项之和"));
        d.add(dim("feature_sandbox_cpu_cores_low", Group.ENVIRONMENT, "沙箱低核数", "可用处理器 <4 置 1"));
        d.add(dim("feature_debugger_present", Group.ENVIRONMENT, "调试器存在", "RuntimeMXBean inputArguments 含 jdwp/agentlib 置 1"));
        d.add(dim("feature_hardware_breakpoint_count", Group.ENVIRONMENT, "硬件断点数量", "纯 JDK 下不可得，恒 0（文档 §4.3.1 由平台层填充）"));
        d.add(dim("feature_hid_device_count", Group.ENVIRONMENT, "HID 设备数", "网络接口与 USB 枚举可见设备数近似"));
        d.add(dim("feature_usb_device_count", Group.ENVIRONMENT, "USB 设备数", "纯 JDK 下以可枚举设备近似，不可得置 0"));
        d.add(dim("feature_screen_refresh_rate", Group.ENVIRONMENT, "屏幕刷新率", "不可得置 0（由桌面壳回填）"));
        d.add(dim("feature_gpu_vendor_known", Group.ENVIRONMENT, "GPU 厂商已知", "os.arch 与图形栈推断，已知置 1"));
        d.add(dim("feature_system_model_virtual", Group.ENVIRONMENT, "系统型号虚拟", "虚拟化厂商在 os.name/version 中可见置 1"));
        d.add(dim("feature_thermal_throttle_ratio", Group.ENVIRONMENT, "热降频时间占比", "系统 CPU 负载与核数不匹配的时长占比"));

        // ================= JVM 20 维（Java Agent 探针 / JMX 内省） =================
        d.add(dim("feature_jvm_heap_used_ratio", Group.JVM, "堆使用率", "MemoryUsage.used/max（堆）"));
        d.add(dim("feature_jvm_heap_max_mb", Group.JVM, "堆上限", "MemoryUsage.max（MB）"));
        d.add(dim("feature_jvm_gc_count", Group.JVM, "GC 次数", "GarbageCollectorMXBean 累计回收次数"));
        d.add(dim("feature_jvm_gc_time_ms", Group.JVM, "GC 耗时", "GarbageCollectorMXBean 累计耗时（ms）"));
        d.add(dim("feature_jvm_thread_count", Group.JVM, "线程数", "ThreadMXBean.getThreadCount()"));
        d.add(dim("feature_jvm_peak_thread_count", Group.JVM, "峰值线程数", "ThreadMXBean.getPeakThreadCount()"));
        d.add(dim("feature_jvm_daemon_thread_ratio", Group.JVM, "守护线程占比", "daemon 线程数/总线程数（近似）"));
        d.add(dim("feature_jvm_class_loaded_count", Group.JVM, "已加载类数", "ClassLoadingMXBean.getLoadedClassCount()"));
        d.add(dim("feature_jvm_class_unloaded_count", Group.JVM, "已卸载类数", "ClassLoadingMXBean.getUnloadedClassCount()"));
        d.add(dim("feature_jvm_jit_compile_time_ms", Group.JVM, "JIT 编译耗时", "CompilationMXBean 累计耗时（ms）"));
        d.add(dim("feature_jvm_uptime_sec", Group.JVM, "JVM 运行时长", "RuntimeMXBean.getUptime()/1000"));
        d.add(dim("feature_jvm_cpu_load", Group.JVM, "进程 CPU 负载", "OperatingSystemMXBean.getProcessCpuLoad"));
        d.add(dim("feature_jvm_system_cpu_load", Group.JVM, "系统 CPU 负载", "OperatingSystemMXBean.getCpuLoad"));
        d.add(dim("feature_jvm_available_processors", Group.JVM, "JVM 可见处理器数", "OperatingSystemMXBean.getAvailableProcessors()"));
        d.add(dim("feature_jvm_file_descriptor_ratio", Group.JVM, "文件描述符占用率", "open/max（不可得置 0）"));
        d.add(dim("feature_jvm_safepoint_count", Group.JVM, "安全点次数", "累计同步/异步安全点次数（不可得置 0）"));
        d.add(dim("feature_jvm_safepoint_time_ms", Group.JVM, "安全点耗时", "累计安全点停留（ms，不可得置 0）"));
        d.add(dim("feature_jvm_pending_finalization", Group.JVM, "待终结对象数", "MemoryMXBean 待终结计数"));
        d.add(dim("feature_jvm_agent_attached", Group.JVM, "Agent 已挂载", "inputArguments 含 -javaagent 置 1"));
        d.add(dim("feature_jvm_input_args_count", Group.JVM, "JVM 启动参数数", "RuntimeMXBean.getInputArguments().size()"));

        // ================= 模组 15 维（模组列表 + 签名校验） =================
        d.add(dim("feature_mod_count", Group.MOD, "模组总数", "已加载模组数量"));
        d.add(dim("feature_mod_jar_count", Group.MOD, "模组 JAR 数", "模组目录下 JAR 文件数"));
        d.add(dim("feature_mod_unsigned_ratio", Group.MOD, "未签名模组占比", "无签名模组/模组总数"));
        d.add(dim("feature_mod_signature_valid_ratio", Group.MOD, "签名有效占比", "签名校验通过模组/模组总数"));
        d.add(dim("feature_mod_unknown_count", Group.MOD, "未知模组数", "不在白名单的模组数量"));
        d.add(dim("feature_mod_cheat_name_hits", Group.MOD, "作弊模组名命中数", "模组名命中已知作弊客户端名单的数量"));
        d.add(dim("feature_mod_network_loader_count", Group.MOD, "网络加载类加载器数", "从 URL/网络加载类的类加载器数量"));
        d.add(dim("feature_mod_reflective_loader_count", Group.MOD, "反射式加载器数", "内存中无对应文件的类加载器数量"));
        d.add(dim("feature_mod_classloader_count", Group.MOD, "类加载器总数", "已加载类加载器数量"));
        d.add(dim("feature_mod_bytecode_diff_ratio", Group.MOD, "字节码差异比例", "与原版类字节码不一致的关键类占比"));
        d.add(dim("feature_mod_inject_detected", Group.MOD, "注入检测命中", "检出反射式/APC/线程劫持等注入置 1"));
        d.add(dim("feature_mod_fabric_present", Group.MOD, "Fabric 存在", "检出 Fabric 加载器置 1"));
        d.add(dim("feature_mod_forge_present", Group.MOD, "Forge 存在", "检出 Forge 加载器置 1"));
        d.add(dim("feature_mod_mixin_hook_count", Group.MOD, "Mixin 钩子数", "被 Mixin 改写的方法数"));
        d.add(dim("feature_mod_version_mismatch_count", Group.MOD, "版本不匹配模组数", "声明的游戏版本与运行版本不一致的模组数"));

        // ================= 网络 17 维（本地网络栈只读采样 + 遗留网络层维度） =================
        d.add(dim("feature_packet_anomaly_ratio", Group.NETWORK, "数据包异常比例", "异常时序数据包/总数据包"));
        d.add(dim("feature_proxy_detected", Group.NETWORK, "代理检测", "检出代理/VPN 置 1"));
        d.add(dim("feature_abnormal_latency", Group.NETWORK, "异常延迟", "延迟抖动超出阈值的置 1"));
        d.add(dim("feature_net_interface_count", Group.NETWORK, "网络接口数", "NetworkInterface.getNetworkInterfaces() 计数"));
        d.add(dim("feature_net_mtu_mean", Group.NETWORK, "平均 MTU", "各接口 MTU 均值"));
        d.add(dim("feature_net_speed_mean", Group.NETWORK, "平均链路速率", "各接口 speed（Mbps）均值"));
        d.add(dim("feature_net_loopback_ratio", Group.NETWORK, "回环接口占比", "回环接口/接口总数"));
        d.add(dim("feature_net_virtual_ratio", Group.NETWORK, "虚拟接口占比", "虚拟网卡（含 vmnet/vbox/docker 等）占比"));
        d.add(dim("feature_net_bytes_sent", Group.NETWORK, "发送字节数", "接口累计 tx 字节（不可得置 0）"));
        d.add(dim("feature_net_bytes_recv", Group.NETWORK, "接收字节数", "接口累计 rx 字节（不可得置 0）"));
        d.add(dim("feature_net_packet_rate", Group.NETWORK, "包速率", "单位时间收发包数（不可得置 0）"));
        d.add(dim("feature_net_jitter_ms", Group.NETWORK, "网络抖动", "RTT 标准差（ms）"));
        d.add(dim("feature_net_rtt_ms", Group.NETWORK, "往返时延", "最近 RTT 均值（ms）"));
        d.add(dim("feature_net_retransmit_ratio", Group.NETWORK, "重传比例", "TCP 重传/发送段（不可得置 0）"));
        d.add(dim("feature_net_upload_ratio", Group.NETWORK, "上行占比", "上行字节/总字节"));
        d.add(dim("feature_net_down_ratio", Group.NETWORK, "下行占比", "下行字节/总字节"));
        d.add(dim("feature_net_packet_loss_ratio", Group.NETWORK, "丢包比例", "序列缺口/总序列，由 RTT 采样近似"));

        return List.copyOf(d);
    }

    private static Dim dim(String key, Group group, String description, String formula) {
        return new Dim(key, group, description, formula);
    }
}