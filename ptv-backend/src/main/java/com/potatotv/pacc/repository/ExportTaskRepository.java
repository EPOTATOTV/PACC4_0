package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ExportTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExportTaskRepository extends JpaRepository<ExportTask, String> {

    List<ExportTask> findByRequestedByOrderByRequestedAtDesc(String requestedBy);

    List<ExportTask> findByStatusOrderByRequestedAtDesc(String status);
}