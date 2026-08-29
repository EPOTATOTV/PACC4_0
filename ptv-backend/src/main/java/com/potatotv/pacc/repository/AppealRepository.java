package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.Appeal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppealRepository extends JpaRepository<Appeal, String> {

    List<Appeal> findByPteidOrderByCreatedAtDesc(String pteid);

    List<Appeal> findByStatusOrderByCreatedAtAsc(String status);

    long countByStatus(String status);
}
