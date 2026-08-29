package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.Peripheral;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PeripheralRepository extends JpaRepository<Peripheral, String> {
    List<Peripheral> findByPteidOrderByConnectedDescLastSeenAtDesc(String pteid);
    List<Peripheral> findByPteidAndDeviceFingerprint(String pteid, String deviceFingerprint);
}