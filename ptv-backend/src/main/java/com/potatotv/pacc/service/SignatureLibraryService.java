package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.Signature;
import com.potatotv.pacc.repository.SignatureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 特征库管理：特征码增删改查、灰度发布（1/10/50/100）、版本回滚。
 * 基岩版 / Java 版分别管理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignatureLibraryService {

    private final SignatureRepository repo;

    @Transactional
    public Signature add(String name, String pattern, int riskLevel, Signature.Edition edition,
                         String libraryVersion, String operator) {
        Signature s = Signature.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .pattern(pattern)
                .riskLevel(riskLevel)
                .edition(edition)
                .libraryVersion(libraryVersion == null ? "v4.0.0" : libraryVersion)
                .state("DRAFT")
                .createdBy(operator)
                .createdAt(Instant.now())
                .build();
        return repo.save(s);
    }

    public List<Signature> listByEdition(Signature.Edition edition) {
        return repo.findByEditionAndState(edition, "PUBLISHED");
    }

    /** 灰度发布：按比例标记。简化实现直接标记 GRAY/PUBLISHED。 */
    @Transactional
    public void grayRelease(Signature.Edition edition, int percent, String operator) {
        List<Signature> drafts = repo.findByEditionAndState(edition, "DRAFT");
        drafts.forEach(d -> {
            d.setState(percent >= 100 ? "PUBLISHED" : "GRAY");
            d.setGrayPercent(percent);
            repo.save(d);
        });
        log.info("特征库灰度发布 edition={} percent={} counts={} operator={}", edition, percent, drafts.size(), operator);
    }

    /** 回滚到上一稳定版本：将当前 GRAY/PUBLISHED 标记回滚（简化：置 DRAFT）。 */
    @Transactional
    public int rollback(Signature.Edition edition, String operator) {
        return repo.markState("PUBLISHED", "DRAFT", edition) + repo.markState("GRAY", "DRAFT", edition);
    }

    public long draftCount() {
        return repo.countByState("DRAFT");
    }
}