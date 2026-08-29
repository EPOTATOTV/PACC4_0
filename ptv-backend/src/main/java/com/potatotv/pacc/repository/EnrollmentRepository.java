package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EnrollmentRepository extends JpaRepository<Enrollment, String> {
    List<Enrollment> findAllByOrderByCreatedAtDesc();
    Optional<Enrollment> findFirstByPteidOrderByCreatedAtDesc(String pteid);
}