package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.MapEntry;
import com.potatotv.pacc.domain.MapPool;
import com.potatotv.pacc.repository.MapEntryRepository;
import com.potatotv.pacc.repository.MapPoolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 地图池与地图条目管理：CRUD、启停、排序、批量导入与统计。
 * pool.mapCount 为冗余统计，随条目增删在事务内同步。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class MapService {

    private final MapPoolRepository poolRepository;
    private final MapEntryRepository entryRepository;

    // ---------------- 地图池 ----------------

    public List<MapPool> pools() {
        return poolRepository.findAllByOrderByCreatedAtDesc();
    }

    /** 玩家可见的启用中的池（含全部用于选择）。 */
    public List<MapPool> activePools() {
        return poolRepository.findByActiveTrueOrderByCreatedAtDesc();
    }

    public MapPool getPool(String poolId) {
        return poolRepository.findById(poolId).orElse(null);
    }

    @Transactional
    public MapPool createPool(String name, String tournamentId, String gameMode, String edition,
                              String description, String createdBy) {
        MapPool p = MapPool.builder()
                .name(name == null || name.isBlank() ? "未命名池" : name)
                .tournamentId(tournamentId)
                .gameMode(gameMode)
                .edition(edition)
                .description(description)
                .active(true)
                .createdBy(createdBy)
                .build();
        return poolRepository.save(p);
    }

    @Transactional
    public MapPool updatePool(String poolId, String name, String tournamentId, String gameMode,
                              String edition, String description, Boolean active) {
        return poolRepository.findById(poolId).map(p -> {
            if (name != null && !name.isBlank()) p.setName(name);
            if (tournamentId != null) p.setTournamentId(tournamentId);
            if (gameMode != null) p.setGameMode(gameMode);
            if (edition != null) p.setEdition(edition);
            if (description != null) p.setDescription(description);
            if (active != null) p.setActive(active);
            return poolRepository.save(p);
        }).orElse(null);
    }

    @Transactional
    public boolean deletePool(String poolId) {
        if (!poolRepository.existsById(poolId)) return false;
        entryRepository.deleteAll(entryRepository.findByPoolIdOrderByOrderNoAscCreatedAtAsc(poolId));
        poolRepository.deleteById(poolId);
        return true;
    }

    // ---------------- 地图条目 ----------------

    public List<MapEntry> entries(String poolId) {
        return entryRepository.findByPoolIdOrderByOrderNoAscCreatedAtAsc(poolId);
    }

    public List<MapEntry> activeEntries(String poolId) {
        return entryRepository.findByPoolIdAndActiveTrueOrderByOrderNoAsc(poolId);
    }

    @Transactional
    public MapEntry createEntry(String poolId, String name, String nameEn, String mapType, String author,
                                String version, String difficulty, String thumbnailUrl, String previewImages,
                                String description, String downloadUrl, double winRateBlue, double winRateRed,
                                int orderNo, String createdBy) {
        MapEntry e = MapEntry.builder()
                .poolId(poolId)
                .name(name == null || name.isBlank() ? "未命名地图" : name)
                .nameEn(nameEn)
                .mapType(mapType)
                .author(author)
                .version(version)
                .difficulty(difficulty)
                .thumbnailUrl(thumbnailUrl)
                .previewImages(previewImages)
                .description(description)
                .downloadUrl(downloadUrl)
                .winRateBlue(Math.max(0, Math.min(1, winRateBlue)))
                .winRateRed(Math.max(0, Math.min(1, winRateRed)))
                .active(true)
                .orderNo(orderNo)
                .createdBy(createdBy)
                .build();
        MapEntry saved = entryRepository.save(e);
        bumpPoolMapCount(poolId, 1);
        return saved;
    }

    @Transactional
    public MapEntry updateEntry(String mapId, String name, String nameEn, String mapType, String author,
                                String version, String difficulty, String thumbnailUrl, String previewImages,
                                String description, String downloadUrl, Double winRateBlue, Double winRateRed,
                                Integer orderNo, Boolean active) {
        return entryRepository.findById(mapId).map(e -> {
            if (name != null && !name.isBlank()) e.setName(name);
            if (nameEn != null) e.setNameEn(nameEn);
            if (mapType != null) e.setMapType(mapType);
            if (author != null) e.setAuthor(author);
            if (version != null) e.setVersion(version);
            if (difficulty != null) e.setDifficulty(difficulty);
            if (thumbnailUrl != null) e.setThumbnailUrl(thumbnailUrl);
            if (previewImages != null) e.setPreviewImages(previewImages);
            if (description != null) e.setDescription(description);
            if (downloadUrl != null) e.setDownloadUrl(downloadUrl);
            if (winRateBlue != null) e.setWinRateBlue(Math.max(0, Math.min(1, winRateBlue)));
            if (winRateRed != null) e.setWinRateRed(Math.max(0, Math.min(1, winRateRed)));
            if (orderNo != null) e.setOrderNo(orderNo);
            if (active != null) e.setActive(active);
            return entryRepository.save(e);
        }).orElse(null);
    }

    @Transactional
    public boolean deleteEntry(String mapId) {
        return entryRepository.findById(mapId).map(e -> {
            String poolId = e.getPoolId();
            entryRepository.deleteById(mapId);
            bumpPoolMapCount(poolId, -1);
            return true;
        }).orElse(false);
    }

    @Transactional
    public boolean toggleEntry(String mapId) {
        return entryRepository.findById(mapId).map(e -> {
            e.setActive(!e.isActive());
            entryRepository.save(e);
            return true;
        }).orElse(false);
    }

    @Transactional
    public int batchAdd(String poolId, List<Map<String, Object>> rows, String createdBy) {
        if (poolId == null || rows == null) return 0;
        int current = (int) entryRepository.countByPoolId(poolId);
        int added = 0;
        for (Map<String, Object> r : rows) {
            Object name = r.get("name");
            if (name == null) continue;
            entryRepository.save(MapEntry.builder()
                    .poolId(poolId)
                    .name(name.toString())
                    .nameEn(str(r.get("name_en")))
                    .mapType(str(r.get("map_type")))
                    .author(str(r.get("author")))
                    .version(str(r.get("version")))
                    .difficulty(str(r.get("difficulty")))
                    .thumbnailUrl(str(r.get("thumbnail_url")))
                    .description(str(r.get("description")))
                    .downloadUrl(str(r.get("download_url")))
                    .active(true)
                    .orderNo(current + added)
                    .createdBy(createdBy)
                    .build());
            added++;
        }
        if (added > 0) bumpPoolMapCount(poolId, added);
        return added;
    }

    // ---------------- 统计 ----------------

    /** 池级聚合：地图总数/启用数/按类型分布/累计Ban/Pick。 */
    public Map<String, Object> stats(String poolId) {
        List<MapEntry> list = entries(poolId);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("pool_id", poolId);
        res.put("total", list.size());
        res.put("active", list.stream().filter(MapEntry::isActive).count());
        res.put("ban_total", list.stream().mapToInt(MapEntry::getBanCount).sum());
        res.put("pick_total", list.stream().mapToInt(MapEntry::getPickCount).sum());
        Map<String, Long> byType = new LinkedHashMap<>();
        for (MapEntry e : list) {
            if (e.getMapType() != null && !e.getMapType().isBlank()) {
                byType.merge(e.getMapType(), 1L, Long::sum);
            }
        }
        res.put("by_type", byType);
        return res;
    }

    private void bumpPoolMapCount(String poolId, int delta) {
        poolRepository.findById(poolId).ifPresent(p -> {
            p.setMapCount(Math.max(0, p.getMapCount() + delta));
            p.setUpdatedAt(Instant.now());
            poolRepository.save(p);
        });
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}