package com.potatotv.paccclient.redscreen;

/**
 * 红屏指令处理：解析服务端下发的 redscreen_alert / mitigation / unlock 消息，
 * 触发全屏红屏、输入暂停或解除。
 * <p>消息结构对应 PTV 后端广播 Json：{event_type, alert_id, level, cheat_type,
 * pteid_masked, timestamp, risk_score, game_edition}。</p>
 */
public final class RedscreenReceiver {

    private RedscreenReceiver() {
    }

    public static void handle(String json) {
        if (json == null) return;
        try {
            if (json.contains("redscreen_alert")) {
                int level = intOf(json, "level", 2);
                String type = stringOf(json, "cheat_type", "unknown");
                String pteid = stringOf(json, "pteid_masked", "****");
                String risk = stringOf(json, "risk_score", "0");
                FullScreenRed.show(level, type, pteid, risk);
            } else if (json.contains("\"type\":\"mitigation\"")) {
                // 服务端就地防护指令（如 force_close）
                System.out.println("[PTV-Client] 收到缓解指令: " + json);
            } else if (json.contains("unlock") && !json.contains("redscreen")) {
                FullScreenRed.dismiss();
            }
        } catch (Exception e) {
            System.err.println("[PTV-Client] 处理红屏指令失败: " + e.getMessage());
        }
    }

    // ---- 极简 JSON 字段提取（避免引入库） ----
    private static int intOf(String json, String key, int def) {
        try {
            return Integer.parseInt(stringOf(json, key, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }

    private static String stringOf(String json, String key, String def) {
        String marker = "\"" + key + "\":";
        int i = json.indexOf(marker);
        if (i < 0) return def;
        String rest = json.substring(i + marker.length()).trim();
        int depth = 0;
        for (int j = 0; j < rest.length(); j++) {
            char ch = rest.charAt(j);
            if (ch == '{' || ch == '[') depth++;
            else if (ch == '}' || ch == ']') depth--;
            else if (ch == ',' && depth == 0) {
                rest = rest.substring(0, j);
                break;
            }
        }
        rest = rest.trim();
        if (rest.startsWith("\"")) rest = rest.substring(1, rest.indexOf('"', 1));
        return rest;
    }
}