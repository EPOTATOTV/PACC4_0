package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.ListEntry;
import com.potatotv.pacc.repository.ListEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 黑白名单服务：玩家/设备/IP/进程/特征 × 黑/白名单方向的管理。
 * 提供给检测与运维对照使用（命中黑名单加重、白名单豁免的判断由消费方发起）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListService {

    public static final String[] LIST_TYPES = {"BLACK", "WHITE"};
    public static final String[] ENTRY_TYPES = {"PLAYER", "DEVICE", "IP", "PROCESS", "FEATURE"};

    private final ListEntryRepository listEntryRepository;

    /** 新增一条名单：同 (listType, entryType, value) 已存在则更新而非重复插入。 */
    @Transactional
    public ListEntry add(String listType, String entryType, String value,
                         String reason, String status, String operator, Long expiresAtMs) {
        String lt = normalize(listType, LIST_TYPES, "WHITE");
        String et = normalize(entryType, ENTRY_TYPES, "FEATURE");
        String v = value == null || value.isBlank() ? throwBad("值不能为空") : value.trim();
        String st = normStatus(status);
        ListEntry target = listEntryRepository.findByListTypeAndEntryTypeAndValue(lt, et, v).orElse(null);
        if (target != null) {
            target.setReason(reason);
            target.setStatus(st);
            target.setExpiresAt(expiresAtMs == null ? null : Instant.ofEpochMilli(expiresAtMs));
            if (target.getCreatedBy() == null) target.setCreatedBy(operator);
            listEntryRepository.save(target);
            return target;
        }
        ListEntry entry = ListEntry.builder()
                .id(UUID.randomUUID().toString())
                .listType(lt)
                .entryType(et)
                .value(v)
                .reason(reason)
                .status(st)
                .createdBy(operator)
                .createdAt(Instant.now())
                .expiresAt(expiresAtMs == null ? null : Instant.ofEpochMilli(expiresAtMs))
                .build();
        listEntryRepository.save(entry);
        log.info("黑白名单新增 {}:{} = {} by={}", lt, et, v, operator);
        return entry;
    }

    /** 查询名单（可按方向与对象类型过滤）。 */
    public List<Map<String, Object>> list(String listType, String entryType, String status) {
        String lt = listType == null || listType.isBlank() ? null : listType.trim();
        String et = entryType == null || entryType.isBlank() ? null : entryType.trim();
        String st = normStatus(status);
        List<ListEntry> rows;
        if (lt != null && et != null) {
            rows = listEntryRepository.findByListTypeAndEntryTypeAndStatusOrderByCreatedAtDesc(lt, et, st);
        } else if (lt != null) {
            rows = listEntryRepository.findByListTypeAndStatusOrderByCreatedAtDesc(lt, st);
        } else if (et != null) {
            rows = listEntryRepository.findByEntryTypeAndStatusOrderByCreatedAtDesc(et, st);
        } else {
            rows = listEntryRepository.findAll().stream()
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .toList();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ListEntry e : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("list_type", e.getListType());
            m.put("entry_type", e.getEntryType());
            m.put("value", e.getValue());
            m.put("reason", e.getReason() == null ? "" : e.getReason());
            m.put("status", e.getStatus());
            m.put("created_by", e.getCreatedBy());
            m.put("created_at", e.getCreatedAt() == null ? "" : e.getCreatedAt().toString());
            m.put("expires_at", e.getExpiresAt() == null ? "" : e.getExpiresAt().toString());
            out.add(m);
        }
        return out;
    }

    /** 命中判定：某对象类型+值是否存在于指定名单方向的有效条目。 */
    public boolean matches(String listType, String entryType, String value) {
        if (value == null || value.isBlank()) return false;
        return listEntryRepository
                .findByListTypeAndEntryTypeAndValue(listType.trim(), entryType.trim(), value.trim())
                .map(e -> "ACTIVE".equals(e.getStatus())
                        && (e.getExpiresAt() == null || e.getExpiresAt().isAfter(Instant.now())))
                .orElse(false);
    }

    /** 删除名单条目。 */
    @Transactional
    public boolean remove(String id) {
        if (!listEntryRepository.existsById(id)) return false;
        listEntryRepository.deleteById(id);
        return true;
    }

    private String throwBad(String msg) {
        throw new IllegalArgumentException(msg);
    }

    private String normalize(String v, String[] allowed, String fallback) {
        if (v == null || v.isBlank()) return fallback;
        String up = v.trim().toUpperCase();
        for (String a : allowed) {
            if (a.equals(up)) return a;
        }
        return fallback;
    }

    private String normStatus(String status) {
        if (status == null || status.isBlank()) return "ACTIVE";
        String up = status.trim().toUpperCase();
        return "INACTIVE".equals(up) ? "INACTIVE" : "ACTIVE";
    }
}