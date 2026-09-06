package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.Plugin;
import com.potatotv.pacc.domain.Plugin.Type;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PluginRepository extends JpaRepository<Plugin, String> {

    Page<Plugin> findByStatusOrderByUpdatedAtDesc(String status, Pageable pageable);

    List<Plugin> findByStatusAndType(String status, Type type);

    @Modifying
    @Query("update Plugin p set p.downloads = p.downloads + :n, p.updatedAt = :now where p.pluginId = :id")
    int bumpDownloads(@Param("id") String id, @Param("n") long n, @Param("now") Instant now);

    @Modifying
    @Query("update Plugin p set p.status = :status, p.publishedAt = :publishedAt, p.updatedAt = :now where p.pluginId = :id")
    int updateStatus(@Param("id") String id, @Param("status") String status,
                     @Param("publishedAt") Instant publishedAt, @Param("now") Instant now);
}