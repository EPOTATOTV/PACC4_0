package com.potatotv.pacc.domain.automation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * §4.3.3 自动化响应状态：其他服务读取的「真实开关」（降级模式 / 非关键检测开关 / 上报频率倍数等）。
 * <p>键值对存储，便于动作处理器读写与回滚。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_automation_state")
public class AutomationState {

    @Id
    @Column(name = "state_key", length = 64)
    private String stateKey;

    @Column(name = "state_value", length = 512)
    private String stateValue;

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by", length = 128)
    private String updatedBy;
}