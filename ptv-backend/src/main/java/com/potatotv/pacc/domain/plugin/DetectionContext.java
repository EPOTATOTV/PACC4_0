package com.potatotv.pacc.domain.plugin;

import com.potatotv.pacc.domain.FeatureVector;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 沙箱注入给插件的受限执行上下文：只暴露检测所需的观测面（特征向量 / 玩家标识 / 版本 / 租户），
 * 不暴露数据库、文件系统、网络等特权能力——特权调用必须在沙箱 API 白名单内显式声明。
 */
public final class DetectionContext {

    private final FeatureVector featureVector;
    private final String playerId;
    private final String edition;
    private final String tenantId;
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    public DetectionContext(FeatureVector featureVector, String playerId, String edition, String tenantId) {
        this.featureVector = featureVector;
        this.playerId = playerId;
        this.edition = edition;
        this.tenantId = tenantId;
    }

    public FeatureVector getFeatureVector() {
        return featureVector;
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getEdition() {
        return edition;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    /** 只读观测面：插件可读取特征但不可回写原始向量。 */
    public double feature(String key) {
        return featureVector == null ? 0.0 : featureVector.get(key);
    }
}