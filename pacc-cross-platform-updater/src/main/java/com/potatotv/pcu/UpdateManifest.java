package com.potatotv.pcu;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /v1/update/check} 的响应模型（设计文档 §4.11）。
 *
 * <p>服务端在 §4.4 里用扁平字段（{@code delta_url}/{@code delta_checksum}/{@code delta_size}）描述差分包，
 * 在 §4.11 里用嵌套的 {@code delta} 对象描述，两种形态都能解析：优先取嵌套对象，
 * 缺失时回落到扁平字段，这样两种写法在线上混用时不会把端侧更新链路打断。</p>
 */
public record UpdateManifest(
        boolean hasUpdate,
        String platform,
        String latestVersion,
        String downloadUrl,
        String checksum,
        long size,
        boolean forceUpdate,
        String changelog,
        DeltaInfo delta,
        String minAppVersion,
        String signature) {

    public static UpdateManifest fromJson(Object json) {
        boolean hasUpdate = PcuJson.bool(json, "has_update", false);
        String platform = PcuJson.str(json, "platform");
        String latest = PcuJson.str(json, "latest_version");
        String url = PcuJson.str(json, "download_url");
        String checksum = PcuJson.str(json, "checksum");
        long size = PcuJson.num(json, "size", 0L);
        boolean force = PcuJson.bool(json, "force_update", false);
        String changelog = PcuJson.str(json, "changelog");
        String minAppVersion = PcuJson.str(json, "min_app_version");
        String signature = PcuJson.str(json, "signature");

        DeltaInfo delta = parseDelta(json);
        UpdateManifest manifest = new UpdateManifest(hasUpdate, platform, latest, url, checksum, size,
                force, changelog, delta, minAppVersion, signature);
        manifest.validate();
        return manifest;
    }

    private static DeltaInfo parseDelta(Object json) {
        Map<String, Object> nested = PcuJson.object(json, "delta");
        if (nested != null) {
            String url = PcuJson.str(nested, "url");
            if (url != null && !url.isBlank()) {
                return new DeltaInfo(
                        PcuJson.str(nested, "from_version"),
                        url,
                        PcuJson.str(nested, "checksum"),
                        PcuJson.num(nested, "size", 0L));
            }
        }
        String flatUrl = PcuJson.str(json, "delta_url");
        if (flatUrl != null && !flatUrl.isBlank()) {
            return new DeltaInfo(
                    PcuJson.str(json, "delta_from_version"),
                    flatUrl,
                    PcuJson.str(json, "delta_checksum"),
                    PcuJson.num(json, "delta_size", 0L));
        }
        return null;
    }

    /**
     * 说「有更新」就必须给出可下载的地址与校验和：缺失时当作服务端配置错误直接失败，
     * 而不是让端侧下载一个无法校验的包。
     */
    private void validate() {
        if (!hasUpdate) {
            return;
        }
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new PcuException("服务端返回 has_update=true 但没有 download_url");
        }
        if (latestVersion == null || latestVersion.isBlank()) {
            throw new PcuException("服务端返回 has_update=true 但没有 latest_version");
        }
        if (checksum == null || checksum.isBlank()) {
            throw new PcuException("服务端返回 has_update=true 但没有 checksum");
        }
    }

    /** 序列化：只在服务端与测试里用到（端侧只读不写）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("has_update", hasUpdate);
        if (platform != null) {
            m.put("platform", platform);
        }
        m.put("latest_version", latestVersion);
        m.put("download_url", downloadUrl);
        m.put("checksum", checksum);
        m.put("size", size);
        m.put("force_update", forceUpdate);
        m.put("changelog", changelog);
        if (minAppVersion != null) {
            m.put("min_app_version", minAppVersion);
        }
        if (signature != null) {
            m.put("signature", signature);
        }
        if (delta != null) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("from_version", delta.fromVersion());
            d.put("url", delta.url());
            d.put("checksum", delta.checksum());
            d.put("size", delta.size());
            m.put("delta", d);
        }
        return m;
    }
}