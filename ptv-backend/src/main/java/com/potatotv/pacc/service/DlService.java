package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.DlDownloadStat;
import com.potatotv.pacc.domain.DlRelease;
import com.potatotv.pacc.repository.DlDownloadStatRepository;
import com.potatotv.pacc.repository.DlReleaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 下载站服务：下发发布物信息、累加下载计数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DlService {

    private final DlReleaseRepository releaseRepository;
    private final DlDownloadStatRepository statRepository;

    /** 当前启用的全部发布物。 */
    @Transactional(readOnly = true)
    public List<DlRelease> latest() {
        return releaseRepository.findByEnabledTrueOrderByPlatformAscArtifactAsc();
    }

    /** 单次下载计数：对当天 × 平台 × 产物的记录 +1；不存在的记录新建。 */
    @Transactional
    public void track(String platform, String artifact) {
        String p = norm(platform, "UNK");
        String a = norm(artifact, "client");
        LocalDate day = LocalDate.now();
        DlDownloadStat row = statRepository.findByDayDateAndPlatformAndArtifact(day, p, a)
                .orElseGet(() -> statRepository.save(DlDownloadStat.builder()
                        .dayDate(day).platform(p).artifact(a).count(0L).build()));
        row.setCount(row.getCount() + 1);
    }

    /**
     * 管理端汇总：近 N 天各平台累计 + 「天 × 平台」等长序列，供管理端折线图直接对位。
     * dates 与每个 series.data 长度一致（缺数据的日子补 0），前端不用再对齐日期。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> adminStats(int days) {
        LocalDate today = LocalDate.now();
        List<DlDownloadStat> rows = statRepository.findByDayDateGreaterThanEqual(today.minusDays(days - 1L));

        List<String> dates = new ArrayList<>(days);
        for (int i = days - 1; i >= 0; i--) {
            dates.add(today.minusDays(i).toString());
        }

        Map<String, Long> byPlatform = new LinkedHashMap<>();
        // 平台 → 日期 → 计数；TreeMap 让平台顺序稳定，图表图例不会随查询顺序跳
        Map<String, Map<String, Long>> byPlatformDay = new TreeMap<>();
        for (DlDownloadStat r : rows) {
            byPlatform.merge(r.getPlatform(), r.getCount(), Long::sum);
            byPlatformDay.computeIfAbsent(r.getPlatform(), k -> new HashMap<>())
                    .merge(r.getDayDate().toString(), r.getCount(), Long::sum);
        }

        List<Map<String, Object>> series = new ArrayList<>();
        for (Map.Entry<String, Map<String, Long>> e : byPlatformDay.entrySet()) {
            Map<String, Long> dayMap = e.getValue();
            List<Long> counts = new ArrayList<>(days);
            for (String d : dates) {
                counts.add(dayMap.getOrDefault(d, 0L));
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("platform", e.getKey());
            one.put("data", counts);
            series.add(one);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("platform", byPlatform);
        out.put("dates", dates);
        out.put("series", series);
        return out;
    }

    private static String norm(String v, String def) {
        String s = v == null ? "" : v.trim().toUpperCase();
        return s.isEmpty() ? def : s;
    }
}