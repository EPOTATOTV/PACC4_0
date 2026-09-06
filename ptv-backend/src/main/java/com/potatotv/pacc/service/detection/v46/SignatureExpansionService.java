package com.potatotv.pacc.service.detection.v46;

import com.potatotv.pacc.domain.Signature;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * v4.6 特征库扩充：维护基岩版 / Java 版外挂特征种子库并执行匹配。
 * 种子的 pattern 指向静态维度键，命中即记录风险；经威胁情报与主动学习审核后可提升为正式特征库。
 */
@Service
public class SignatureExpansionService {

    /** 特征种子：name / pattern(静态维度键) / riskLevel / edition / libraryVersion(state=DRAFT)。 */
    private static final List<Seed> SEEDS = List.of(
            new Seed("bedrock-auto-clicker", "bedrock_auto_click", 4, Signature.Edition.BEDROCK, "4.6.0"),
            new Seed("bedrock-reach-cheat", "bedrock_reach", 4, Signature.Edition.BEDROCK, "4.6.0"),
            new Seed("bedrock-jump-sprint-spoofer", "bedrock_jump_sprint", 3, Signature.Edition.BEDROCK, "4.6.0"),
            new Seed("bedrock-hitbox-widener", "bedrock_hitbox", 5, Signature.Edition.BEDROCK, "4.6.0"),
            new Seed("java-killaura-assist", "java_killaura", 4, Signature.Edition.JAVA, "4.6.0"),
            new Seed("java-flyable-hack", "java_fly", 5, Signature.Edition.JAVA, "4.6.0"),
            new Seed("java-speedhack", "java_speed", 4, Signature.Edition.JAVA, "4.6.0"),
            new Seed("java-ghost-client-class", "java_ghost_client", 5, Signature.Edition.JAVA, "4.6.0")
    );

    public record Seed(String name, String pattern, int riskLevel, Signature.Edition edition, String libraryVersion) {
    }

    public record Matched(String name, int riskLevel, Signature.Edition edition) {
    }

    /** 返回特征库扩充种子清单（数据源，供后台查看与后续正式入库）。 */
    public List<Seed> seeds() {
        return SEEDS;
    }

    /** 对可疑样本的静态维度做特征匹配，返回命中的种子（按风险降序）。 */
    public List<Matched> match(Map<String, String> staticDims) {
        if (staticDims == null) return List.of();
        return SEEDS.stream()
                .filter(s -> staticDims.containsKey(s.pattern()))
                .map(s -> new Matched(s.name(), s.riskLevel(), s.edition()))
                .sorted((a, b) -> Integer.compare(b.riskLevel(), a.riskLevel()))
                .collect(Collectors.toList());
    }

    /** 种子总数（供看板）。 */
    public int seedCount() {
        return SEEDS.size();
    }

    /** 演示静态维度样本，用于后台一键匹配演示。 */
    public static Map<String, String> demoStaticDims() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("java_ghost_client", "com.cheat.GhostClient");
        m.put("java_killaura", "net.killaura.KillAura");
        m.put("behavior_click_cv", "0.02");
        return m;
    }
}