package com.potatotv.pacc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PACC v5.0 PTV 管控后端入口。
 * <p>职责：玩家端账号体系、检测事件处理、多维风险评分、红屏警告、全在线广播、
 * 远程查端会话、永久作弊记录、特征库管理、统计大盘、Webhook 推送。</p>
 * <p>v5.4 起启用定时任务：APM 小时聚合与原始数据清理、APM 告警评估、
 * 托管密钥到期回收（见 {@code pacc.apm.*} 与 {@code pacc.security.key-rotation-days}）。</p>
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class PaccApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaccApplication.class, args);
    }
}