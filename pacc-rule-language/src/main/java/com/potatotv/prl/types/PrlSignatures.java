package com.potatotv.prl.types;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.potatotv.prl.types.HostFunction.Param;
import static com.potatotv.prl.types.HostFunction.of;
import static com.potatotv.prl.types.HostFunction.param;

/**
 * 内置函数签名表（设计文档 §2.4）。
 *
 * <p>这张表是编译期白名单的一部分（§2.11.1 的 L3「只能调用 PACC 注册的函数」）：规则里出现表外的
 * 函数名一律编译失败，而不是留到运行时才炸。实现不在本类，宿主可经 §2.11.2 的
 * {@code callFunction} 接管，宿主没接管的名字由本模块的标准库兜底。</p>
 *
 * <p>几处与文档字面不同的处理，都记在 {@link #standard()} 的实现里。</p>
 */
public final class PrlSignatures {

    /** 泛型占位符，与 §2.4.3 的 {@code filter(list[T], (T) -> bool)} 写法对齐。 */
    private static final PrlType T = PrlType.var("T");
    private static final PrlType U = PrlType.var("U");
    private static final PrlType K = PrlType.var("K");
    private static final PrlType A = PrlType.var("A");
    private static final PrlType B = PrlType.var("B");

    /** 文档签名里的 {@code Duration}，单位统一归一化成毫秒（§2.2.3）。 */
    private static final PrlType DURATION = PrlType.INT;

    /** 文档签名里的 {@code Event}：规则不关心具体事件类型，匹配一切。 */
    private static final PrlType EVENT = PrlType.ANY;

    private static final PrlType LIST_INT = PrlType.list(PrlType.INT);
    private static final PrlType LIST_FLOAT = PrlType.list(PrlType.FLOAT);
    private static final PrlType LIST_STRING = PrlType.list(PrlType.STRING);

    private final Map<String, List<HostFunction>> functions = new LinkedHashMap<>();

    /**
     * 文档 §2.4 的全部内置函数。
     *
     * <p>与文档字面不同的地方：</p>
     * <ol>
     *   <li>{@code Duration} 参数按 {@code int}（毫秒）登记，{@code Event} 参数按 {@code any} 登记，
     *       理由见 {@link #DURATION}、{@link #EVENT} 的注释；</li>
     *   <li>{@code abs}/{@code min}/{@code max}/{@code sum}/{@code clamp} 在文档里写作
     *       {@code (number) -> number}，PRL 没有隐式数值转换（§2.3.5），因此拆成 int/float 两个重载，
     *       返回值类型才是确定的；</li>
     *   <li>{@code average}/{@code median}/{@code stddev}/{@code variance}/{@code percentile} 的返回
     *       固定是 float，所以入参保留 {@code number} 模式，一个签名同时接受 int 与 float 列表；</li>
     *   <li>{@code emit_alert} 文档写 {@code -> void}，但 §2.12.2 的 {@code RuleManager.executeAll}
     *       依赖 {@code vm.execute} 的返回值是 {@code DetectionResult}，因此这里登记为返回
     *       {@code DetectionResult}。规则里当语句用即可，返回值只在宿主侧有意义；</li>
     *   <li>{@code to_float} 来自 §2.3.5（「需显式 {@code to_float(x)}」），{@code to_int}、
     *       {@code to_string} 是它的同类转换，一并补齐；</li>
     *   <li>{@code accuracy}、{@code last_n} 出现在 §2.2.1 的示例规则里但 §2.4 的函数表没列，
     *       按示例补齐 —— 否则文档里的规则示例过不了类型检查。</li>
     * </ol>
     */
    public static PrlSignatures standard() {
        PrlSignatures s = new PrlSignatures();

        // ---- §2.4.1 统计 ----
        s.register(of("average", PrlType.FLOAT, "平均值", param("values", PrlType.list(PrlType.NUMBER))));
        s.register(of("median", PrlType.FLOAT, "中位数", param("values", PrlType.list(PrlType.NUMBER))));
        s.register(of("stddev", PrlType.FLOAT, "标准差", param("values", PrlType.list(PrlType.NUMBER))));
        s.register(of("variance", PrlType.FLOAT, "方差", param("values", PrlType.list(PrlType.NUMBER))));
        s.register(of("min", PrlType.INT, "最小值", param("values", LIST_INT)));
        s.register(of("min", PrlType.FLOAT, "最小值", param("values", LIST_FLOAT)));
        s.register(of("max", PrlType.INT, "最大值", param("values", LIST_INT)));
        s.register(of("max", PrlType.FLOAT, "最大值", param("values", LIST_FLOAT)));
        s.register(of("percentile", PrlType.FLOAT, "百分位数",
                param("values", PrlType.list(PrlType.NUMBER)), param("percent", PrlType.NUMBER)));
        s.register(of("sum", PrlType.INT, "求和", param("values", LIST_INT)));
        s.register(of("sum", PrlType.FLOAT, "求和", param("values", LIST_FLOAT)));
        s.register(of("count", PrlType.INT, "元素个数", param("values", PrlType.list(T))));
        s.register(of("rate", PrlType.FLOAT, "单位时间事件数",
                param("events", PrlType.list(EVENT)), param("window", DURATION)));
        s.register(of("frequency", PrlType.map(T, PrlType.INT), "频率分布",
                param("values", PrlType.list(T))));
        s.register(of("correlation", PrlType.FLOAT, "皮尔逊相关系数",
                param("xs", PrlType.list(PrlType.NUMBER)), param("ys", PrlType.list(PrlType.NUMBER))));
        s.register(of("zscore", PrlType.FLOAT, "Z 分数",
                param("value", PrlType.NUMBER), param("values", PrlType.list(PrlType.NUMBER))));
        s.register(of("outliers", PrlType.list(PrlType.INT), "异常值索引（IQR）",
                param("values", PrlType.list(PrlType.NUMBER)), param("factor", PrlType.NUMBER)));

        // ---- §2.4.2 时序 ----
        s.register(of("sliding_window", PrlType.list(PrlType.list(T)), "滑动窗口",
                param("events", PrlType.list(T)), param("window", DURATION)));
        s.register(of("burst_count", PrlType.INT, "突发次数",
                param("events", PrlType.list(EVENT)), param("window", DURATION), param("threshold", PrlType.INT)));
        s.register(of("interval_stats", PrlType.host("Stats"), "事件间隔统计", param("events", PrlType.list(T))));
        s.register(of("trend", PrlType.FLOAT, "线性趋势斜率", param("values", PrlType.list(PrlType.NUMBER))));
        s.register(of("change_rate", PrlType.FLOAT, "变化率", param("values", PrlType.list(PrlType.NUMBER))));

        // ---- §2.4.3 集合 ----
        s.register(of("filter", PrlType.list(T), "过滤",
                param("values", PrlType.list(T)), param("predicate", PrlType.func(List.of(T), PrlType.BOOL))));
        s.register(of("map", PrlType.list(U), "映射",
                param("values", PrlType.list(T)), param("transform", PrlType.func(List.of(T), U))));
        s.register(of("reduce", U, "归约",
                param("values", PrlType.list(T)), param("initial", U),
                param("folder", PrlType.func(List.of(U, T), U))));
        s.register(of("sort", PrlType.list(T), "排序",
                param("values", PrlType.list(T)), param("comparator", PrlType.func(List.of(T, T), PrlType.INT))));
        s.register(of("group_by", PrlType.map(K, PrlType.list(T)), "分组",
                param("values", PrlType.list(T)), param("key", PrlType.func(List.of(T), K))));
        s.register(of("first", T, "第一个元素", param("values", PrlType.list(T))));
        s.register(of("last", T, "最后一个元素", param("values", PrlType.list(T))));
        s.register(of("take", PrlType.list(T), "取前 N 个",
                param("values", PrlType.list(T)), param("n", PrlType.INT)));
        s.register(of("skip", PrlType.list(T), "跳过前 N 个",
                param("values", PrlType.list(T)), param("n", PrlType.INT)));
        s.register(of("contains", PrlType.BOOL, "是否包含",
                param("values", PrlType.list(T)), param("value", T)));
        s.register(of("contains", PrlType.BOOL, "是否包含子串",
                param("text", PrlType.STRING), param("substring", PrlType.STRING)));
        s.register(of("unique", PrlType.list(T), "去重", param("values", PrlType.list(T))));
        s.register(of("flatten", PrlType.list(T), "展平", param("values", PrlType.list(PrlType.list(T)))));
        s.register(of("zip", PrlType.list(PrlType.tuple(List.of(A, B))), "拉链",
                param("left", PrlType.list(A)), param("right", PrlType.list(B))));

        // ---- §2.4.4 字符串 ----
        s.register(of("length", PrlType.INT, "长度", param("text", PrlType.STRING)));
        s.register(of("starts_with", PrlType.BOOL, "前缀匹配",
                param("text", PrlType.STRING), param("prefix", PrlType.STRING)));
        s.register(of("ends_with", PrlType.BOOL, "后缀匹配",
                param("text", PrlType.STRING), param("suffix", PrlType.STRING)));
        s.register(of("regex_match", PrlType.BOOL, "正则匹配",
                param("text", PrlType.STRING), param("pattern", PrlType.STRING)));
        s.register(of("regex_extract", LIST_STRING, "正则提取",
                param("text", PrlType.STRING), param("pattern", PrlType.STRING)));
        s.register(of("to_lower", PrlType.STRING, "转小写", param("text", PrlType.STRING)));
        s.register(of("to_upper", PrlType.STRING, "转大写", param("text", PrlType.STRING)));
        s.register(of("trim", PrlType.STRING, "去首尾空白", param("text", PrlType.STRING)));
        s.register(of("split", LIST_STRING, "分割",
                param("text", PrlType.STRING), param("separator", PrlType.STRING)));
        s.register(of("join", PrlType.STRING, "连接",
                param("parts", LIST_STRING), param("separator", PrlType.STRING)));
        s.register(of("levenshtein", PrlType.INT, "编辑距离",
                param("left", PrlType.STRING), param("right", PrlType.STRING)));
        s.register(of("similarity", PrlType.FLOAT, "文本相似度",
                param("left", PrlType.STRING), param("right", PrlType.STRING)));

        // ---- §2.4.5 数学 ----
        s.register(of("abs", PrlType.INT, "绝对值", param("value", PrlType.INT)));
        s.register(of("abs", PrlType.FLOAT, "绝对值", param("value", PrlType.FLOAT)));
        s.register(of("round", PrlType.FLOAT, "四舍五入",
                param("value", PrlType.FLOAT), param("digits", PrlType.INT)));
        s.register(of("floor", PrlType.INT, "向下取整", param("value", PrlType.FLOAT)));
        s.register(of("ceil", PrlType.INT, "向上取整", param("value", PrlType.FLOAT)));
        s.register(of("clamp", PrlType.INT, "限制范围",
                param("value", PrlType.INT), param("lower", PrlType.INT), param("upper", PrlType.INT)));
        s.register(of("clamp", PrlType.FLOAT, "限制范围",
                param("value", PrlType.FLOAT), param("lower", PrlType.FLOAT), param("upper", PrlType.FLOAT)));
        s.register(of("sqrt", PrlType.FLOAT, "平方根", param("value", PrlType.FLOAT)));
        s.register(of("pow", PrlType.FLOAT, "幂",
                param("base", PrlType.FLOAT), param("exponent", PrlType.FLOAT)));
        s.register(of("log", PrlType.FLOAT, "对数",
                param("value", PrlType.FLOAT), param("base", PrlType.FLOAT)));
        s.register(of("sin", PrlType.FLOAT, "正弦", param("value", PrlType.FLOAT)));
        s.register(of("cos", PrlType.FLOAT, "余弦", param("value", PrlType.FLOAT)));
        s.register(of("tan", PrlType.FLOAT, "正切", param("value", PrlType.FLOAT)));
        s.register(of("random", PrlType.INT, "确定性随机数（可复现）",
                param("lower", PrlType.INT), param("upper", PrlType.INT)));

        // ---- §2.4.6 PACC 专属检测 ----
        // analyze_click_pattern 在 §2.4.6 的签名是 list[ClickEvent]，但 §2.2.1 的示例规则把
        // list[AttackEvent] 传了进来；两处都要能用，因此各登记一个签名。
        s.register(of("analyze_click_pattern", PrlType.host("ClickPattern"), "点击模式分析",
                param("events", PrlType.list(PrlType.host("ClickEvent")))));
        s.register(of("analyze_click_pattern", PrlType.host("ClickPattern"), "点击模式分析（§2.2.1 传的是攻击事件）",
                param("events", PrlType.list(PrlType.host("AttackEvent")))));
        s.register(of("detect_aim_assist", PrlType.FLOAT, "自瞄检测置信度",
                param("events", PrlType.list(PrlType.host("MouseMoveEvent")))));
        s.register(of("detect_speed_hack", PrlType.FLOAT, "加速检测置信度",
                param("events", PrlType.list(PrlType.host("MoveEvent")))));
        s.register(of("detect_fly", PrlType.FLOAT, "飞行检测置信度",
                param("events", PrlType.list(PrlType.host("MoveEvent")))));
        s.register(of("detect_reach", PrlType.FLOAT, "reach 检测置信度",
                param("events", PrlType.list(PrlType.host("AttackEvent")))));
        s.register(of("detect_autoclicker", PrlType.FLOAT, "连点器检测置信度",
                param("events", PrlType.list(PrlType.host("ClickEvent")))));
        s.register(of("is_human_click_pattern", PrlType.BOOL, "是否为人类点击模式",
                param("pattern", PrlType.host("ClickPattern"))));
        s.register(of("entropy", PrlType.FLOAT, "信息熵", param("values", PrlType.list(PrlType.NUMBER))));
        s.register(of("cps_variance", PrlType.FLOAT, "CPS 方差",
                param("events", PrlType.list(PrlType.host("ClickEvent")))));
        s.register(of("reaction_time", PrlType.FLOAT, "平均反应时间", param("events", PrlType.list(EVENT))));

        // ---- §2.4.7 动作（副作用） ----
        s.register(of("emit_alert", PrlType.host("DetectionResult"), "发出告警",
                param("type", PrlType.STRING), param("confidence", PrlType.FLOAT),
                param("evidence", PrlType.map(PrlType.ANY, PrlType.ANY))));
        s.register(of("record_evidence", PrlType.VOID, "记录证据",
                param("key", PrlType.STRING), param("value", PrlType.ANY)));
        s.register(of("trigger_redscreen", PrlType.VOID, "触发红屏", param("reason", PrlType.STRING)));
        s.register(of("log", PrlType.VOID, "写日志",
                param("level", PrlType.STRING), param("message", PrlType.STRING)));
        s.register(of("ban_feature", PrlType.VOID, "临时禁用某功能（非封禁玩家）",
                param("feature", PrlType.STRING), param("duration", DURATION)));

        // ---- §2.3.5 显式转换 ----
        s.register(of("to_float", PrlType.FLOAT, "转 float", param("value", PrlType.NUMBER)));
        s.register(of("to_int", PrlType.INT, "转 int", param("value", PrlType.NUMBER)));
        s.register(of("to_string", PrlType.STRING, "转 string", param("value", PrlType.ANY)));

        // ---- §2.2.1 示例规则用到、§2.4 未列出的两个 ----
        s.register(of("accuracy", PrlType.FLOAT, "命中率",
                param("events", PrlType.list(PrlType.host("AttackEvent")))));
        s.register(of("last_n", PrlType.list(T), "取末尾 N 个",
                param("values", PrlType.list(T)), param("n", PrlType.INT)));

        return s;
    }

    /** 空表，用于测试或宿主完全自管函数集。 */
    public static PrlSignatures empty() {
        return new PrlSignatures();
    }

    /** 追加一个签名；同名已有签名时作为重载追加。 */
    public PrlSignatures register(HostFunction function) {
        functions.computeIfAbsent(function.name(), key -> new ArrayList<>()).add(function);
        return this;
    }

    /** 同名重载列表；函数不存在时返回空列表。 */
    public List<HostFunction> lookup(String name) {
        return functions.getOrDefault(name, List.of());
    }

    public boolean known(String name) {
        return functions.containsKey(name);
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(functions.keySet());
    }

    /** 全部签名，按注册顺序。 */
    public List<HostFunction> all() {
        List<HostFunction> all = new ArrayList<>();
        for (List<HostFunction> overloads : functions.values()) {
            all.addAll(overloads);
        }
        return all;
    }
}