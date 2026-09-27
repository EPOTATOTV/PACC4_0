package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.alert.AlertNotifyQueue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertNotifyQueueRepository extends JpaRepository<AlertNotifyQueue, String> {

    Page<AlertNotifyQueue> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<AlertNotifyQueue> findByStatusOrderByScheduledAtAsc(String status);
}