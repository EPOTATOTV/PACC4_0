package com.potatotv.pacc.service.detection.v46;

import com.potatotv.pacc.domain.ThreatIntelSample;
import com.potatotv.pacc.domain.ZeroDayFinding;
import com.potatotv.pacc.repository.ThreatIntelSampleRepository;
import com.potatotv.pacc.repository.ZeroDayFindingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * v4.6 概念漂移 / 主动学习：将低置信的零日发现与威胁情报样本放入运营队列，
 * 人工复核（确认真样本 / 误报）后固化结论并回流，作为后续模型与特征库更新的训练信号。
 */
@Service
@RequiredArgsConstructor
public class ActiveLearningService {

    private final ZeroDayFindingRepository zeroDayFindings;
    private final ThreatIntelSampleRepository threatIntelSamples;

    /** 零日发现复核队列（含低置信待判定样本）。 */
    public List<ZeroDayFinding> zeroDayQueue() {
        return zeroDayFindings.findByStatusOrderByCreatedAtDesc(ZeroDayFinding.Status.OPEN);
    }

    /** 威胁情报样本复核队列。 */
    public List<ThreatIntelSample> threatQueue() {
        return threatIntelSamples.findByStatusOrderByCreatedAtDesc(ThreatIntelSample.Status.NEW);
    }

    /** 审核一条零日发现。 */
    public ZeroDayFinding reviewFinding(String findingId, Boolean confirmed, String reviewer, String comment) {
        ZeroDayFinding f = zeroDayFindings.findById(findingId).orElseThrow();
        f.setStatus(ZeroDayFinding.Status.REVIEWED);
        f.setConfirmed(confirmed);
        f.setReviewer(reviewer);
        f.setReviewedAt(Instant.now());
        f.setReviewComment(comment);
        return zeroDayFindings.save(f);
    }

    /** 审核一条威胁情报样本。 */
    public ThreatIntelSample reviewThreat(String sampleId, Boolean confirmed, String reviewer) {
        ThreatIntelSample s = threatIntelSamples.findById(sampleId).orElseThrow();
        s.setStatus(ThreatIntelSample.Status.REVIEWED);
        s.setConfirmed(confirmed);
        s.setReviewer(reviewer);
        s.setReviewedAt(Instant.now());
        return threatIntelSamples.save(s);
    }

    public long pendingZeroDayCount() {
        return zeroDayFindings.countByStatus(ZeroDayFinding.Status.OPEN);
    }

    public long pendingThreatCount() {
        return threatIntelSamples.countByStatus(ThreatIntelSample.Status.NEW);
    }
}