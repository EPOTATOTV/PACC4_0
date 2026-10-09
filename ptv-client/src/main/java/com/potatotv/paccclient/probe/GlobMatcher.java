package com.potatotv.paccclient.probe;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 通配匹配（{@code *} 任意长度、{@code ?} 单字符），用于进程名 / 窗口标题 / 文件路径匹配。
 *
 * <p>路径分隔符 {@code \} 与 {@code /} 在比较前统一成 {@code /}，避免同一模式在 Windows 上
 * 因反斜杠匹配不到。{@code *} 会跨越路径分隔符 —— 文档里的模式（如 {@code *&#47;Cheat Engine&#47;*}）
 * 依赖这个语义。</p>
 *
 * <p>编译结果按「模式 + 大小写」缓存：本类被进程 / 模块 / 文件等扫描热循环高频调用，
 * 每次 {@link Pattern#compile} 的开销可观。缓存有上限，超过后新模式不再入缓存，避免无界增长。</p>
 */
public final class GlobMatcher {

    /** 编译缓存条数上限（模式集合来自内置签名与云端规则，实际远小于此值）。 */
    private static final int CACHE_LIMIT = 1024;
    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    private GlobMatcher() {
    }

    /** 大小写不敏感的匹配。 */
    public static boolean matches(String glob, String text) {
        return matches(glob, text, true);
    }

    /**
     * 通配匹配。
     *
     * @param glob       通配模式
     * @param text       待匹配文本
     * @param ignoreCase 是否忽略大小写（进程名 / 路径在 Windows 上不区分大小写）
     */
    public static boolean matches(String glob, String text, boolean ignoreCase) {
        if (glob == null || text == null) {
            return false;
        }
        String regex = toRegex(normalize(glob));
        int flags = ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        return compiled(regex, ignoreCase, flags).matcher(normalize(text)).matches();
    }

    private static Pattern compiled(String regex, boolean ignoreCase, int flags) {
        String key = (ignoreCase ? "i:" : "s:") + regex;
        Pattern cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Pattern pattern = Pattern.compile(regex, flags);
        if (CACHE.size() < CACHE_LIMIT) {
            CACHE.putIfAbsent(key, pattern);
        }
        return pattern;
    }

    private static String normalize(String s) {
        return s.replace('\\', '/');
    }

    /** 把通配模式转成等价正则（转义正则元字符，仅保留 {@code *} / {@code ?} 的通配语义）。 */
    private static String toRegex(String glob) {
        StringBuilder sb = new StringBuilder(glob.length() + 8).append('^');
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.append('$').toString();
    }
}