package com.potatotv.pacc.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.domain.DetectionEvent;
import com.potatotv.pacc.proto.PaccWire;
import com.potatotv.pacc.service.AccountService;
import com.potatotv.pacc.service.InspectSignalBus;
import com.potatotv.pacc.service.MapBpEventBus;
import com.potatotv.pacc.service.OnlineStatusService;
import com.potatotv.pacc.service.RedscreenService;
import com.potatotv.pacc.service.RiskScoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

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
public class PlayerWebSocketHandler extends AbstractWebSocketHandler {

    private final ObjectMapper mapper;
    private final OnlineStatusService onlineStatusService;
    private final RiskScoringService riskScoringService;
    private final RedscreenService redscreenService;
    private final AccountService accountService;
    private final WssMessageGuard messageGuard;
    private final InspectSignalBus inspectSignalBus;
    private final MapBpEventBus mapBpEventBus;
    private final PaccWireCodec paccWireCodec;
    private final WssSessionKeys sessionKeys;

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
            // 完整性校验：签名 + 时间窗 + nonce 防重放（未启用签名时放行）
            if (!messageGuard.verify(pteid, node)) {
                log.warn("PTV 消息校验失败（可能抓包重放/篡改）pteid={} type={} session={}",
                        pteid, type, session.getId());
                send(session, Map.of("type", "error", "code", "BAD_SIGNATURE"));
                return;
            }
            switch (type) {
                case "ping" -> send(session, Map.of("type", "pong", "ts", Instant.now().toString()));
                case "event" -> handleEvent(pteid, edition, node);
                // 地图 BP 实时订阅
                case "bp_subscribe" -> {
                    String bpId = node.path("bp_session_id").asText(null);
                    if (bpId != null && !bpId.isBlank()) {
                        mapBpEventBus.subscribe(bpId, session);
                        send(session, Map.of("type", "bp_subscribed", "bp_session_id", bpId));
                    }
                }
                case "bp_unsubscribe" -> {
                    String bpId = node.path("bp_session_id").asText(null);
                    if (bpId != null) mapBpEventBus.unsubscribe(bpId, session);
                }
                // 远程查端信令：玩家端取证的 started/forensics 及 B2 透传的 offer/answer/ice 原样转给管理端
                case "inspect_started", "inspect_forensics", "inspect_offer",
                        "inspect_answer", "inspect_ice", "inspect_ready" -> {
                    boolean forwarded = inspectSignalBus.forwardPlayerToAdmin(session, message.getPayload());
                    log.info("玩家查端信令 {} pteid={} forwarded={} session={}", type, pteid, forwarded, session.getId());
                }
                default -> log.debug("未知消息类型 type={} pteid={}", type, pteid);
            }
        } catch (Exception e) {
            log.warn("解析玩家消息失败 pteid={} err={}", pteid, e.getMessage());
        }
    }

    /**
     * 二进制帧 = protobuf 查端信令信封：验签后转发给管理端信号总线；另有两条会话密钥控制消息在此处理。
     * <p>密钥流程：
     * <ul>
     *   <li>session_init（v1 静态密钥引导）→ 派生并登记会话，回 session_ready（v2 会话密钥）；</li>
     *   <li>session_rekey（v2 会话密钥）→ 链式轮换，回 session_ack；</li>
     *   <li>其余 inspect_*（v2 会话密钥）→ 验签后转发。</li>
     * </ul>
     * 会话密钥建立前，任何 inspect_* 都进不来（强制模式 v1 被拒；非强制模式下旧客户端仍可用 v1）。</p>
     */
    @Override
    protected void handleBinaryMessage(@NonNull WebSocketSession session, @NonNull BinaryMessage message) {
        String pteid = (String) session.getAttributes().get("pteid");
        try {
            PaccWire.WsEnvelope env = paccWireCodec.parse(message.getPayload().array());
            String type = env.getType();
            if (!paccWireCodec.verify(env)) {
                log.warn("信封校验失败（可能抓包重放/篡改/会话密钥失效）pteid={} type={} session={}",
                        pteid, type, session.getId());
                return;
            }
            if (WssSessionKeys.INIT_TYPE.equals(type)) {
                // 用静态密钥（v1）引导握手的初帧：必须在会话建立前可验，故由 verify 的 v1 分支放行
                String sid = env.getSessionId();
                String salt = parseSalt(env.getPayloadJson());
                if (sid == null || sid.isBlank()) {
                    log.warn("session_init 缺 session_id pteid={}", pteid);
                    return;
                }
                if (salt == null) {
                    log.warn("session_init 缺盐或盐非法 pteid={} sid={}", pteid, sid);
                    return;
                }
                WssSessionKeys.Session reg = sessionKeys.open(sid, salt, pteid, paccWireCodec.staticSecret());
                if (reg == null) {
                    log.warn("session_init 被拒（非法盐或会话超限）pteid={} sid={}", pteid, sid);
                    return;
                }
                // 记下本连接的会话密钥 id，断线时据此销毁密钥
                session.getAttributes().put("paccSessionId", sid);
                String readyPayload = "{\"keyId\":\"" + sid + "\"}";
                session.sendMessage(new BinaryMessage(
                        paccWireCodec.buildWithSessionKey(WssSessionKeys.READY_TYPE, sid, pteid,
                                readyPayload, reg.keyHex()).toByteArray()));
                log.info("WSS 会话密钥已建立 pteid={} sid={} session={}", pteid, sid, session.getId());
                return;
            }
            if (WssSessionKeys.REKEY_TYPE.equals(type)) {
                long epoch = parseEpoch(env.getPayloadJson());
                WssSessionKeys.Session rotated = sessionKeys.rotate(env.getSessionId(), epoch);
                if (rotated == null) {
                    log.warn("session_rekey epoch 校验失败，拒绝轮换 pteid={} sid={}", pteid, env.getSessionId());
                    return;
                }
                session.sendMessage(new BinaryMessage(
                        paccWireCodec.buildWithSessionKey(WssSessionKeys.ACK_TYPE, env.getSessionId(), pteid,
                                "{\"epoch\":" + rotated.epoch() + "}", rotated.keyHex()).toByteArray()));
                log.info("WSS 会话密钥已轮换 pteid={} sid={} epoch={}", pteid, env.getSessionId(), rotated.epoch());
                return;
            }
            if (type == null || !type.startsWith("inspect_")) {
                log.debug("二进制信封忽略 type={} pteid={}", type, pteid);
                return;
            }
            boolean forwarded = inspectSignalBus.forwardPlayerToAdmin(session, env.getPayloadJson());
            log.info("查端信令(protobuf) {} pteid={} forwarded={} session={}", type, pteid, forwarded, session.getId());
        } catch (Exception e) {
            log.warn("解析二进制信封失败 pteid={} err={}", pteid, e.getMessage());
        }
    }

    /** 从 {@code {"salt":"..."}} 取盐；也容忍直接把裸十六进制盐当 payload 的写法。 */
    private String parseSalt(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return null;
        String s = payloadJson.trim();
        if (s.startsWith("{")) {
            try {
                String v = mapper.readTree(s).path("salt").asText("");
                return v.isBlank() ? null : v;
            } catch (Exception e) {
                return null;
            }
        }
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** 从 {@code {"epoch":N}} 提取当前 epoch；解析失败回 0（与 epoch 起始一致，双保险）。 */
    private long parseEpoch(String payloadJson) {
        try {
            var n = mapper.readTree(payloadJson == null ? "{}" : payloadJson).path("epoch");
            return n.asLong(0);
        } catch (Exception e) {
            return 0L;
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
            String json = mapper.writeValueAsString(payload);
            if (json == null) return; // 序列化异常兜底
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
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
        // 立即销毁本连接的会话密钥：连接已断，密钥再留着只是徒增泄露面。
        // 重连时客户端会重新走 session_init 派生新盐、新密钥（不复用旧密钥）。
        Object sid = session.getAttributes().get("paccSessionId");
        if (sid instanceof String s && !s.isBlank()) {
            sessionKeys.close(s);
        }
        mapBpEventBus.onDisconnect(session);
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
        log.warn("PTV 传输异常 session={} err={}", session.getId(), exception.getMessage());
        try {
            CloseStatus closeStatus = CloseStatus.SERVER_ERROR;
            if (closeStatus != null) {
                session.close(closeStatus);
            }
        } catch (Exception ignored) {
            // 关闭失败忽略
        }
        mapBpEventBus.onDisconnect(session);
    }
}