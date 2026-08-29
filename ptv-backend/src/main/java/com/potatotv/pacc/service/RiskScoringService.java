package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.domain.DetectionEvent;
import com.potatotv.pacc.rule.LuaRuleEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多维度风险评分（0-100）。加权维度（依据技术设计文档）：
 * <ul>
 *   <li>底层检测分 35%（内存/进程/设备）</li>
 *   <li>行为检测分 25%（移动/战斗/交互）</li>
 *   <li>历史行为分 20%（近 7 天）</li>
 *   <li>AI 模型分 15%（XGBoost + LSTM-AE）</li>
 *   <li>环境风险分 5%</li>
 * </ul>
 * 演示实现：以端侧评分 + 特征命中聚合估算，生产接入真实模型推理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskScoringService {

    private final AiInferenceClient aiInferenceClient;
    private final LuaRuleEngine luaRuleEngine;

    // 各事件类型到"维度"的简单映射（用于加权）
    private enum Dimension { LOW_LEVEL, BEHAVIOR, ENV }

    public int score(DetectionEvent event, Account account) {
        Dimension dim = dimensionOf(event);
        double base = Math.max(0, Math.min(100, event.getClientRiskScore()));
        // 历史行为分：历史作弊记录降低信誉 -> 提升当前分
        int reputation = account != null ? account.getReputation() : 100;
        double historyFactor = Math.max(0.0, 1.0 - reputation / 100.0);

        double score = switch (dim) {
            case LOW_LEVEL -> base * 0.95;          // 底层证据权重最高
            case BEHAVIOR  -> base * 0.8;
            case ENV       -> base * 0.6;
        };
        score = score + historyFactor * 12;          // 历史劣迹加成
        // 端侧已判定 critical 直接抬升
        if ("critical".equalsIgnoreCase(event.getSeverity())) score += 10;

        // AI 模型分（15% 权重）：服务未启用/不可用时自动回退本地评分
        // 注意：score 已被重赋值，lambda 需捕获"实际最终"副本
        final double localScore = score;
        var ai = aiInferenceClient.score(event, account);
        double finalScore = ai.map(v -> localScore * 0.85 + v * 0.15).orElse(localScore);

        // Lua 动态规则命中加分（引擎内封顶 max-bonus）
        double ruleBonus = luaRuleEngine.evaluate(contextOf(event, account, historyFactor)).bonus();
        finalScore += ruleBonus;

        return (int) Math.max(0, Math.min(100, finalScore));
    }

    /** 构造供 Lua 规则评估的事件上下文。 */
    private Map<String, Object> contextOf(DetectionEvent event, Account account, double historyFactor) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("pteid", event.getPteid());
        ctx.put("event_type", event.getEventType());
        ctx.put("severity", event.getSeverity());
        ctx.put("client_risk", event.getClientRiskScore());
        ctx.put("edition", event.getEdition() == null ? "" : event.getEdition().name());
        DetectionEvent.Evidence ev = event.getEvidence();
        ctx.put("process_name", ev == null ? "" : ev.getProcessName());
        ctx.put("memory_region", ev == null ? "" : ev.getMemoryRegion());
        ctx.put("signature_hit", ev == null ? "" : ev.getSignatureHit());
        ctx.put("detail", ev == null ? "" : ev.getDetailJson());
        ctx.put("reputation", account != null ? account.getReputation() : 100);
        ctx.put("history_factor", historyFactor);
        return ctx;
    }

    private Dimension dimensionOf(DetectionEvent e) {
        return switch (e.getEventType() == null ? "" : e.getEventType().toLowerCase()) {
            case "memory_tamper", "process_injection", "usb_device", "signature_hit" -> Dimension.LOW_LEVEL;
            case "killaura", "aimbot", "reach", "fly_speed", "autoclicker", "auto_totem", "scaffold" -> Dimension.BEHAVIOR;
            case "debugger", "java_mod", "injection" -> Dimension.ENV;
            default -> Dimension.BEHAVIOR;
        };
    }
}