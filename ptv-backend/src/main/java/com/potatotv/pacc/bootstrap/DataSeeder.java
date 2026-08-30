package com.potatotv.pacc.bootstrap;

import com.potatotv.pacc.service.AccountService;
import com.potatotv.pacc.service.CompetitionService;
import com.potatotv.pacc.service.SignatureLibraryService;
import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.domain.Enrollment;
import com.potatotv.pacc.domain.Signature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    private final CompetitionService competitionService;

    @Override
    public void run(String... args) {
        if (!"true".equalsIgnoreCase(System.getenv("PACC_SEED"))) {
            log.info("跳过演示数据初始化（设置 PACC_SEED=true 可开启）");
            return;
        }
        try {
            seedAccounts();
            seedDevices();
            seedSignatures();
            seedTournament();
        } catch (Exception e) {
            log.warn("演示数据初始化出现异常（可忽略）: {}", e.getMessage());
        }
    }

    private void seedDevices() {
        // 为第一个演示账号预置登录设备（一台当前使用 + 历史未使用设备）+ 若干外设
        Account acc = accountService.findByEmailOrNull("player@ptv.dev");
        if (acc == null) return;
        String pteid = acc.getPteid();
        // 历史设备先上报（保持非当前），最后上报当前设备（最后上报者标记为当前使用）
        accountService.touchDevice(pteid, "old-device-fp-a-" + pteid);
        accountService.touchDevice(pteid, "old-device-fp-b-" + pteid);
        accountService.touchDevice(pteid, "main-device-fp-" + pteid);

        // 当前设备接入的外设（使用中）
        accountService.reportPeripherals(pteid, "main-device-fp-" + pteid, List.of(
                Map.of("kind", "mouse", "vendor", "Razer", "model", "DeathAdder V2"),
                Map.of("kind", "keyboard", "vendor", "Logitech", "model", "G Pro X"),
                Map.of("kind", "headset", "vendor", "HyperX", "model", "Cloud II"),
                Map.of("kind", "gamepad", "vendor", "5B", "model", "Elite Series 2")
        ));
        // 该设备上次使用但现已拔出的外设（未使用）
        accountService.reportPeripherals(pteid, "main-device-fp-" + pteid, List.of(
                Map.of("kind", "mouse", "vendor", "Razer", "model", "DeathAdder V2"),
                Map.of("kind", "keyboard", "vendor", "Logitech", "model", "G Pro X")
        ));

        // 历史设备上的外设（未使用）
        accountService.reportPeripherals(pteid, "old-device-fp-a-" + pteid, List.of(
                Map.of("kind", "usb_storage", "vendor", "Kingston", "model", "DT 100 G3")
        ));
        log.info("演示设备与外设初始化完成");
    }

    private void seedAccounts() {
        // email, phone, mcid, ecid, qq, neteaseUuid, password, device-fp
        String[][] accounts = {
                {"player@ptv.dev", "13800138001", "PlayerOne", "ECID005001", "1001001", "1a2b3c4d-5e6f-7a8b-9c0d-123456789abc", "DemoPass123!", "demo-device-001"},
                {"demo@ptv.dev", "13800138002", "DemoTwo", "ECID005002", "1001002", null, "DemoPass123!", "demo-device-002"},
                {"ops@ptv.dev", "13800138003", "OpsThree", "ECID005003", "1001003", null, "DemoPass123!", "demo-device-003"},
        };
        for (String[] a : accounts) {
            try {
                accountService.register(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]);
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

    /** 演示赛事数据：报名/队伍/赛程/公告，供「赛事进程 → 可视化」页展示。 */
    private void seedTournament() {
        String tid = "demo-tournament";
        try {
            competitionService.updateConfig(tid, "PACC 2026 秋季锦标赛",
                    "https://docs.qq.com/form/pacc-2026-fall",
                    Instant.now().plusSeconds(60L * 60 * 24 * 7), true, "每周五晚开赛，赛制详见公告");
        } catch (Exception ignored) {
        }
        // 12 名获批报名分布在 4 支队伍
        String[][] teams = {
                {"PT1001", "Alpha", "红队", "#e6194b"},
                {"PT1002", "Bravo", "红队", "#e6194b"},
                {"PT1003", "Charlie", "红队", "#e6194b"},
                {"PT1004", "Delta", "红队", "#e6194b"},
                {"PT1005", "Echo", "蓝队", "#3b82f6"},
                {"PT1006", "Foxtrot", "蓝队", "#3b82f6"},
                {"PT1007", "Golf", "蓝队", "#3b82f6"},
                {"PT1008", "Hotel", "绿队", "#22c55e"},
                {"PT1009", "India", "绿队", "#22c55e"},
                {"PT1010", "Juliet", "绿队", "#22c55e"},
                {"PT1011", "Kilo", "金队", "#eab308"},
                {"PT1012", "Lima", "金队", "#eab308"},
        };
        for (String[] t : teams) {
            try {
                Enrollment e = competitionService.enroll(tid, t[0], t[1],
                        "demo-device-" + t[0], "seed");
                competitionService.approve(e.getEnrollmentId(), true, "seed", "资料齐全");
                competitionService.setTeam(e.getEnrollmentId(), t[2], t[3]);
            } catch (Exception ignored) {
            }
        }
        // 2 名待审批 + 1 名已拒绝样本
        try {
            competitionService.enroll(tid, "PT1013", "Mike", "demo-device-PT1013", "seed");
            competitionService.enroll(tid, "PT1014", "November", "demo-device-PT1014", "seed");
            Enrollment r = competitionService.enroll(tid, "PT1015", "Oscar", "demo-device-PT1015", "seed");
            competitionService.approve(r.getEnrollmentId(), false, "seed", "设备指纹与报名信息不符");
        } catch (Exception ignored) {
        }
        // 赛程阶段
        try {
            competitionService.addStage(tid, "线上资格赛（BO1 积分循环）", "QUALIFIER", "DONE",
                    Instant.now().minusSeconds(60L * 60 * 24 * 10),
                    Instant.now().minusSeconds(60L * 60 * 24 * 3), "前 16 名晋级小组赛");
            competitionService.addStage(tid, "小组赛（BO3）", "GROUP", "ACTIVE",
                    Instant.now().minusSeconds(60L * 60 * 24 * 2),
                    Instant.now().plusSeconds(60L * 60 * 24 * 5), "四组各取前二");
            competitionService.addStage(tid, "淘汰赛（BO5）", "KNOCKOUT", "PENDING",
                    Instant.now().plusSeconds(60L * 60 * 24 * 6),
                    Instant.now().plusSeconds(60L * 60 * 24 * 9), "八强单败淘汰");
            competitionService.addStage(tid, "总决赛（BO7）", "FINAL", "PENDING",
                    Instant.now().plusSeconds(60L * 60 * 24 * 10),
                    Instant.now().plusSeconds(60L * 60 * 24 * 12), "线下场馆");
        } catch (Exception ignored) {
        }
        // 公告
        try {
            competitionService.publishNotice(tid, "2026 秋季锦标赛报名开启",
                    "报名截止至本周日 23:59。请完成客户端安装与设备绑定后提交参赛申请，未绑定设备将无法通过门禁。",
                    true, "seed");
            competitionService.publishNotice(tid, "赛制说明",
                    "资格赛为线上积分循环，小组赛 BO3、淘汰赛 BO5、总决赛 BO7。所有对局须通过赛事客户端入场校验。",
                    false, "seed");
        } catch (Exception ignored) {
        }
        log.info("演示赛事数据初始化完成（{}）", tid);
    }
}