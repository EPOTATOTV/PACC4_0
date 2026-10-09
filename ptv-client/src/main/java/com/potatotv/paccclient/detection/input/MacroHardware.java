package com.potatotv.paccclient.detection.input;

import java.util.Locale;
import java.util.Set;

/**
 * 板载宏可编程设备与宏软件名单（文档 §3.5）。
 *
 * <p>这些 VID / 进程名只是「板载宏可能存在的硬件线索」：罗技、雷蛇、赛睿、海盗船、HyperX、冰豹等
 * 游戏外设本身完全合规，单凭设备出现在机器上绝不判定作弊，必须与时序 / 宏文件等多维信号叠加才计分。</p>
 */
public final class MacroHardware {

    /** 板载宏可编程外设厂商 ID（十六进制，不含 {@code VID_} 前缀）。 */
    public static final Set<String> MACRO_CAPABLE_VIDS = Set.of(
            "046D", // 罗技 Logitech
            "1532", // 雷蛇 Razer
            "1038", // 赛睿 SteelSeries
            "1B1C", // 海盗船 Corsair
            "0951", // HyperX
            "1E7D"); // 冰豹 Roccat

    /** 宏软件进程名（小写）。 */
    public static final Set<String> MACRO_SOFTWARE = Set.of(
            "lghub.exe", "lcore.exe", "logigaming.exe",
            "razersynapse.exe", "rzsynapse.exe", "synapse.exe",
            "steelseriesengine.exe", "sse.exe",
            "icue.exe", "corsaircue.exe",
            "hyperxngenuity.exe",
            "roccatswarm.exe");

    private MacroHardware() {
    }

    /** 设备 VID/PID（形如 {@code VID_046D&PID_C52B}）是否为板载宏可编程外设。 */
    public static boolean isMacroCapable(String vidPid) {
        if (vidPid == null || vidPid.isBlank()) {
            return false;
        }
        String v = vidPid.toUpperCase(Locale.ROOT);
        int i = v.indexOf("VID_");
        if (i < 0) {
            return false;
        }
        int start = i + 4;
        int end = start;
        while (end < v.length() && isHex(v.charAt(end))) {
            end++;
        }
        if (end == start) {
            return false;
        }
        return MACRO_CAPABLE_VIDS.contains(v.substring(start, end));
    }

    /** 进程名是否为宏软件（大小写不敏感）。 */
    public static boolean isMacroSoftware(String processName) {
        return processName != null && !processName.isBlank()
                && MACRO_SOFTWARE.contains(processName.toLowerCase(Locale.ROOT));
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
    }
}