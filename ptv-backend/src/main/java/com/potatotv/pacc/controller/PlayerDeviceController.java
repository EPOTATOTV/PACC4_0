package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.DeviceRecord;
import com.potatotv.pacc.domain.Peripheral;
import com.potatotv.pacc.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 玩家自助门户：登录设备与使用过的外设（当前使用 / 未使用）。
 * 需要玩家 JWT（JwtAuthFilter 已校验 Subject 与 PTEID 一致性）。
 */
@RestController
@RequestMapping("/api/player")
@RequiredArgsConstructor
@SuppressWarnings("null")
public class PlayerDeviceController {

    private final AccountService accountService;

    /** 登录设备列表（当前使用优先）。 */
    @GetMapping("/devices")
    public List<DeviceRecord> devices(HttpServletRequest req) {
        return accountService.listDevices(req.getAttribute("pteid").toString());
    }

    /** 使用过的外设列表（使用中优先）。 */
    @GetMapping("/peripherals")
    public List<Peripheral> peripherals(HttpServletRequest req) {
        return accountService.listPeripherals(req.getAttribute("pteid").toString());
    }

    /** 客户端上报当前设备使用中的外设。 */
    @PostMapping("/peripherals/report")
    public ResponseEntity<?> reportPeripherals(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String pteid = req.getAttribute("pteid").toString();
        @SuppressWarnings("unchecked")
        var inputs = (List<Map<String, String>>) body.getOrDefault("peripherals", List.of());
        // 归属到当前使用的设备指纹
        String activeFp = accountService.listDevices(pteid).stream()
                .filter(DeviceRecord::isActive)
                .map(DeviceRecord::getDeviceFingerprint)
                .findFirst()
                .orElse(null);
        accountService.reportPeripherals(pteid, activeFp, inputs);
        return ResponseEntity.ok(Map.of("updated", inputs.size()));
    }
}