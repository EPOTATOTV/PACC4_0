package com.potatotv.pacc.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.domain.DetectionEvent;
import com.potatotv.pacc.service.AccountService;
import com.potatotv.pacc.service.OnlineStatusService;
import com.potatotv.pacc.service.RedscreenService;
import com.potatotv.pacc.service.RiskScoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.Map;

/**
 * 玩家端 PTV 长连接处理器：
 * <ul>
 *   <li>接收玩家端上报的检测事件（心跳 / 底层检测 / 行为检测 / 环境检测 / AI 模型分）</li>
 *   <li>实时计算风险分并交由红屏服务决策是否触发红屏</li>
 *   <li>此连接不承载任何游戏通信，仅用于反作弊数据与控制指令</li>
 *   <li>采用 Spring {@code @NonNull} 契约，参数空值由框架保证</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlayerWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper mapper;
    private final OnlineStatusService onlineStatusService;
    private final RiskScoringService riskScoringService;
    private final RedscreenService redscreenService;
    private final AccountService accountService;

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        Map<String, Object> attrs = session.getAttributes();
        String pteid = (String) attrs.get("pteid");
        String edition = (String) attrs.getOrDefault("edition", "UNKNOWN");
        onlineStatusService.register(pteid, session);
        log.info("PTV 玩家端上线 pteid={} edition={} session={}", pteid, edition, session.getId());
        send(session, Map.of("type", "hello", "ts", Instant.now().toString(),
                "redscreen_enabled", true, "wss_secure", "TLS1.3"));
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        String pteid = (String) session.getAttributes().get("pteid");
        String edition = (String) session.getAttributes().getOrDefault("edition", "UNKNOWN");
        try {
            JsonNode node = mapper.readTree(message.getPayload());
            String type = node.path("type").asText();
            switch (type) {
                case "ping" -> send(session, Map.of("type", "pong", "ts", Instant.now().toString()));
                case "event" -> handleEvent(pteid, edition, node);
                default -> log.debug("未知消息类型 type={} pteid={}", type, pteid);
            }
        } catch (Exception e) {
            log.warn("解析玩家消息失败 pteid={} err={}", pteid, e.getMessage());
        }
    }

    private void handleEvent(String pteid, String edition, JsonNode node) {
        try {
            String eventType = node.path("event_type").asText("signature_hit");
            int clientRisk = node.path("client_risk_score").asInt(0);
            String severity = node.path("severity").asText("low");
            String detail = node.path("detail").asText("");
            DetectionEvent.Edition gameEdition;
            try {
                gameEdition = DetectionEvent.Edition.valueOf(edition.toUpperCase());
            } catch (Exception e) {
                gameEdition = DetectionEvent.Edition.BEDROCK;
            }

            DetectionEvent event = DetectionEvent.builder()
                    .id(java.util.UUID.randomUUID().toString())
                    .pteid(pteid)
                    .eventType(eventType)
                    .severity(severity)
                    .edition(gameEdition)
                    .clientRiskScore(clientRisk)
                    .evidence(new DetectionEvent.Evidence(
                            node.path("process_name").asText(null),
                            node.path("memory_region").asText(null),
                            node.path("signature_hit").asText(null),
                            detail))
                    .clientVersion(node.path("client_version").asText(null))
                    .osInfo(node.path("os_info").asText(null))
                    .occurredAt(Instant.now())
                    .build();

            Account account = accountService.findByPteidOrNull(pteid);
            int score = riskScoringService.score(event, account);
            accountService.saveEvent(event);

            // 触发红屏决策（含冷却去重 + 全服广播 + 查端排队）
            redscreenService.decideAndHandle(pteid, cheatTypeOf(eventType), score, edition, detail);

            // 高威胁可下发客户端就地防护指令
            if (score >= 70) {
                send(sessionOf(pteid), Map.of("type", "mitigation",
                        "action", "force_close", "reason", detail, "score", score));
            }
        } catch (Exception e) {
            log.warn("处理检测事件失败 pteid={} err={}", pteid, e.getMessage());
        }
    }

    private WebSocketSession sessionOf(String pteid) {
        // 通过在线状态服务找到该 PTEID 对应的首个会话
        return onlineStatusService.firstSession(pteid).orElse(null);
    }

    private String cheatTypeOf(String eventType) {
        return switch (eventType == null ? "" : eventType.toLowerCase()) {
            case "killaura", "aimbot", "reach", "scaffold", "autoclicker" -> "AIM_ASSIST";
            case "debugger", "java_mod", "injection" -> "ENV_TAMPER";
            default -> "PRIVILEGED_ACCESS";
        };
    }

    private void send(WebSocketSession session, Map<String, Object> payload) {
        if (session == null || !session.isOpen()) return;
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
            }
        } catch (Exception e) {
            log.warn("发送失败 session={} err={}", session.getId(), e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        String pteid = (String) session.getAttributes().get("pteid");
        if (pteid != null) {
            onlineStatusService.unregister(pteid, session);
            log.info("PTV 玩家端下线 pteid={} session={}", pteid, session.getId());
        }
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
        log.warn("PTV 传输异常 session={} err={}", session.getId(), exception.getMessage());
        try { session.close(CloseStatus.SERVER_ERROR); } catch (Exception ignored) { }
    }
}