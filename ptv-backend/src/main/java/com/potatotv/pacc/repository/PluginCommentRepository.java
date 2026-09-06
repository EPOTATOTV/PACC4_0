package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.PluginComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PluginCommentRepository extends JpaRepository<PluginComment, Long> {

    Page<PluginComment> findByPluginIdOrderByCreatedAtDesc(String pluginId, Pageable pageable);

    Optional<PluginComment> findByPluginIdAndAuthor(String pluginId, String author);
}