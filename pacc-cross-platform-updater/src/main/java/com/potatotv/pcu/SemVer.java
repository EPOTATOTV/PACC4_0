package com.potatotv.pcu;

/**
 * 语义化版本比较：服务端与端侧都用它判断「是否有新版本」与「是否在降级」。
 *
 * <p>只解析 {@code major.minor.patch}（允许 {@code v} 前缀、允许缺省段、允许
 * {@code -pre} 预发布后缀与 {@code +build} 构建元数据）。预发布版本排在正式版之前，
 * 这与 SemVer 2.0 一致；构建元数据不参与比较。</p>
 */
public final class SemVer implements Comparable<SemVer> {

    private final int major;
    private final int minor;
    private final int patch;
    /** 预发布标识，正式版为 null。 */
    private final String preRelease;

    private SemVer(int major, int minor, int patch, String preRelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.preRelease = preRelease;
    }

    public static SemVer parse(String text) {
        if (text == null || text.isBlank()) {
            throw new PcuException("版本号为空");
        }
        String s = text.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        int plus = s.indexOf('+');
        if (plus >= 0) {
            s = s.substring(0, plus);
        }
        String pre = null;
        int dash = s.indexOf('-');
        if (dash >= 0) {
            pre = s.substring(dash + 1);
            s = s.substring(0, dash);
            if (pre.isEmpty()) {
                throw new PcuException("版本号预发布段为空：" + text);
            }
        }
        String[] parts = s.split("\\.", -1);
        if (parts.length == 0 || parts.length > 3) {
            throw new PcuException("版本号段数非法：" + text);
        }
        int[] nums = new int[3];
        for (int i = 0; i < parts.length; i++) {
            nums[i] = parseSegment(parts[i], text);
        }
        return new SemVer(nums[0], nums[1], nums[2], pre);
    }

    /** 宽松解析：失败时返回 null，用于处理服务端字段缺失/格式异常。 */
    public static SemVer tryParse(String text) {
        try {
            return parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int parseSegment(String segment, String whole) {
        if (segment.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < segment.length(); i++) {
            if (!Character.isDigit(segment.charAt(i))) {
                throw new PcuException("版本号含非数字段：" + whole);
            }
        }
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException e) {
            throw new PcuException("版本号数值溢出：" + whole, e);
        }
    }

    @Override
    public int compareTo(SemVer other) {
        int c = Integer.compare(major, other.major);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minor, other.minor);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(patch, other.patch);
        if (c != 0) {
            return c;
        }
        return comparePreRelease(preRelease, other.preRelease);
    }

    private static int comparePreRelease(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        // 正式版高于任何预发布版
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        String[] sa = a.split("\\.");
        String[] sb = b.split("\\.");
        int n = Math.min(sa.length, sb.length);
        for (int i = 0; i < n; i++) {
            int c = compareIdentifier(sa[i], sb[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(sa.length, sb.length);
    }

    private static int compareIdentifier(String a, String b) {
        boolean na = isDigits(a);
        boolean nb = isDigits(b);
        if (na && nb) {
            return compareNumeric(a, b);
        }
        if (na) {
            return -1; // 数字标识低于字母标识
        }
        if (nb) {
            return 1;
        }
        return a.compareTo(b);
    }

    /**
     * 数字标识按数值比较，但不经过 {@code Long.parseLong}：SemVer 对数字标识没有位数上限，
     * 预发布段里塞一长串数字（{@code 5.4.0-202401011234567890123456789}）会让 parseLong 抛
     * NumberFormatException。位数不同位数多的更大，位数相同时字典序就等于数值序。
     */
    private static int compareNumeric(String a, String b) {
        String x = stripLeadingZeros(a);
        String y = stripLeadingZeros(b);
        if (x.length() != y.length()) {
            return x.length() > y.length() ? 1 : -1;
        }
        return x.compareTo(y);
    }

    private static String stripLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    public String preRelease() {
        return preRelease;
    }

    @Override
    public String toString() {
        String s = major + "." + minor + "." + patch;
        return preRelease == null ? s : s + "-" + preRelease;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SemVer other && compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }
}