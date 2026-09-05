package com.potatotv.pacc.service;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * v4.4 邮件通知服务（复用 AuthController 既有 SMTP 范式）。
 * <p>stub-enabled（本地联调）或 SMTP 未配置时仅记录邮件意图，不实际发送；生产必须配置真实 SMTP。</p>
 */
@Slf4j
@Service
public class MailerService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final boolean stubEnabled;
    private final String mailSmtpFrom;
    private final String mailUsername;

    public MailerService(ObjectProvider<JavaMailSender> mailSenderProvider,
                         @Value("${pacc.mail.stub-enabled:false}") boolean stubEnabled,
                         @Value("${spring.mail.properties.mail.smtp.from:}") String mailSmtpFrom,
                         @Value("${spring.mail.username:}") String mailUsername) {
        this.mailSenderProvider = mailSenderProvider;
        this.stubEnabled = stubEnabled;
        this.mailSmtpFrom = mailSmtpFrom;
        this.mailUsername = mailUsername;
    }

    /**
     * 向玩家发送邮件通知。stub 或 SMTP 未配置时仅记录（写入日志），不拼死启动。
     */
    public void send(String email, String subject, String text) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (stubEnabled || mailSender == null) {
            log.info("[邮件stub] 收件人={} 主题={}\n内容={}", email, subject, text);
            return;
        }
        try {
            MimeMessage m = mailSender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(m, "UTF-8");
            String from = (mailSmtpFrom != null && !mailSmtpFrom.isBlank()) ? mailSmtpFrom : mailUsername;
            h.setFrom(from);
            h.setSubject(subject);
            h.setText(text, true);
            mailSender.send(m);
            log.info("已向 {} 发送邮件：{}", email, subject);
        } catch (Exception e) {
            log.error("发送邮件失败 email={} subject={} err={}", email, subject, e.getMessage());
        }
    }
}