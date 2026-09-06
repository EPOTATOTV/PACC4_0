package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ZeroDayFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ZeroDayFindingRepository extends JpaRepository<ZeroDayFinding, String> {

    List<ZeroDayFinding> findByStatusOrderByCreatedAtDesc(ZeroDayFinding.Status status);

    List<ZeroDayFinding> findTop50ByOrderByCreatedAtDesc();

    long countByStatus(ZeroDayFinding.Status status);
}