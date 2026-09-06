package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.BiDashboard;
import com.potatotv.pacc.repository.BiDashboardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * v4.8 数据平台与 BI：自定义仪表盘 CRUD。
 * <p>widgets 为前端提交的组件 JSON 字符串，服务端仅负责持久化与校验，不解析图表内容，
 * 避免把内部聚合逻辑耦合进仪表盘定义。</p>
 */
@Service
@RequiredArgsConstructor
public class BiDashboardService {

    private final BiDashboardRepository repo;

    public List<BiDashboard> list(String tenantId) {
        return repo.findByTenantIdOrderByUpdatedAtDesc(tenantId == null || tenantId.isBlank() ? "platform" : tenantId);
    }

    public BiDashboard create(String tenantId, String name, String widgets, String layout, String createdBy) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("dashboard name is required");
        if (widgets == null || widgets.isBlank()) throw new IllegalArgumentException("dashboard widgets is required");
        BiDashboard d = BiDashboard.builder()
                .tenantId(tenantId == null || tenantId.isBlank() ? "platform" : tenantId)
                .name(name.trim())
                .widgets(widgets)
                .layout(layout)
                .createdBy(createdBy)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return repo.save(d);
    }

    public BiDashboard update(Long id, String name, String widgets, String layout) {
        BiDashboard d = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("dashboard not found: " + id));
        if (name != null && !name.isBlank()) d.setName(name.trim());
        if (widgets != null && !widgets.isBlank()) d.setWidgets(widgets);
        if (layout != null) d.setLayout(layout);
        d.setUpdatedAt(Instant.now());
        return repo.save(d);
    }

    public void delete(Long id) {
        repo.deleteById(id);
    }
}