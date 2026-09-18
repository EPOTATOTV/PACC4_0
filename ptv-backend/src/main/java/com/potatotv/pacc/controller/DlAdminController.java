package com.potatotv.pacc.controller;

import com.potatotv.pacc.service.DlService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 下载站数据管理接口：下载趋势与发布物列表视图。受 X-Admin-Key 保护。
 */
@RestController
@RequestMapping("/api/admin/dl")
@RequiredArgsConstructor
public class DlAdminController {

    private final DlService dlService;

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(defaultValue = "14") int days) {
        return dlService.adminStats(Math.min(Math.max(days, 1), 90));
    }

    @GetMapping("/releases")
    public Object releases() {
        return dlService.latest();
    }
}