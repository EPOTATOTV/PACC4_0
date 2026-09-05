package com.potatotv.pacc.service.sms;

/**
 * 短信验证码发送提供方。真实服务（阿里云/腾讯云短信）由环境变量注入并切换实现；
 * 当前仅内置演示 stub，避免缺密钥且规避群发短信风险。
 */
public interface SmsProvider {

    void send(String phone, String code);
}