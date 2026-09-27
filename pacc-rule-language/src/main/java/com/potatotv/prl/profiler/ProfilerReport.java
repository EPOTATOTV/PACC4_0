package com.potatotv.prl.profiler;

import java.util.List;
import java.util.Locale;

/**
 * 一条规则的性能报告（设计文档 §2.15.2 的输出示例）。
 *
 * <p>耗时字段全是纳秒，展示时按毫秒格式化；{@link #text()} 拼出的就是文档里那段文本。</p>
 *
 * @param ruleName       规则名
 * @param version        规则版本，未登记时为 {@code null}
 * @param executions     执行次数
 * @param averageNanos   平均执行时间；没执行过时为 0
 * @param p95Nanos       P95 执行时间
 * @param p99Nanos       P99 执行时间
 * @param maxNanos       最慢一次
 * @param memoryPeakBytes 内存峰值（估算值，来源见 {@code PrlProfiler#register}）
 * @param hotspots       热点函数，按自身耗时降序
 * @param suggestions    优化建议
 */
public record ProfilerReport(String ruleName,
                             String version,
                             long executions,
                             long averageNanos,
                             long p95Nanos,
                             long p99Nanos,
                             long maxNanos,
                             long memoryPeakBytes,
                             List<FunctionHotspot> hotspots,
                             List<String> suggestions) {

    public ProfilerReport {
        hotspots = hotspots == null ? List.of() : List.copyOf(hotspots);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    /** 报告文本，格式对齐 §2.15.2 的示例。 */
    public String text() {
        StringBuilder sb = new StringBuilder();
        sb.append("规则: ").append(ruleName);
        if (version != null && !version.isBlank()) {
            sb.append(" v").append(version.startsWith("v") ? version.substring(1) : version);
        }
        sb.append('\n');
        sb.append("执行次数: ").append(String.format(Locale.ROOT, "%,d", executions)).append('\n');
        sb.append("平均执行时间: ").append(formatDuration(averageNanos)).append('\n');
        sb.append("P95: ").append(formatDuration(p95Nanos)).append('\n');
        sb.append("P99: ").append(formatDuration(p99Nanos)).append('\n');
        sb.append("最大: ").append(formatDuration(maxNanos)).append('\n');
        sb.append("内存峰值: ").append(formatBytes(memoryPeakBytes)).append('\n');
        sb.append('\n').append("热点函数:").append('\n');
        appendHotspots(sb);
        sb.append('\n').append("优化建议:").append('\n');
        if (suggestions.isEmpty()) {
            sb.append("  - 没有需要处理的热点").append('\n');
        } else {
            for (String suggestion : suggestions) {
                sb.append("  - ").append(suggestion).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 热点列表按文档的样子最多列 5 行：不足 5 个函数就全列，超过就把前 4 名之外合并成「其他」。
     * 合并是为了让百分比加起来是 100 —— 列了前 5 名却剩下的时间不计入，面板上会显得数字对不上。
     */
    private void appendHotspots(StringBuilder sb) {
        if (hotspots.isEmpty()) {
            sb.append("  没有函数级采样（规则里没有函数调用）").append('\n');
            return;
        }
        int named = hotspots.size() <= 5 ? hotspots.size() : 4;
        for (int i = 0; i < named; i++) {
            FunctionHotspot hotspot = hotspots.get(i);
            sb.append("  ").append(i + 1).append(". ").append(pad(hotspot.name(), 26))
                    .append("- ").append(Math.round(hotspot.share() * 100)).append("% 时间").append('\n');
        }
        if (named < hotspots.size()) {
            double rest = 0;
            for (int i = named; i < hotspots.size(); i++) {
                rest += hotspots.get(i).share();
            }
            sb.append("  ").append(named + 1).append(". ").append(pad("其他", 26))
                    .append("- ").append(Math.round(rest * 100)).append("% 时间").append('\n');
        }
    }

    private static String pad(String text, int width) {
        if (text.length() >= width) {
            return text + " ";
        }
        return text + " ".repeat(width - text.length());
    }

    /** 纳秒 → 毫秒文本，保留两位小数。 */
    static String formatDuration(long nanos) {
        return String.format(Locale.ROOT, "%.2fms", nanos / 1_000_000.0);
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return trimZero(bytes / 1024.0) + "KB";
        }
        return trimZero(bytes / (1024.0 * 1024.0)) + "MB";
    }

    private static String trimZero(double value) {
        String text = String.format(Locale.ROOT, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    @Override
    public String toString() {
        return text();
    }
}