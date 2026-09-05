package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.CheatHardwareProfile;
import com.potatotv.pacc.domain.CheatHardwareProfile.DeviceClass;
import com.potatotv.pacc.repository.CheatHardwareProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * v4.5 硬件级作弊检测（服务端判定层）。
 * <p>玩家端上报外设指纹（VID/PID、设备类、设备字符串），这里与 {@code CheatHardwareProfile}
 * 指纹库做三类匹配：</p>
 * <ul>
 *   <li>VID+PID 精确匹配（已知作弊设备）</li>
 *   <li>设备类匹配（未知 PCIe FPGA / 数据采集卡 → DMA 风险）</li>
 *   <li>设备字符串指纹匹配（子串规则，覆盖多型号变体）</li>
 * </ul>
 * 命中返回匹配到的最高风险分；未命中返回 0。
 */
@Service
@RequiredArgsConstructor
public class HardwareFingerprintService {

    private final CheatHardwareProfileRepository repository;

    @Value("${pacc.countermeasure.hardware-match-score:70}")
    private int hardwareMatchFloor = 70;

    public record HardwareReport(String vid, String pid, DeviceClass deviceClass, String deviceString) {
    }

    public record HardwareVerdict(boolean matched, String matchedId, String cheatFlag, int score, String reason) {
        static HardwareVerdict none(int floor) {
            return new HardwareVerdict(false, null, null, Math.min(floor, 40), "no_matched_hardware");
        }
    }

    /**
     * 判读一组外设上报，返回最高风险的那一条命中。
     */
    public HardwareVerdict scan(List<HardwareReport> reports) {
        List<CheatHardwareProfile> profiles = repository.findByActiveTrueOrderByRiskScoreDesc();
        HardwareVerdict best = null;
        if (reports != null) {
            for (HardwareReport r : reports) {
                HardwareVerdict v = matchOne(r, profiles);
                if (v == null) continue;
                if (best == null || v.score() > best.score()) best = v;
            }
        }
        return best != null ? best : HardwareVerdict.none(hardwareMatchFloor);
    }

    private HardwareVerdict matchOne(HardwareReport r, List<CheatHardwareProfile> profiles) {
        for (CheatHardwareProfile p : profiles) {
            String reason = reasonFor(r, p);
            if (reason == null) continue;
            int score = Math.max(p.getRiskScore(), hardwareMatchFloor);
            return new HardwareVerdict(true, p.getId(), p.getCheatFlag().name(), score, reason);
        }
        return null;
    }

    private String reasonFor(HardwareReport r, CheatHardwareProfile p) {
        // 1) VID+PID 精确匹配
        if (nonBlank(p.getVid()) && nonBlank(p.getPid())
                && p.getVid().equalsIgnoreCase(normalize(r.vid()))
                && p.getPid().equalsIgnoreCase(normalize(r.pid()))) {
            return "vid_pid_match";
        }
        // 2) 设备类匹配：仅针对 DMA 类设备（PCIe FPGA / 数据采集卡）按类兜底，
        //    未知 VID/PID 的 PCIe 设备即使无指纹也判 DMA 风险；USB_HID 类太宽泛，不类判。
        if (p.getVid() == null && p.getDeviceClass() != null
                && (p.getDeviceClass() == DeviceClass.PCIE_FPGA || p.getDeviceClass() == DeviceClass.PCIE_DATA_ACQ)
                && p.getDeviceClass() == r.deviceClass()) {
            return "device_class_match:" + r.deviceClass();
        }
        // 3) 设备字符串指纹（子串）
        if (nonBlank(p.getFingerprintPatterns()) && nonBlank(r.deviceString())) {
            String dev = r.deviceString().toLowerCase(Locale.ROOT);
            for (String pat : p.getFingerprintPatterns().split(",")) {
                if (!pat.isBlank() && dev.contains(pat.trim().toLowerCase(Locale.ROOT))) {
                    return "fingerprint_match:" + pat.trim();
                }
            }
        }
        return null;
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}