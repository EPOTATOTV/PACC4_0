package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.DeviceRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DeviceRecordRepository extends JpaRepository<DeviceRecord, String> {
    List<DeviceRecord> findByPteidOrderByLastLoginAtDesc(String pteid);
    List<DeviceRecord> findByPteidAndDeviceFingerprint(String pteid, String deviceFingerprint);

    /** v5.3 管理端：批量取玩家设备行，用于在指纹列表上补「最近登录平台」。 */
    List<DeviceRecord> findByPteidIn(Collection<String> pteids);
}