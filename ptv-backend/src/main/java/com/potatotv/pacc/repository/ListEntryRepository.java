package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ListEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ListEntryRepository extends JpaRepository<ListEntry, String> {

    Optional<ListEntry> findByListTypeAndEntryTypeAndValue(String listType, String entryType, String value);

    List<ListEntry> findByListTypeAndEntryTypeAndStatusOrderByCreatedAtDesc(
            String listType, String entryType, String status);

    List<ListEntry> findByEntryTypeAndStatusOrderByCreatedAtDesc(String entryType, String status);

    List<ListEntry> findByListTypeAndStatusOrderByCreatedAtDesc(String listType, String status);

    boolean existsByListTypeAndEntryTypeAndValueAndStatus(String listType, String entryType, String value, String status);
}