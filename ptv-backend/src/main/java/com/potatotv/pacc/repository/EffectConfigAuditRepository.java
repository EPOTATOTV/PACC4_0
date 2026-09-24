package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.EffectConfigAudit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EffectConfigAuditRepository extends JpaRepository<EffectConfigAudit, Long> {

    /** 最近 N 条（按时间倒序，由 Pageable 限流）。 */
    List<EffectConfigAudit> findAllByOrderByCreatedAtDesc(Pageable pageable);
}