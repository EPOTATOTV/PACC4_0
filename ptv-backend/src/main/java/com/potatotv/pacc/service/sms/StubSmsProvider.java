package com.potatotv.pacc.service.sms;

import org.springframework.stereotype.Component;

/** 演示版短信发送：本地联调在日志打印验证码，不真连运营商。 */
@Component
public class StubSmsProvider implements SmsProvider {

    @Override
    public void send(String phone, String code) {
        System.out.println("[短信stub] 发往 " + phone + " 的验证码=" + code);
    }
}