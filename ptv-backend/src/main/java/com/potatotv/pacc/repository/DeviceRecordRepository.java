package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.DeviceRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceRecordRepository extends JpaRepository<DeviceRecord, String> {
    List<DeviceRecord> findByPteidOrderByLastLoginAtDesc(String pteid);
    List<DeviceRecord> findByPteidAndDeviceFingerprint(String pteid, String deviceFingerprint);
}