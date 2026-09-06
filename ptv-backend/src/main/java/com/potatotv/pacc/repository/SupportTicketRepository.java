package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, String> {

    List<SupportTicket> findByPteidOrderByCreatedAtDesc(String pteid);

    List<SupportTicket> findAllByOrderByCreatedAtDesc();

    List<SupportTicket> findByCategoryOrderByCreatedAtDesc(String category);

    List<SupportTicket> findByStatusOrderByCreatedAtDesc(String status);

    List<SupportTicket> findByCategoryAndStatusOrderByCreatedAtDesc(String category, String status);

    long countByStatus(String status);
}