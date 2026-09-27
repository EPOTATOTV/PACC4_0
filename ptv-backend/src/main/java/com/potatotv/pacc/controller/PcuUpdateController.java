package com.potatotv.pacc.controller;

import com.potatotv.pacc.service.PcuUpdateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * PCU（跨平台更新模块）端侧公共接口（设计文档 §4.11）：端侧自动更新用，无登录态。
 *
 * <p>端侧检查更新发生在登录之前，因此这两个接口不挂任何鉴权；pteid 只在可取得时携带，仅作统计。
 * 路由固定为 {@code /v1/update/...}（对齐设计文档与端侧 {@code UpdateChecker}/{@code UpdateReporter} 写死的路径）；
 * 后端没有 server.servlet.context-path，也没有网关前缀改写，故真实路径即 {@code /v1/update/check} 与
 * {@code /v1/update/report}。返回体沿用裸 Map（与 DlController 等玩家端公共接口一致），不引入统一包装。</p>
 */
@RestController
@RequestMapping("/v1/update")
@RequiredArgsConstructor
public class PcuUpdateController {

    private final PcuUpdateService pcuUpdateService;

    /** 检查更新；platform / current_version / channel / pteid 全部可选。 */
    @GetMapping("/check")
    public Map<String, Object> check(@RequestParam(required = false) String platform,
                                     @RequestParam(name = "current_version", required = false) String currentVersion,
                                     @RequestParam(required = false) String channel,
                                     @RequestParam(required = false) String pteid) {
        return pcuUpdateService.check(platform, currentVersion, channel, pteid);
    }

    /** 上报更新结果；字段缺失按缺省处理，始终返回 ok，不让端侧因上报体残缺而失败。 */
    @PostMapping("/report")
    public Map<String, Object> report(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        pcuUpdateService.report(str(b.get("pteid")), str(b.get("platform")),
                str(b.get("from_version")), str(b.get("to_version")),
                str(b.get("status")), str(b.get("error_message")));
        return Map.of("ok", true);
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}