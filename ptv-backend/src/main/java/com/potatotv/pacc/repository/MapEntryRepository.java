package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.MapEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MapEntryRepository extends JpaRepository<MapEntry, String> {
    List<MapEntry> findByPoolIdOrderByOrderNoAscCreatedAtAsc(String poolId);
    List<MapEntry> findByPoolIdAndActiveTrueOrderByOrderNoAsc(String poolId);
    long countByPoolId(String poolId);

    @Modifying
    @Query("UPDATE MapEntry e SET e.banCount = e.banCount + 1 WHERE e.mapId = :mapId")
    void incrementBanCount(@Param("mapId") String mapId);

    @Modifying
    @Query("UPDATE MapEntry e SET e.pickCount = e.pickCount + 1 WHERE e.mapId = :mapId")
    void incrementPickCount(@Param("mapId") String mapId);
}