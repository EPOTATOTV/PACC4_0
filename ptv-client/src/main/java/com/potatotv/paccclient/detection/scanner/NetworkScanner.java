package com.potatotv.paccclient.detection.scanner;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.probe.NetworkSnapshot;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 网络行为检测（文档 §4.8）：识别作弊软件的 C2 通信与异常出站连接。
 *
 * <p>只读采样本机连接表（{@code netstat}），不改动、不拦截任何连接。三类信号加权：C2 域名 / IP
 * 命中 +50、异常端口 +15、游戏进程连非标准端口 +10；阈值 40，且必须至少有一条 C2 或异常端口
 * 命中才判定 —— 单靠游戏进程的非标准连接（可能只是语音 / 更新服务）不触发。</p>
 *
 * <p>C2 清单由云端经现有 {@code SignatureSync} 通道增量下发（文档 §9 注意事项 5），
 * 客户端不硬编码完整列表；{@link #setC2Domains} 供下发链路更新。</p>
 *
 * <p>产出扩展特征（{@code ext_network_}*），供 {@code suspicious_network} PRL 规则读取。</p>
 */
public final class NetworkScanner implements Detector {

    private static final String ID = "network_scanner";
    private static final long INTERVAL_MS = 10_000L;
    private static final int C2_WEIGHT = 50;
    private static final int PORT_WEIGHT = 15;
    private static final int MC_NONSTANDARD_WEIGHT = 10;
    private static final int THRESHOLD = 40;
    /** 本探针自身的回环端口，需排除。 */
    private static final int SELF_PROBE_PORT = 17020;

    /** 异常端口（非游戏 / 非 HTTP(S) 出站连接）。 */
    private static final Set<Integer> SUSPICIOUS_PORTS = Set.of(
            17020, 8080, 8888, 4444, 5555, 31337);

    /** 已知作弊软件 C2 域名 / IP（由云端下发更新）。 */
    private volatile Set<String> c2Domains = Set.of(
            "horion.xyz", "zephyrclient.com", "wurstclient.net", "meteorclient.com");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public long intervalMs() {
        return INTERVAL_MS;
    }

    /** 更新 C2 清单（云端 {code SignatureSync} 下发链路调用）。 */
    public void setC2Domains(Set<String> domains) {
        this.c2Domains = domains == null ? Set.of() : Set.copyOf(domains);
    }

    @Override
    public Optional<DetectionEvent> detect(DetectContext ctx) {
        if (!ctx.systemProbe().isSupported(SystemProbe.Capability.NETWORK)) {
            return Optional.empty();
        }
        NetworkSnapshot snapshot = ctx.systemProbe().snapshotNetwork();
        List<String> suspicious = new ArrayList<>();
        int score = 0;
        int c2Hits = 0;
        int portHits = 0;
        int mcNonstandardHits = 0;

        for (NetworkSnapshot.ConnectionInfo c : snapshot.connections()) {
            if (c.state() != NetworkSnapshot.ConnectionState.ESTABLISHED) {
                continue;
            }
            if (c.localPort() == SELF_PROBE_PORT) {
                continue;
            }
            String remoteHost = c.remoteHost();
            if (remoteHost != null && c2Domains.contains(remoteHost.toLowerCase(Locale.ROOT))) {
                suspicious.add("c2:" + remoteHost + ":" + c.remotePort());
                score += C2_WEIGHT;
                c2Hits++;
            }
            if (SUSPICIOUS_PORTS.contains(c.remotePort()) && c.remotePort() != SELF_PROBE_PORT) {
                suspicious.add("suspicious_port:" + c.remotePort());
                score += PORT_WEIGHT;
                portHits++;
            }
            String process = c.processName() == null ? "" : c.processName().toLowerCase(Locale.ROOT);
            if (process.contains("minecraft")
                    && c.remotePort() != 25565 && c.remotePort() != 443 && c.remotePort() != 80) {
                suspicious.add("mc_nonstandard_conn:" + remoteHost + ":" + c.remotePort());
                score += MC_NONSTANDARD_WEIGHT;
                mcNonstandardHits++;
            }
        }

        ctx.putExtended("ext_network_score", Math.min(100, score));
        ctx.putExtended("ext_network_c2_hits", c2Hits);
        ctx.putExtended("ext_network_suspicious_port_hits", portHits);
        ctx.putExtended("ext_network_mc_nonstandard_hits", mcNonstandardHits);

        if (score < THRESHOLD || (c2Hits == 0 && portHits == 0)) {
            return Optional.empty();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("connections", suspicious);
        detail.put("score", Math.min(100, score));
        return Optional.of(new DetectionEvent(
                "suspicious_network",
                score >= 70 ? "high" : "medium",
                Math.min(100, score),
                String.join(",", suspicious), null, null, ctx.osInfo().summary(),
                Json.encode(detail)));
    }
}