package com.potatotv.paccclient.inspect;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 远程查端代理（管理员取证侧）。
 * <p>B1 实现：收到服务端 {@code inspect_request} 后，采集本机轻量取证件
 * （进程列表 / 系统与客户端版本）并回传 {@code inspect_started} + {@code inspect_forensics}，
 * 供管理端查端抽屉展示文本取证。真实屏幕共享（B2）经信令通道透传，此处不消费。</p>
 * <p>取证为进程内快照，仅暴露进程名与 CPU/内存占用，不采集用户文件或剪贴板。</p>
 */
public final class InspectAgent {

    /** 回传回调，由 {@code WssReporter.sendTyped} 接入。 */
    private Consumer<Map<String, Object>> responder = m -> {
    };

    /** 最近收到请求的查端会话。 */
    private volatile String pendingSession = null;

    public void setResponder(Consumer<Map<String, Object>> responder) {
        if (responder != null) this.responder = responder;
    }

    /** 处理服务端下发的查端信令。 */
    public void handle(String json) {
        if (json == null) return;
        try {
            if (json.contains("\"type\":\"inspect_request\"")) {
                String sessionId = field(json, "session_id");
                if (sessionId == null) return;
                this.pendingSession = sessionId;
                System.out.println("[PTV-Client] 收到查端请求，准备取证 session=" + sessionId);
                // 先回执已连接，再回传取证件
                Map<String, Object> started = new LinkedHashMap<>();
                started.put("type", "inspect_started");
                started.put("session_id", sessionId);
                started.put("ts", Instant.now().toString());
                responder.accept(started);
                forensics(sessionId);
            } else if (json.contains("\"type\":\"inspect_bye\"")) {
                System.out.println("[PTV-Client] 查端结束，解除监管 session=" + field(json, "session_id"));
                this.pendingSession = null;
            } else {
                // B2 透传（inspect_answer/ice 等）本轮客户端原样打印，不消费
                System.out.println("[PTV-Client] 查端信令(透传): " + json);
            }
        } catch (Exception e) {
            System.err.println("[PTV-Client] 处理查端信令失败: " + e.getMessage());
        }
    }

    /** 采集轻量取证件并以 inspect_forensics 回传。 */
    private void forensics(String sessionId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "inspect_forensics");
        m.put("session_id", sessionId);
        m.put("ts", Instant.now().toString());
        m.put("os", System.getProperty("os.name", "unknown") + " "
                + System.getProperty("os.version", "") + " "
                + System.getProperty("os.arch", ""));
        m.put("java", System.getProperty("java.version", "unknown"));
        m.put("cpu_threads", Runtime.getRuntime().availableProcessors());
        m.put("processes", topProcesses(8));
        responder.accept(m); // type 由 sendTyped 补
        System.out.println("[PTV-Client] 已回传取证件 session=" + sessionId + " processes=" + m.get("processes"));
    }

    /** 按 CPU 占用取前 N 个进程（纯 JDK，仅进程名/pid/CPU）。 */
    private java.util.List<Map<String, Object>> topProcesses(int limit) {
        java.util.List<ProcessHandle> pids = new ArrayList<>();
        // 一次性快照，避免对进程句柄做长时持有
        ProcessHandle.allProcesses().forEach(pids::add);
        pids.sort(Comparator.comparingDouble((ProcessHandle p) ->
                        p.info().totalCpuDuration().map(d -> (double) d.toNanos()).orElse(0.0))
                .reversed());
        java.util.List<Map<String, Object>> out = new ArrayList<>();
        int n = Math.min(limit, pids.size());
        for (int i = 0; i < n; i++) {
            ProcessHandle ph = pids.get(i);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("pid", ph.pid());
            p.put("name", ph.info().command().map(c -> {
                String[] seg = c.split("[\\\\/]");
                return seg[seg.length - 1];
            }).orElse("?"));
            ph.info().totalCpuDuration().ifPresent(d -> p.put("cpu", String.format("%.1fs", d.toNanos() / 1e9)));
            out.add(p);
        }
        return out;
    }

    /** 极简 JSON 字段提取（无第三方依赖）。 */
    private static String field(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int i = json.indexOf(marker);
        if (i < 0) return null;
        int s = i + marker.length();
        int e = json.indexOf('"', s);
        if (e < 0) return null;
        return json.substring(s, e);
    }
}