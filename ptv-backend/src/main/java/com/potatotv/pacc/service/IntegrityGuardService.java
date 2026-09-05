package com.potatotv.pacc.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * v4.5 完整性/对抗状态护栏（服务端聚合判定）。
 * <p>客户端在本地完成反调试/反 Hook/DSE/代码哈希等自检后，把布尔结果上报到这里，
 * 服务端按权重聚合出完整性风险分并分三级：</p>
 * <ul>
 *   <li>CLEAN：无异常</li>
 *   <li>SUSPECT：轻微可疑（签名弱、个别 Hook），进入深观察</li>
 *   <li>TAMPERED：严重破坏（DSE 关闭 / TESTSIGNING / 代码段哈希不符），直接进红屏对抗链路</li>
 * </ul>
 * <p>纯函数核心为 {@link #assess(...)}，便于确定性单测。</p>
 */
@Service
public class IntegrityGuardService {

    private final int suspectThreshold;
    private final int tamperedThreshold;

    public IntegrityGuardService(@Value("${pacc.countermeasure.integrity-suspect:25}") int suspectThreshold,
                                 @Value("${pacc.countermeasure.integrity-tampered:60}") int tamperedThreshold) {
        this.suspectThreshold = suspectThreshold;
        this.tamperedThreshold = tamperedThreshold;
    }

    public enum State { CLEAN, SUSPECT, TAMPERED }

    public record IntegrityReport(int score, State state, List<String> findings) {
    }

    /** 客户端上传的完整性自检结果。 */
    public record Input(boolean signatureValid, boolean dseOperating, boolean testsigningOn,
                        boolean codeHashMatch, List<String> hookFound) {
    }

    /** 聚合评分。未配置/兜底阈值。 */
    public IntegrityReport assess(Input in) {
        List<String> findings = new ArrayList<>();
        int score = 0;
        if (in == null) return new IntegrityReport(0, State.CLEAN, findings);

        if (!in.signatureValid()) {
            score += 30;
            findings.add("invalid_signature");
        }
        if (!in.dseOperating()) {
            score += 20;
            findings.add("dse_disabled");
        }
        if (in.testsigningOn()) {
            score += 30;
            findings.add("testsigning_enabled");
        }
        if (!in.codeHashMatch()) {
            score += 40;
            findings.add("code_hash_mismatch");
        }
        if (in.hookFound() != null) {
            for (String h : in.hookFound()) {
                if (h == null || h.isBlank()) continue;
                score += 10;
                findings.add("hook:" + h);
            }
        }
        score = Math.min(score, 100);
        State state = score >= tamperedThreshold ? State.TAMPERED
                : score >= suspectThreshold ? State.SUSPECT : State.CLEAN;
        return new IntegrityReport(score, state, findings);
    }

    public int suspectThreshold() {
        return suspectThreshold;
    }

    public int tamperedThreshold() {
        return tamperedThreshold;
    }
}