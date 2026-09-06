package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.FaqEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FaqEntryRepository extends JpaRepository<FaqEntry, String> {

    List<FaqEntry> findAllByOrderByCreatedAtDesc();
}