package com.potatotv.pacc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * PACC v4.0 PTV 管控后端入口。
 * <p>职责：玩家端账号体系、检测事件处理、多维风险评分、红屏警告、全在线广播、
 * 远程查端会话、永久作弊记录、特征库管理、统计大盘、Webhook 推送。</p>
 */
@EnableAsync
@SpringBootApplication
public class PaccApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaccApplication.class, args);
    }
}