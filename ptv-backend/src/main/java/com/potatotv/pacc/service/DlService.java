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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** 管理端汇总：近 N 天逐日趋势（按平台 × 产物展开），外加各平台累计。 */
    @Transactional(readOnly = true)
    public Map<String, Object> adminStats(int days) {
        LocalDate start = LocalDate.now().minusDays(days);
        List<DlDownloadStat> rows = statRepository.findByDayDateGreaterThanEqual(start);

        Map<String, Long> byPlatform = new LinkedHashMap<>();
        Map<String, Long> perDay = new LinkedHashMap<>();
        for (int i = days - 1; i >= 0; i--) {
            perDay.put(LocalDate.now().minusDays(i).toString(), 0L);
        }
        for (DlDownloadStat r : rows) {
            byPlatform.merge(r.getPlatform(), r.getCount(), Long::sum);
            String d = r.getDayDate().toString();
            perDay.merge(d, r.getCount(), Long::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("platform", byPlatform);
        out.put("trend", perDay.entrySet().stream()
                .map(e -> Map.<String, Object>of("date", e.getKey(), "count", e.getValue()))
                .toList());
        return out;
    }

    private static String norm(String v, String def) {
        String s = v == null ? "" : v.trim().toUpperCase();
        return s.isEmpty() ? def : s;
    }
}