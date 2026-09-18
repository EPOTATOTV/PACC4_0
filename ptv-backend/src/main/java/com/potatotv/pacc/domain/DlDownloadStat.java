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

import java.time.LocalDate;

/**
 * 下载计数（按天 × 平台 × 产物聚合）。track 由端上信标累加。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_dl_download_stat")
public class DlDownloadStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate dayDate;

    @Column(nullable = false, length = 16)
    private String platform;

    @Column(nullable = false, length = 48)
    private String artifact;

    @Builder.Default
    @Column(nullable = false)
    private long count = 0;
}