package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.CheatRecord;
import com.potatotv.pacc.repository.CheatRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 管理端作弊记录：全量检索（支持按 PTEID 过滤）+ 撤销/恢复记录。
 * 受 SecurityConfig 中的 /api/admin/** X-Admin-Key 前置过滤保护。
 */
@RestController
@RequestMapping("/api/admin/records")
@RequiredArgsConstructor
@SuppressWarnings("null")
public class RecordsAdminController {

    private final CheatRecordRepository recordRepository;

    /** 作弊记录列表；keyword 非空时仅返回匹配该 PTEID 的记录。 */
    @GetMapping
    public List<CheatRecord> list(@RequestParam(defaultValue = "") String keyword) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        return recordRepository.findAll().stream()
                .filter(r -> kw.isEmpty() || (r.getPteid() != null && r.getPteid().toLowerCase().contains(kw)))
                .sorted(Comparator.comparing(CheatRecord::getOccurredAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /** 撤销 / 恢复该条作弊记录（查端误报等场景还原账号声誉）。 */
    @PostMapping("/{recordId}/revoke")
    public ResponseEntity<?> revoke(@PathVariable String recordId, @RequestBody Map<String, String> body) {
        return recordRepository.findById(recordId)
                .map(r -> {
                    boolean revoked = Boolean.parseBoolean(body.getOrDefault("revoked", "true"));
                    r.setRevoked(revoked);
                    recordRepository.save(r);
                    return ResponseEntity.ok(Map.of(
                            "record_id", r.getRecordId(),
                            "revoked", r.isRevoked()));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}