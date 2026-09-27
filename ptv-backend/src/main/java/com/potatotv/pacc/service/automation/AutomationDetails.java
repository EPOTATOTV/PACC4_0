package com.potatotv.pacc.service.automation;

/**
 * 执行明细解析工具：动作处理器把回滚所需的前值编码进 {@code detail}，
 * 回滚时由此解析还原（形如 {@code sensitivity 1.0->1.5; alert=xxx} 或 {@code nc=true;ri=1.0}）。
 */
final class AutomationDetails {

    private AutomationDetails() {
    }

    /** 解析 {@code old->new} 形态的首个数值对；缺失返回 {@code {NaN, NaN}}。 */
    static double[] arrow(String detail) {
        if (detail == null || !detail.contains("->")) {
            return new double[] { Double.NaN, Double.NaN };
        }
        int idx = detail.indexOf("->");
        return new double[] { lastNumber(detail.substring(0, idx)), firstNumber(detail.substring(idx + 2)) };
    }

    /** 取 {@code key=value} 中的 value（value 截至 {@code ;} 或串尾）。 */
    static String token(String detail, String key) {
        if (detail == null) {
            return null;
        }
        String marker = key + "=";
        int i = detail.indexOf(marker);
        if (i < 0) {
            return null;
        }
        int start = i + marker.length();
        int end = detail.indexOf(';', start);
        String v = end < 0 ? detail.substring(start) : detail.substring(start, end);
        return v.trim().isEmpty() ? null : v.trim();
    }

    static boolean boolToken(String detail, String key, boolean defaultValue) {
        String v = token(detail, key);
        return v == null ? defaultValue : Boolean.parseBoolean(v);
    }

    static double numToken(String detail, String key, double defaultValue) {
        String v = token(detail, key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double firstNumber(String s) {
        String num = capture(s, true);
        return num == null ? Double.NaN : Double.parseDouble(num);
    }

    private static double lastNumber(String s) {
        String num = capture(s, false);
        return num == null ? Double.NaN : Double.parseDouble(num);
    }

    private static String capture(String s, boolean first) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(s);
        String found = null;
        while (m.find()) {
            if (first) {
                return m.group();
            }
            found = m.group();
        }
        return found;
    }
}