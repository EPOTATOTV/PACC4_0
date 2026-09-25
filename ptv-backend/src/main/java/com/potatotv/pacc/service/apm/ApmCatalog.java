package com.potatotv.pacc.service.apm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v5.4 §2.1 APM 指标目录（静态白名单）。
 *
 * <p>这份目录是「什么指标允许入库」的唯一判定源：客户端上报的指标名必须命中这里，
 * 未命中一律丢弃、不落库。这是防止指标基数被任意字符串撑爆的第一道闸——
 * 原始表按指标名建索引并按小时聚合，一旦允许自由命名，聚合分组与图表都会被长尾指标毁掉。</p>
 *
 * <p>目录同时充当管理端展示元数据（label / unit / group），前端不再维护第二份映射；
 * 因此改动这里等于改动前端可见的指标清单，名称不可随意重命名。</p>
 *
 * <p>分组含义：{@code system} 机器资源、{@code game} 游戏帧与输入、{@code detect} 检测链路时延与效果、
 * {@code health} 客户端自身状态。注意 {@code client_version} 走上报体的独立字段，
 * 它是维度不是度量，因此不占指标位。</p>
 */
public final class ApmCatalog {

    /** 指标定义：名称 / 展示名 / 单位 / 分组。 */
    public record MetricDef(String name, String label, String unit, String group) { }

    private static final List<MetricDef> DEFS = List.of(
            // ------------------------------ system：机器资源 ------------------------------
            new MetricDef("sys_cpu_total", "系统 CPU", "%", "system"),
            new MetricDef("sys_cpu_process", "PACC 进程 CPU", "%", "system"),
            new MetricDef("sys_cpu_game", "游戏进程 CPU", "%", "system"),
            new MetricDef("sys_mem_total", "系统总内存", "MB", "system"),
            new MetricDef("sys_mem_used", "系统已用内存", "MB", "system"),
            new MetricDef("sys_mem_process", "PACC 进程内存", "MB", "system"),
            new MetricDef("sys_mem_game", "游戏进程内存", "MB", "system"),
            new MetricDef("sys_disk_read", "磁盘读取", "KB/s", "system"),
            new MetricDef("sys_disk_write", "磁盘写入", "KB/s", "system"),
            new MetricDef("sys_disk_io_wait", "磁盘 IO 等待", "ms", "system"),
            new MetricDef("sys_net_rx", "网络接收", "KB/s", "system"),
            new MetricDef("sys_net_tx", "网络发送", "KB/s", "system"),
            new MetricDef("sys_net_latency", "到 PTV 的 RTT", "ms", "system"),
            new MetricDef("sys_net_packet_loss", "丢包率", "%", "system"),
            new MetricDef("sys_battery_level", "电池电量", "%", "system"),
            new MetricDef("sys_battery_temp", "电池温度", "℃", "system"),
            new MetricDef("sys_thermal", "CPU/GPU 温度", "℃", "system"),
            new MetricDef("sys_uptime", "系统运行时间", "s", "system"),
            new MetricDef("sys_load_avg", "系统负载", "", "system"),

            // ------------------------------ game：帧与输入 ------------------------------
            new MetricDef("game_fps", "游戏帧率", "FPS", "game"),
            new MetricDef("game_frame_time_mean", "平均帧时间", "ms", "game"),
            new MetricDef("game_frame_time_p95", "P95 帧时间", "ms", "game"),
            new MetricDef("game_frame_time_p99", "P99 帧时间", "ms", "game"),
            new MetricDef("game_frame_time_var", "帧时间方差", "ms²", "game"),
            new MetricDef("game_frame_stutter", "卡顿次数", "次", "game"),
            new MetricDef("game_input_latency", "输入延迟", "ms", "game"),
            new MetricDef("game_input_latency_p95", "输入延迟 P95", "ms", "game"),
            new MetricDef("game_cpu_bound", "CPU 瓶颈占比", "%", "game"),
            new MetricDef("game_gpu_bound", "GPU 瓶颈占比", "%", "game"),
            new MetricDef("game_draw_calls", "Draw Call 数", "次", "game"),
            new MetricDef("game_memory_gc", "GC 停顿", "ms", "game"),

            // ------------------------------ detect：检测链路 ------------------------------
            new MetricDef("detect_collect_latency", "特征采集延迟", "ms", "detect"),
            new MetricDef("detect_collect_latency_p95", "采集延迟 P95", "ms", "detect"),
            new MetricDef("detect_engine_latency", "检测引擎延迟", "ms", "detect"),
            new MetricDef("detect_ai_infer_latency", "AI 推理延迟", "ms", "detect"),
            new MetricDef("detect_stealth_latency", "隐身扫描延迟", "ms", "detect"),
            new MetricDef("detect_event_rate", "检测事件速率", "次/s", "detect"),
            new MetricDef("detect_positive_rate", "检测阳性率", "%", "detect"),
            new MetricDef("detect_false_positive_rate", "误报率", "%", "detect"),
            new MetricDef("detect_feature_dimensions", "特征维度数", "个", "detect"),
            new MetricDef("detect_feature_backfill_ratio", "特征回填率", "%", "detect"),
            new MetricDef("detect_model_infer_count", "AI 推理调用次数", "次", "detect"),
            new MetricDef("detect_model_cache_hit", "模型缓存命中率", "%", "detect"),

            // ------------------------------ health：客户端自身状态 ------------------------------
            new MetricDef("client_uptime", "客户端运行时长", "s", "health"),
            new MetricDef("client_config_hash", "配置哈希", "", "health"),
            new MetricDef("client_integrity_state", "完整性状态", "", "health"),
            new MetricDef("client_antidebug_state", "反调试状态", "", "health"),
            new MetricDef("client_antihook_state", "反 Hook 状态", "", "health"),
            new MetricDef("client_wss_connected", "WSS 连接状态", "", "health"),
            new MetricDef("client_wss_reconnect_count", "WSS 重连次数", "次", "health"),
            new MetricDef("client_report_success_rate", "上报成功率", "%", "health"),
            new MetricDef("client_report_queue_size", "上报队列积压", "条", "health"),
            new MetricDef("client_redscreen_count", "红屏触发次数", "次", "health"),
            new MetricDef("client_crash_count", "崩溃次数", "次", "health")
    );

    private static final Map<String, MetricDef> BY_NAME;
    private static final List<String> NAMES;

    static {
        Map<String, MetricDef> byName = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(DEFS.size());
        for (MetricDef def : DEFS) {
            byName.put(def.name(), def);
            names.add(def.name());
        }
        BY_NAME = Collections.unmodifiableMap(byName);
        NAMES = Collections.unmodifiableList(names);
    }

    private ApmCatalog() {
    }

    /** 全部指标（顺序即前端展示顺序，按分组聚在一起）。 */
    public static List<MetricDef> all() {
        return DEFS;
    }

    /** 全部指标名。 */
    public static List<String> names() {
        return NAMES;
    }

    /** 按名称取定义；未登记的名称返回空 —— 调用方据此拒绝入库。 */
    public static Optional<MetricDef> byName(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(BY_NAME.get(name.trim()));
    }
}