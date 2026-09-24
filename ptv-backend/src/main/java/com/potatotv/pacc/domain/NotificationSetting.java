package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 玩家通知偏好设置：settings 为 JSON 文本（如各类型推送开关）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_notification_setting")
public class NotificationSetting {

    @Id
    @Column(name = "pteid")
    private String pteid;

    /** length 取 int 上限：Hibernate 据此推导为 longtext，与迁移脚本一致。 */
    @Lob
    @Column(length = Integer.MAX_VALUE)
    private String settings;
}