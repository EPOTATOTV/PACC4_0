package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.UpdateReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;

public interface UpdateReportRepository extends JpaRepository<UpdateReport, String> {

    /** 某目标版本在时间窗内的上报总数（灰度放量的分母）。 */
    long countByToVersionAndCreatedAtAfter(String toVersion, Instant since);

    /** 某目标版本在时间窗内落到给定状态的上报数（灰度放量的分子）。 */
    long countByToVersionAndStatusInAndCreatedAtAfter(String toVersion, Collection<String> statuses, Instant since);
}