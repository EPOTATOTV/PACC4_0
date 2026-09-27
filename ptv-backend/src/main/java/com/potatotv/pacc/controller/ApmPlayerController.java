package com.potatotv.pacc.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.service.apm.ApmIngestService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v5.4 §2.2 APM 玩家端批量上报入口（受玩家 JWT 保护）。
 *
 * <p>契约：{@code POST /api/player/apm/batch}，body 形如
 * <pre>{"client_version":"5.4.0","platform":"WIN",
 *  "samples":[{"metric_time":1758000000000,"name":"sys_cpu_total","value":12.3,
 *              "type":"gauge","tags":{"game_version":"1.20"}}]}</pre>
 * 返回 {@code {"accepted":N,"rejected":M}}。</p>
 *
 * <p>两点约定：{@code metric_time} 同时接受 epoch 毫秒（数字）与 ISO-8601 字符串——
 * 端侧不同语言栈拿到的时钟类型不一样，服务端统一在这里归一；{@code tags} 允许直接传对象，
 * 由服务端序列化成字符串存库（端侧不必先自己 JSON 化）。玩家身份一律取自会话属性 {@code pteid}，
 * 不接受请求体自述，否则任何人都能伪造别人的指标。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/player/apm")
@RequiredArgsConstructor
public class ApmPlayerController {

    /** tags 对象序列化用（只做写，不需要 Spring 容器里的那份配置）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApmIngestService ingestService;

    /** 批量上报 APM 采样。 */
    @PostMapping("/batch")
    public ResponseEntity<?> batch(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "需要登录"));
        }
        String clientVersion = str(body.get("client_version"), body.get("clientVersion"));
        if (clientVersion.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "client_version required"));
        }
        String platform = str(body.get("platform"), null);

        ApmIngestService.IngestResult result = ingestService.ingestDetailed(
                pteid, clientVersion, platform, samplesOf(body.get("samples")));
        if (result.rejected() > 0) {
            // 只记数量不记明细：拒绝原因多为未登记指标名，逐条打日志会被长尾名字刷屏
            log.warn("APM 上报存在被丢弃采样 pteid={} 版本={} 平台={} 接受={} 丢弃={}",
                    pteid, clientVersion, platform, result.accepted(), result.rejected());
        }
        return ResponseEntity.ok(Map.of("accepted", result.accepted(), "rejected", result.rejected()));
    }

    /** 解析 samples 数组；非对象元素保留一条空采样占位，好让丢弃数如实计入 rejected。 */
    private static List<ApmIngestService.Sample> samplesOf(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<ApmIngestService.Sample> samples = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                samples.add(new ApmIngestService.Sample(null, "", Double.NaN, null, null));
                continue;
            }
            Object value = map.get("value") != null ? map.get("value") : map.get("metric_value");
            Double numeric = dbl(value);
            samples.add(new ApmIngestService.Sample(
                    parseTime(map.get("metric_time") != null ? map.get("metric_time") : map.get("metricTime")),
                    str(map.get("name"), map.get("metric_name")),
                    // 缺失的值用 NaN 占位，由服务层统一按非法值丢弃并计数
                    numeric == null ? Double.NaN : numeric,
                    str(map.get("type"), map.get("metric_type")),
                    tagsOf(map.get("tags"))));
        }
        return samples;
    }

    /** metric_time 归一：数字按 epoch 毫秒，字符串按 ISO-8601（也兼容纯数字字符串）。 */
    private static Instant parseTime(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return Instant.ofEpochMilli(n.longValue());
        String text = value.toString().trim();
        if (text.isEmpty()) return null;
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (Exception ignored) {
            // 继续尝试下面两种写法
        }
        try {
            return Instant.parse(text);
        } catch (Exception ignored) {
            // 继续尝试 epoch 毫秒字符串
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(text));
        } catch (Exception e) {
            return null;
        }
    }

    /** tags 归一：对象序列化为 JSON 字符串，字符串原样透传。 */
    private static String tagsOf(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> map) {
            try {
                return MAPPER.writeValueAsString(map);
            } catch (Exception e) {
                // 序列化失败不阻塞入库，退化成字符串形式（服务层仍会截断长度）
                return map.toString();
            }
        }
        return value.toString();
    }

    private static String pteidOf(HttpServletRequest req) {
        Object v = req.getAttribute("pteid");
        return v == null ? "" : v.toString();
    }

    private static String str(Object primary, Object fallback) {
        Object v = primary != null ? primary : fallback;
        return v == null ? "" : v.toString();
    }

    private static Double dbl(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}