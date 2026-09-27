package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.alert.AlertGroup;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AlertGroupRepository extends JpaRepository<AlertGroup, String> {

    Page<AlertGroup> findAllByOrderByLastSeenAtDesc(Pageable pageable);

    List<AlertGroup> findByLastSeenAtAfter(Instant since);

    long countByLastSeenAtAfter(Instant since);
}