package com.potatotv.pacc.domain;

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
 * 赛事直播转播配置（OBS 推流的 B 站直播间）。
 * <p>管理端维护多条转播；玩家端只读展示 live=true 的条目。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_broadcast")
public class Broadcast {

    @Id
    private String id;

    @Column(nullable = false, length = 128)
    private String title;

    /** B 站直播间号（live.bilibili.com/{id} 的末段）。 */
    @Column(nullable = false, length = 32)
    private String bilibiliLiveId;

    @Column(length = 512)
    private String coverUrl;

    @Column(length = 512)
    private String description;

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String platform = "bilibili";

    /** 是否对外可见/正在开播。 */
    @Builder.Default
    @Column(nullable = false)
    private boolean live = false;

    /** 排序权重，小在前。 */
    @Builder.Default
    @Column(nullable = false)
    private int sort = 0;

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column
    private Instant updatedAt;
}