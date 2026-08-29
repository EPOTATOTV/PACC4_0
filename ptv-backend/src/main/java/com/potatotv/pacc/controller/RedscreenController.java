package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.RedscreenAlert;
import com.potatotv.pacc.repository.RedscreenAlertRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 红屏管理接口：查询历史红屏事件列表。
 */
@RestController
@RequestMapping("/api/admin/redscreens")
@RequiredArgsConstructor
public class RedscreenController {

    private final RedscreenAlertRepository alertRepository;

    @GetMapping
    public List<RedscreenAlert> list(@RequestParam(defaultValue = "PENDING_INSPECT") String state) {
        return alertRepository.findByStateOrderByOccurredAtDesc(state);
    }
}