package com.potatotv.pacc.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 探针识别结果模型。同一实例由本地位集托管，供 ptv-client 周期轮询上报 PTV。
 *
 * <p>线程安全：所有读写经由 {@code synchronized} 方法；窗口内缓存结果由
 * {@code drain()} 一次性取走。</p>
 */
public final class Findings {

    private static final long WINDOW_MS = 30_000;

    private final List<Finding> queue = new ArrayList<>();

    /** 记录一条识别结果（线程安全）。 */
    public synchronized void add(String signature, String severity, String detail) {
        queue.add(new Finding(signature, severity, detail, System.currentTimeMillis()));
    }

    /** 取走窗口内全部结果并清空（ptv-client 上报后调用）。 */
    public synchronized List<Finding> drain() {
        List<Finding> out = new ArrayList<>(queue);
        queue.clear();
        return out;
    }

    /** 生成去后的 JSON 快照（health/sample 端点展示用）。 */
    public synchronized String snapshotJson() {
        List<Finding> recent = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Finding f : queue) if (now - f.timestamp <= WINDOW_MS) recent.add(f);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("ok", true);
        root.put("count", recent.size());
        root.put("window_ms", WINDOW_MS);
        List<Map<String, String>> items = new ArrayList<>();
        for (Finding f : recent) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("signature", f.signature);
            m.put("severity", f.severity);
            m.put("detail", f.detail);
            items.add(m);
        }
        root.put("findings", items);
        return Json.encode(root);
    }

    /** 单条识别结果。 */
    public record Finding(String signature, String severity, String detail, long timestamp) {
        public Map<String, String> asMap() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("signature", signature);
            m.put("severity", severity);
            m.put("detail", detail);
            return m;
        }
    }
}