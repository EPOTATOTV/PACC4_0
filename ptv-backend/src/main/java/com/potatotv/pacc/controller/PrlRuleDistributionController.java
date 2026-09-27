package com.potatotv.pacc.controller;

import com.potatotv.pacc.rule.PrlRuleReleaseService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家端规则下发端点（设计文档 §3.2.4）：清单 + 单条源码下载。
 *
 * <p>结构与 v5.2 的模型下发（{@code V52ModelController}）保持一致：清单给「版本 + SHA-256 + 下载地址」，
 * 端侧比对本地记录后按需下载，服务端已按灰度分桶决定下发哪个版本，客户端不做任何灰度判断。</p>
 *
 * <p>这里下发的是 PRL <strong>源码</strong>而不是编译产物：{@code PrlcFormat} 的字节码格式版本
 * （{@code PrlBytecode.FORMAT_VERSION}）与引擎绑定，源码则天然跨版本可读，端侧自带编译器。
 * 代价是端侧多一次编译，换来的是「规则包能跟着引擎一起升级」。</p>
 *
 * <p>鉴权沿用玩家会话约定：{@code JwtAuthFilter} 对 {@code /api/player/**} 无令牌直接 401，
 * 通过后把 PTEID 写入请求属性；此处仍显式判空，避免过滤器链路变更时静默放行。</p>
 */
@RestController
@RequestMapping("/api/player/rules")
@RequiredArgsConstructor
public class PrlRuleDistributionController {

    private final PrlRuleReleaseService releaseService;

    /** 当前玩家应装载的规则清单。 */
    @GetMapping("/manifest")
    public ResponseEntity<?> manifest(HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "需要登录"));
        }
        List<Map<String, Object>> rules = new ArrayList<>();
        for (PrlRuleReleaseService.RuleDelivery delivery : releaseService.deliveriesFor(pteid)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", delivery.name());
            m.put("version", delivery.version());
            m.put("sha256", delivery.checksum());
            m.put("status", delivery.status());
            m.put("url", "/api/player/rules/" + delivery.name() + "/file");
            rules.add(m);
        }
        return ResponseEntity.ok(Map.of("rules", rules));
    }

    /** 下载当前玩家应装载的某条规则的源码。 */
    @GetMapping("/{name}/file")
    public ResponseEntity<?> file(@PathVariable String name, HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "需要登录"));
        }
        PrlRuleReleaseService.RuleDelivery delivery = releaseService.deliveriesFor(pteid).stream()
                .filter(item -> item.name().equals(name))
                .findFirst()
                .orElse(null);
        if (delivery == null) {
            // 两种情况都归 404：规则不存在，或这台设备不在该版本的灰度范围内 —— 后者不该让端侧
            // 去猜「是不是被灰度排除了」，拿不到就是拿不到，下一轮同步再问。
            return ResponseEntity.status(404).body(Map.of("error", "当前设备没有可下载的规则版本：" + name));
        }
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .header("X-PACC-Rule-Name", safeHeader(delivery.name()))
                .header("X-PACC-Rule-Version", safeHeader(delivery.version()))
                .header("X-PACC-Rule-SHA256", safeHeader(delivery.checksum()))
                .cacheControl(CacheControl.noStore())
                .body(delivery.source());
    }

    /**
     * 响应头里的版本号来自管理端输入，必须过滤成字符白名单。
     * 不做这一步，一个含 CRLF 的版本号就能往响应里插头（版本号目前没有任何格式校验）。
     */
    private static String safeHeader(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[^0-9A-Za-z._-]", "_");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    private static String pteidOf(HttpServletRequest req) {
        Object value = req.getAttribute("pteid");
        return value == null ? "" : value.toString();
    }
}