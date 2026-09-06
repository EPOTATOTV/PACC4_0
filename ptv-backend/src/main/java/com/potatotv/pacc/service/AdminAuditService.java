package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.AdminOperationLog;
import com.potatotv.pacc.repository.AdminOperationLogRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * v4.8 管理员操作审计服务：落库管理端敏感写操作审计。
 * <p>只记录操作维度信息（操作人/动作/对象/结果/IP），不记录请求明文。</p>
 */
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);

    private final AdminOperationLogRepository repository;

    /**
     * 记录一次管理员操作。
     *
     * @param actor       操作人身份
     * @param role        角色（super-admin/operator/租户管理员）
     * @param action      动作编码（如 redscreen.unlock）
     * @param entityType  对象类型
     * @param entityId    对象 ID
     * @param detail      脱敏后的操作摘要
     * @param ip          来源 IP
     * @param httpMethod  HTTP 方法
     * @param httpPath    HTTP 路径（不含查询串）
     * @param httpStatus  响应状态码
     */
    public void record(String actor, String role, String action, String entityType, String entityId,
                       String detail, String ip, String httpMethod, String httpPath, int httpStatus) {
        try {
            repository.save(AdminOperationLog.builder()
                    .actor(actor)
                    .role(role)
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .detail(detail)
                    .ip(ip)
                    .httpMethod(httpMethod)
                    .httpPath(httpPath)
                    .httpStatus(httpStatus)
                    .build());
        } catch (Exception e) {
            log.warn("写入管理员操作审计失败 action={} err={}", action, e.getMessage());
        }
    }
}