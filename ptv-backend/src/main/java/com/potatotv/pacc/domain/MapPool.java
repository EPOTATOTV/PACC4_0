package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 地图池：赛事可选地图的集合。可关联某赛事（tournamentId 可空=通用池），
 * 供对局前 BP（Ban/Pick）从其中挑选最终地图。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_map_pool", indexes = {
        @Index(name = "idx_map_pool_tourney", columnList = "tournamentId")
})
public class MapPool {

    @Id
    @Builder.Default
    private String poolId = java.util.UUID.randomUUID().toString();

    private String tournamentId;

    @Builder.Default
    private String name = "";

    private String gameMode;

    private String edition;

    @Column(length = 4000)
    private String description;

    @Builder.Default
    private boolean active = true;

    @Builder.Default
    private int mapCount = 0;

    private String createdBy;

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}