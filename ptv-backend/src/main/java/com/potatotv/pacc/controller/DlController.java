package com.potatotv.pacc.controller;

import com.potatotv.pacc.service.DlService;
import com.potatotv.pacc.service.EffectConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 下载站公共接口（无需登录）：下载页与端侧拉取发布物、上报下载计数。
 * 计数只累加，不做鉴权；来源可信度由网关限流与同源校验兜底。
 */
@RestController
@RequestMapping("/api/dl")
@RequiredArgsConstructor
public class DlController {

    private final DlService dlService;
    private final EffectConfigService effectConfigService;

    /** 当前启用的全部发布物。 */
    @GetMapping("/latest")
    public List<com.potatotv.pacc.domain.DlRelease> latest() {
        return dlService.latest();
    }

    /** 动效下发视图：客户端/下载站据此决定动效档位与红屏模板。 */
    @GetMapping("/effect-config")
    public Map<String, Object> effectConfig() {
        return effectConfigService.clientView();
    }

    /** 下载计数信标：下载页在触发文件下载时上报。 */
    @PostMapping("/track")
    public Map<String, Object> track(@RequestBody Map<String, Object> body) {
        String platform = body.get("platform") == null ? "" : body.get("platform").toString();
        String artifact = body.get("artifact") == null ? "" : body.get("artifact").toString();
        dlService.track(platform, artifact);
        return Map.of("ok", true);
    }
}