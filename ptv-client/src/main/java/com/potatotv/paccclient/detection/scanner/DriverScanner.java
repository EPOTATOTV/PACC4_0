package com.potatotv.paccclient.detection.scanner;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.probe.DriverSnapshot;
import com.potatotv.paccclient.probe.ServiceSnapshot;
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
 * 驱动 / 服务检测（文档 §4.6）：扫描已加载内核驱动与已注册系统服务里的已知作弊组件。
 *
 * <p>驱动单项权重 40（Cheat Engine 的 dbk64 / Process Hacker 的 kprocesshacker / TitanHide
 * 等反检测驱动），服务单项权重 30；阈值 40 意味着单个作弊驱动即判定，服务需叠加。</p>
 *
 * <p>产出扩展特征（{@code ext_driver_}*），供 {@code cheat_driver} PRL 规则读取。</p>
 */
public final class DriverScanner implements Detector {

    private static final String ID = "driver_scanner";
    private static final long INTERVAL_MS = 30_000L;
    private static final int DRIVER_WEIGHT = 40;
    private static final int SERVICE_WEIGHT = 30;
    private static final int THRESHOLD = 40;

    /** 已知作弊 / 反检测驱动（统一去掉 {@code .sys} 后缀后比较）。 */
    private static final Set<String> CHEAT_DRIVERS = Set.of(
            "dbk64", "dbk32", "kprocesshacker", "capcom", "dbutil_2_3",
            "titanhide", "scylla", "speedhack", "aimbot", "dma",
            "kernelhook", "infinityhook", "pgldd9x", "winio",
            "rweverything", "inpoutx64", "giveio");

    /** 已知作弊 / 可疑服务名（小写比较）。 */
    private static final Set<String> CHEAT_SERVICES = Set.of(
            "cheatengine", "horion", "zephyr", "codebreak",
            "processhacker", "dbk64", "dbk32", "titanhide", "scyllahide",
            "speedhack", "aimbotservice", "wemod", "vape", "neverlose",
            "32kclient", "meteorclient", "autohotkey", "按键精灵");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public long intervalMs() {
        return INTERVAL_MS;
    }

    @Override
    public Optional<DetectionEvent> detect(DetectContext ctx) {
        DriverSnapshot drivers = ctx.systemProbe().snapshotDrivers();
        ServiceSnapshot services = ctx.systemProbe().snapshotServices();
        if (drivers.drivers().isEmpty() && services.services().isEmpty()) {
            return Optional.empty();
        }

        List<String> matched = new ArrayList<>();
        int score = 0;
        int driverHits = 0;
        int serviceHits = 0;

        for (DriverSnapshot.DriverInfo driver : drivers.drivers()) {
            String name = normalizeDriver(driver.name());
            if (name.isEmpty() || !CHEAT_DRIVERS.contains(name)) {
                continue;
            }
            matched.add("driver:" + driver.name());
            score += DRIVER_WEIGHT;
            driverHits++;
        }
        for (ServiceSnapshot.ServiceInfo service : services.services()) {
            String name = service.name() == null ? "" : service.name().toLowerCase(Locale.ROOT);
            if (name.isEmpty() || !CHEAT_SERVICES.contains(name)) {
                continue;
            }
            matched.add("service:" + service.name());
            score += SERVICE_WEIGHT;
            serviceHits++;
        }

        ctx.putExtended("ext_driver_score", Math.min(100, score));
        ctx.putExtended("ext_driver_cheat_hits", driverHits);
        ctx.putExtended("ext_driver_service_hits", serviceHits);

        if (score < THRESHOLD) {
            return Optional.empty();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("score", Math.min(100, score));
        detail.put("matched", matched);
        return Optional.of(new DetectionEvent(
                "cheat_driver", "high", Math.min(100, score),
                String.join(",", matched), null, null, ctx.osInfo().summary(),
                Json.encode(detail)));
    }

    /** driverquery 的名字可能带 {@code .sys}，统一去后缀再比较。 */
    private static String normalizeDriver(String name) {
        if (name == null) {
            return "";
        }
        String lower = name.toLowerCase(Locale.ROOT).trim();
        return lower.endsWith(".sys") ? lower.substring(0, lower.length() - 4) : lower;
    }
}