package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, String> {

    /** 广播公告（pteid 为空）。 */
    List<Notification> findByPteidIsNullOrderByCreatedAtDesc();

    /** 定向通知（目标玩家）。 */
    List<Notification> findByPteidOrderByCreatedAtDesc(String pteid);
}