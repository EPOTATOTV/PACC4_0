package com.potatotv.pacc.service.detection.v52;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * v5.2 §7.1/§7.2 设备指纹登记：客户端上报硬件指纹摘要后，落库、判定突变并联动信誉分。
 *
 * <p>与 {@link BehaviorProfileService#deviceFingerprintSeen} 的关系：那个只负责「记录 + 是否首次」，
 * 本类补齐业务动作——新设备登录扣 {@value #DELTA_NEW_DEVICE}（§7.2 规则为 -10，见
 * {@link ReputationV2Service.Event#NEW_DEVICE_LOGIN}），新指纹使该玩家持有多枚指纹（设备突变）
 * 时再按突变扣分（-30）。两笔扣分都以指纹摘要作为来源事件 id，同一设备重复上报只扣一次。</p>
 *
 * <p>只接受 32-128 位十六进制摘要：服务端不接收明文指纹，也不做任何指纹拼接（拼接等于伪造设备身份）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceFingerprintService {

    /** 指纹摘要格式：小写/大写十六进制均可，长度 32-128（SHA-256 为 64）。 */
    private static final Pattern HASH = Pattern.compile("^[0-9a-fA-F]{32,128}$");

    private static final int DELTA_NEW_DEVICE = -10;

    private final BehaviorProfileService profiles;
    private final ReputationV2Service reputation;

    /** 登记结果。 */
    public record Registration(boolean firstSeen, boolean mutation, int reputationScore) {
    }

    /**
     * 登记一次设备指纹。
     *
     * @param pteid           玩家
     * @param fingerprintHash 指纹摘要（十六进制）
     * @return 是否首次出现、是否构成设备突变、登记后的信誉分
     * @throws IllegalArgumentException 玩家或摘要非法
     */
    @Transactional
    public Registration register(String pteid, String fingerprintHash) {
        if (pteid == null || pteid.isBlank()) throw new IllegalArgumentException("pteid 不能为空");
        if (fingerprintHash == null || !HASH.matcher(fingerprintHash.trim()).matches()) {
            throw new IllegalArgumentException("指纹摘要必须是 32-128 位十六进制字符串");
        }
        String hash = fingerprintHash.trim().toLowerCase(Locale.ROOT);

        boolean firstSeen = profiles.deviceFingerprintSeen(pteid, hash);
        // 首次出现且该玩家已持有其他指纹 → 设备突变（账号共享 / 盗号 / 换机）
        boolean mutation = firstSeen && profiles.hasDeviceMutation(pteid);
        if (firstSeen) {
            reputation.apply(pteid, ReputationV2Service.Event.NEW_DEVICE_LOGIN, hash, "新设备登录");
        }
        if (mutation) {
            reputation.apply(pteid, ReputationV2Service.Event.FINGERPRINT_MUTATION, hash, "硬件指纹突变");
        }
        int score = reputation.score(pteid);
        if (firstSeen) {
            log.info("设备指纹登记 pteid={} 首次={} 突变={} 信誉={} hash={}",
                    pteid, true, mutation, score, hash.substring(0, Math.min(8, hash.length())));
        }
        return new Registration(firstSeen, mutation, score);
    }
}