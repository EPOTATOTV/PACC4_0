package com.potatotv.pacc.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v4.1 企业级合规对外接口与面世就绪度自检。
 * <p>品牌视觉（Logo）按用户要求<b>不在此修改</b>，仅登记占位路径供后续替换。</p>
 */
@RestController
@RequestMapping("/api/admin/compliance")
@RequiredArgsConstructor
public class ComplianceController {

    /** 合规框架与合规自检项清单。 */
    @GetMapping("/selfcheck")
    public Map<String, Object> selfCheck() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("framework", Map.of(
                "pipL", "PIPL 个人信息保护法",
                "gdpr", "GDPR 通用数据保护条例",
                "dengbao", "等保三级（备案就绪）",
                "iso27001", "ISO 27001（就绪化）",
                "whql", "WHQL 驱动签名",
                "sbom", "SBOM 物料清单"
        ));
        out.put("items", List.of(
                Map.of("code", "pipL_gdpr", "name", "目的约束 / 最小化收集 / 明示同意 / 数据主体权利", "ready", true),
                Map.of("code", "encryption", "name", "全链路 TLS 1.3 + AES-256 + bcrypt/Argon2id", "ready", true),
                Map.of("code", "right_access", "name", "数据主体访问/导出（自助门户 my records）", "ready", true),
                Map.of("code", "right_erasure", "name", "删除/退出（预留接口）", "ready", false),
                Map.of("code", "right_approval", "name", "权利申诉（Appeal 工作流）", "ready", true),
                Map.of("code", "audit", "name", "操作审计（登录/查端/审批留痕）", "ready", true),
                Map.of("code", "sbom", "name", "SBOM 生成（前端 CycloneDX / 后端 Maven 依赖树）", "ready", true)
        ));
        return out;
    }

    /** SBOM 物料清单产物索引。 */
    @GetMapping("/sbom")
    public Map<String, Object> sbom() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("frontend", "deploy/sbom/frontend-cyclonedx.json（CycloneDX，npm sbom 生成）");
        out.put("backend", "deploy/sbom/backend-dependencies.txt（Maven dependency:tree）");
        out.put("regenerate_frontend", "cd ptv-frontend && npm sbom --sbom-format=cyclonedx --omit=dev > ../deploy/sbom/frontend-cyclonedx.json");
        out.put("regenerate_backend", "cd ptv-backend && mvn -s ../tools-local/maven-settings.xml dependency:tree -DoutputFile=../deploy/sbom/backend-dependencies.txt");
        out.put("cyclonedx_license", "CycloneDX 规范（AGPLv3 兼容，纯格式开放）");
        return out;
    }

    /** SLA 服务等级定义。 */
    @GetMapping("/sla")
    public Map<String, Object> sla() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("availability", "99.95%");
        out.put("false_positive_target", "≤ 0.01%");
        out.put("new_cheat_response_hours", 48);
        out.put("incident_tiers", List.of(
                Map.of("tier", "P1", "desc", "红屏误报大规模 / 服务中断", "sla", "1 小时响应"),
                Map.of("tier", "P2", "desc", "新型外挂 / 误报上升", "sla", "4 小时响应"),
                Map.of("tier", "P3", "desc", "一般咨询 / 工单", "sla", "24 小时响应")
        ));
        return out;
    }

    /** 品牌视觉占位说明（Logo 等企业外观标识预留空间，不实际替换）。 */
    @GetMapping("/branding")
    public ResponseEntity<Map<String, Object>> branding() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("note", "品牌视觉体系（科技蓝 #58A6FF + 警示红 #FF3B30、盾牌像素 Logo、思源黑体/Inter 字体）");
        out.put("placeholder", List.of(
                "ptv-frontend/index.html  <link rel=\"icon\"> + <title>",
                "ptv-frontend/src/components/Layout.tsx  LOGO 挂载点",
                "ptv-frontend/public/logo.png / favicon.ico  （待替换文件）",
                "deploy/dl-web/index.html  LOGO + 下载站页头",
                "deploy/dl-web/assets/  （待替换资源）"
        ));
        out.put("status", "PENDING_BRAND_ASSET");  // 预留: 未替换 Logo，待用户提供资产
        return ResponseEntity.ok(out);
    }
}