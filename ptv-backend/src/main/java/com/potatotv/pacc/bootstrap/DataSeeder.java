package com.potatotv.pacc.bootstrap;

import com.potatotv.pacc.service.AccountService;
import com.potatotv.pacc.service.SignatureLibraryService;
import com.potatotv.pacc.domain.Signature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 开发环境演示数据：预置若干反作弊账号与特征库样本。
 * 生产环境应禁用（通过配置文件控制）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final AccountService accountService;
    private final SignatureLibraryService signatureLibraryService;

    @Override
    public void run(String... args) {
        if (!"true".equalsIgnoreCase(System.getenv("PACC_SEED"))) {
            log.info("跳过演示数据初始化（设置 PACC_SEED=true 可开启）");
            return;
        }
        try {
            seedAccounts();
            seedSignatures();
        } catch (Exception e) {
            log.warn("演示数据初始化出现异常（可忽略）: {}", e.getMessage());
        }
    }

    private void seedAccounts() {
        String[][] accounts = {
                {"player@ptv.dev", "13800138001", "DemoPass123", "demo-device-001"},
                {"demo@ptv.dev", "13800138002", "DemoPass123", "demo-device-002"},
                {"ops@ptv.dev", "13800138003", "DemoPass123", "demo-device-003"},
        };
        for (String[] a : accounts) {
            try {
                accountService.register(a[0], a[1], a[2], a[3]);
            } catch (Exception ignored) {
                // 已存在则跳过
            }
        }
        log.info("演示账号初始化完成");
    }

    private void seedSignatures() {
        String[][] sigs = {
                {"KillAura/AutoSwing 内存特征", "E8 ?? ?? ?? ?? 49 8D 55", "5", "JAVA"},
                {"Reach/Jesus 指针链", "48 8B 05 ?? ?? ?? ?? 74 2B", "4", "JAVA"},
                {"基岩版 Fly 本地变量", "0F 5B C0 C1 E0 02 D1 E8", "4", "BEDROCK"},
                {"基岩版 KillAura 模式", "C7 45 FC 01 00 00 00 E8", "5", "BEDROCK"},
        };
        for (String[] s : sigs) {
            try {
                signatureLibraryService.add(s[0], s[1], Integer.parseInt(s[2]),
                        Signature.Edition.valueOf(s[3]), "v4.0.0", "system-seed");
            } catch (Exception ignored) {
            }
        }
        log.info("演示特征库初始化完成");
    }
}