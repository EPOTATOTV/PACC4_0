package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.plugin.PluginRuntime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PluginRuntimeRepository extends JpaRepository<PluginRuntime, String> {

    List<PluginRuntime> findAllByOrderByUpdatedAtDesc();
}