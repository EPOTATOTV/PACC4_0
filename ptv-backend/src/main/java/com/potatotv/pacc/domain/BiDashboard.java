package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * v4.8 数据平台与 BI：自定义仪表盘。
 * <p>保存租户/平台管理员拖拽生成的仪表盘定义（组件 JSON、布局），
 * 供前端按需渲染图表并调用预置报表 API 拉取数据。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_bi_dashboard")
public class BiDashboard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属租户；平台级仪表盘用 'platform'。 */
    @Builder.Default
    @Column(nullable = false, length = 64)
    private String tenantId = "platform";

    @Column(nullable = false, length = 128)
    private String name;

    /** 组件定义 JSON（图表类型/数据源/筛选条件/刷新频率）。 */
    @Column(nullable = false)
    private String widgets;

    @Column(length = 255)
    private String layout;

    private String createdBy;

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}