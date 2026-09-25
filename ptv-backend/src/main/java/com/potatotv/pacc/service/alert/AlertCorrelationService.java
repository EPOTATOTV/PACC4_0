package com.potatotv.pacc.service.alert;

import com.potatotv.pacc.domain.alert.AlertGroup;
import com.potatotv.pacc.repository.AlertGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §4.3.2 相关性分析：把「同一作弊家族」的多个告警组关联为一个相关性事件，
 * 供运营从家族视角而非单条告警视角研判。
 */
@Service
@RequiredArgsConstructor
public class AlertCorrelationService {

    private final AlertGroupRepository groupRepository;

    /** 窗口内按家族聚合出的相关性事件（家族 → 事件）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> correlatedEvents(int windowHours) {
        int hours = windowHours <= 0 ? 24 : Math.min(windowHours, 24 * 30);
        Instant since = Instant.now().minus(Duration.ofHours(hours));
        Map<String, List<AlertGroup>> byFamily = new LinkedHashMap<>();
        for (AlertGroup g : groupRepository.findByLastSeenAtAfter(since)) {
            if (g.getFamilyCode() != null && !g.getFamilyCode().isBlank()) {
                byFamily.computeIfAbsent(g.getFamilyCode(), k -> new java.util.ArrayList<>()).add(g);
            }
        }
        return byFamily.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> {
                    List<AlertGroup> members = e.getValue();
                    int signals = members.stream().mapToInt(AlertGroup::getSignalCount).sum();
                    int severity = members.stream().mapToInt(AlertGroup::getSeverity).max().orElse(1);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("familyCode", e.getKey());
                    m.put("groupCount", members.size());
                    m.put("signalCount", signals);
                    m.put("maxSeverity", severity);
                    m.put("groupIds", members.stream().map(AlertGroup::getId).toList());
                    return m;
                })
                .toList();
    }
}