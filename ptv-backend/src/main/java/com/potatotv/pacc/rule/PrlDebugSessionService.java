package com.potatotv.pacc.rule;

import com.potatotv.prl.PrlException;
import com.potatotv.prl.bytecode.PrlBytecode;
import com.potatotv.prl.compiler.PrlCompiler;
import com.potatotv.prl.debugger.DebugState;
import com.potatotv.prl.debugger.LogRecord;
import com.potatotv.prl.debugger.PrlDebugAbortedException;
import com.potatotv.prl.debugger.PrlDebugger;
import com.potatotv.prl.runtime.PrlValues;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * PRL 调试会话注册表（设计文档 §2.15.1 / §3.3.3）。
 *
 * <p>PRL 库的 {@link PrlDebugger} 是「一次执行一个实例」，而管理端的调试面板要来回发好几个请求
 * （开会话、单步、继续、停止、改断点），中间那次暂停发生在被调试线程里。会话必须活在两次请求之间，
 * 这就是本类存在的理由。</p>
 *
 * <p><b>为什么要有并发上限。</b>每条会话占着一个后台线程，规则停在断点上时那个线程一直阻塞着。
 * 上限之内是「管理端开了几个调试面板」，上限之外就是「谁把调试接口当普通接口在刷」。到上限直接报错，
 * 不排队：排队的请求最后只会超时，什么信息也给不出。</p>
 *
 * <p><b>响应字段名严格对齐 {@code prl-editor} 的 {@code api.ts} 与 {@code types.ts}</b>，这个类
 * 返回的就是那个 {@code DebugState} 的 JSON 形状，控制器只负责包一层 {@code {"state": ...}}。</p>
 */
@Service
public class PrlDebugSessionService {

    /** 同时活着的调试会话上限。 */
    static final int MAX_SESSIONS = 4;

    /** 一条命令之后等现场的时间。规则带循环或死循环时靠它收尾，不能让请求线程无限等。 */
    static final long PAUSE_TIMEOUT_MS = 2_000L;

    /** 展示字符串的截断长度：一个巨大的集合转成字符串会顺着响应把面板撑爆。 */
    private static final int MAX_VALUE_CHARS = 300;

    /** 会话内保留的执行日志条数，与前端「执行日志」面板的展示量对齐。 */
    private static final int MAX_OUTPUT = 100;

    private final RuleHostContext hostContext = new RuleHostContext();
    private final PrlCompiler compiler = new PrlCompiler(hostContext);
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ 会话

    /**
     * 开一次调试执行，返回第一个现场。
     *
     * @param input 样本输入；规则 input 块声明的字段从这里取，缺哪个哪个就是 null
     */
    public Map<String, Object> create(String ruleName, String source, String ruleVersion,
                                      List<Map<String, Object>> breakpoints, Map<String, Object> input) {
        purgeFinished();
        PrlBytecode bytecode = compiler.compile(source);
        // 源码里没有这条规则时 PrlDebugger 会退回执行入口函数，等于在调试另一条规则。宁可直接拒绝。
        if (!ruleName.isBlank() && bytecode.rule(ruleName) == null) {
            throw new PrlException("源码里没有规则 '" + ruleName + "'");
        }

        PrlDebugger debugger = new PrlDebugger(hostContext);
        Session session = new Session(UUID.randomUUID().toString(), ruleName, ruleVersion, debugger,
                input == null ? Map.of() : new LinkedHashMap<>(input));
        applyBreakpoints(session, breakpoints);
        synchronized (sessions) {
            // 上限检查与占位得在同一把锁里：先读 size 再 put 是 check-then-act，两个请求能一起挤进来。
            if (sessions.size() >= MAX_SESSIONS) {
                throw new PrlException("并发调试会话已达上限 " + MAX_SESSIONS + "，先停止一个再开");
            }
            sessions.put(session.id, session);
        }
        try {
            debugger.start(bytecode, ruleName.isBlank() ? null : ruleName, session.input);
        } catch (RuntimeException e) {
            sessions.remove(session.id);
            throw e;
        }
        synchronized (session) {
            return settle(session, "会话已创建");
        }
    }

    /** 单步：{@code mode} 为 {@code over} 时跳过函数，其余一律当作进入。 */
    public Map<String, Object> step(String sessionId, String mode) {
        Session session = require(sessionId);
        synchronized (session) {
            if (!session.debugger.isPaused()) {
                // 已经跑完了：命令队列上没有暂停点，硬发命令只会抛「没有停在断点上」，如实给终态更有用。
                return terminalState(session, "单步");
            }
            boolean into = !"over".equalsIgnoreCase(mode);
            if (into) {
                session.debugger.stepInto();
            } else {
                session.debugger.stepOver();
            }
            return settle(session, into ? "单步进入" : "单步跳过");
        }
    }

    /** 放行到下一个断点。 */
    public Map<String, Object> resume(String sessionId) {
        Session session = require(sessionId);
        synchronized (session) {
            if (!session.debugger.isPaused()) {
                return terminalState(session, "继续执行");
            }
            session.debugger.resume();
            return settle(session, "继续执行");
        }
    }

    /** 中止并回收会话。 */
    public Map<String, Object> stop(String sessionId) {
        Session session = require(sessionId);
        synchronized (session) {
            session.debugger.close();
            appendOutput(session, lineOf(session.debugger.state().orElse(null)), "info", "调试会话已停止");
            Map<String, Object> state = stateJson(session, "stopped",
                    session.debugger.state().orElse(null));
            sessions.remove(session.id);
            return state;
        }
    }

    /** 覆盖式更新断点表，返回下发后的整张表（含未启用的条目）。 */
    public List<Map<String, Object>> updateBreakpoints(String sessionId, List<Map<String, Object>> breakpoints) {
        Session session = require(sessionId);
        synchronized (session) {
            applyBreakpoints(session, breakpoints);
            return breakpointsJson(session);
        }
    }

    // ------------------------------------------------------------------ 命令之后

    /** 命令已下发：等下一次暂停，等不到就按终态收尾。 */
    private Map<String, Object> settle(Session session, String action) {
        Optional<DebugState> paused = session.debugger.awaitPause(PAUSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        drainLogs(session);
        if (paused.isPresent()) {
            appendOutput(session, paused.get().line(), "info",
                    action + "，暂停在第 " + paused.get().line() + " 行");
            return stateJson(session, "paused", paused.get());
        }
        return terminalState(session, action);
    }

    /** 执行还没停就报 running，已经停了就交结果。 */
    private Map<String, Object> terminalState(Session session, String action) {
        if (session.debugger.isRunning()) {
            appendOutput(session, lineOf(session.debugger.state().orElse(null)), "info",
                    action + "，规则仍在运行（本次等待超时）");
            return stateJson(session, "running", session.debugger.state().orElse(null));
        }
        return finish(session, action);
    }

    /**
     * 执行已经结束：把结果整理成终态。
     *
     * <p>失败与中止都如实写进执行日志 —— 调试面板上「规则为什么没跑完」比「没跑完」重要得多。</p>
     */
    private Map<String, Object> finish(Session session, String action) {
        drainLogs(session);
        DebugState snapshot = session.debugger.state().orElse(null);
        try {
            session.debugger.awaitFinish(0, TimeUnit.MILLISECONDS);
            appendOutput(session, lineOf(snapshot), "info", action + "，规则执行结束");
            return stateJson(session, "stopped", snapshot);
        } catch (PrlDebugAbortedException e) {
            appendOutput(session, lineOf(snapshot), "info", action + "，调试会话已中止");
            return stateJson(session, "stopped", snapshot);
        } catch (PrlException e) {
            appendOutput(session, lineOf(snapshot), "error", "规则执行失败：" + e.getMessage());
            return stateJson(session, "error", snapshot);
        }
    }

    // ------------------------------------------------------------------ 断点

    /**
     * 覆盖式下发断点表。
     *
     * <p><b>条件断点在这里退化成无条件断点。</b>条件在 PRL 库里是调用方给的 {@code Predicate}，不是
     * 源码字符串：编译一段 PRL 表达式得先知道 input 块的宿主类型，那份类型信息只在编辑器手里
     * （{@code PrlDebugger} 的类注释写得比这里细）。服务端手里只有 {@code condition} 字符串，
     * 凭空当条件用只会编译失败，所以这里按无条件断点处理。编辑器当前也不产生 condition。</p>
     *
     * <p>整张表（含 {@code enabled=false} 的条目）原样记在会话上：断点的启停状态归编辑器维护，
     * 回显时就得照编辑器建模的样子还回去。只回生效的那几条，编辑器一刷新就把被停用的断点丢了。</p>
     */
    private static void applyBreakpoints(Session session, List<Map<String, Object>> breakpoints) {
        PrlDebugger debugger = session.debugger;
        debugger.clearBreakpoints();
        session.breakpoints.clear();
        for (Map<String, Object> raw : breakpoints) {
            int line = intOf(raw.get("line"));
            if (line <= 0) {
                continue;
            }
            boolean logPoint = boolOf(raw.get("logPoint"), false);
            boolean enabled = boolOf(raw.get("enabled"), true);
            session.breakpoints.add(breakpointRow(session.ruleName, line, enabled, logPoint));
            if (!enabled) {
                continue;
            }
            if (logPoint) {
                debugger.addLogpoint(line);
            } else {
                debugger.setBreakpoint(line);
            }
        }
    }

    private static Map<String, Object> breakpointRow(String ruleName, int line, boolean enabled,
                                                     boolean logPoint) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", ruleName + ":" + line);
        row.put("ruleName", ruleName);
        row.put("line", line);
        row.put("enabled", enabled);
        row.put("logPoint", logPoint);
        return row;
    }

    /**
     * 会话上的断点表，字段名按编辑器 {@code Breakpoint} 给。
     *
     * <p>不回显 {@code condition}：条件没有生效，还回去等于谎报，编辑器会以为它管用。</p>
     */
    private static List<Map<String, Object>> breakpointsJson(Session session) {
        return List.copyOf(session.breakpoints);
    }

    // ------------------------------------------------------------------ 序列化

    private static Map<String, Object> stateJson(Session session, String status, DebugState snapshot) {
        // 终态不给 sessionId：编辑器据此判断「会话还活着」，留着它按钮就会一直可点。
        boolean live = "paused".equals(status) || "running".equals(status);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", live ? session.id : "");
        out.put("ruleName", session.ruleName);
        out.put("ruleVersion", session.ruleVersion);
        out.put("status", status);
        out.put("currentLine", lineOf(snapshot));
        out.put("variables", variablesJson(session, snapshot));
        out.put("callStack", callStackJson(snapshot));
        out.put("output", List.copyOf(session.output));
        out.put("isDemo", false);
        return out;
    }

    private static List<Map<String, Object>> variablesJson(Session session, DebugState snapshot) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (snapshot == null) {
            return out;
        }
        for (Map.Entry<String, Object> variable : snapshot.variables().entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", variable.getKey());
            row.put("type", typeName(variable.getValue()));
            row.put("value", display(variable.getValue()));
            // 引擎的变量槽位不分来源。input 块的字段名与宿主传进来的键一致，靠它划出 input 那一档。
            row.put("scope", session.input.containsKey(variable.getKey()) ? "input" : "local");
            out.add(row);
        }
        return out;
    }

    /** 调用栈：PRL 只给函数名列表，行号统一挂当前行 —— 帧本身不带行号。 */
    private static List<Map<String, Object>> callStackJson(DebugState snapshot) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (snapshot == null) {
            return out;
        }
        List<String> frames = snapshot.callStack();
        for (int i = 0; i < frames.size(); i++) {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("id", "frame-" + i);
            frame.put("name", frames.get(i));
            frame.put("line", snapshot.line());
            out.add(frame);
        }
        return out;
    }

    /**
     * 把新产生的日志点记录补进执行日志。
     *
     * <p>按条数游标去重：一次请求读一次全量记录，不做游标的话同一条会在每次响应里各出现一遍。</p>
     */
    private static void drainLogs(Session session) {
        List<LogRecord> records = session.debugger.logRecords();
        for (int i = session.consumedLogs; i < records.size(); i++) {
            LogRecord record = records.get(i);
            appendOutput(session, record.line(), "info",
                    "日志点 " + record.functionName() + " " + truncate(String.valueOf(record.variables())));
        }
        session.consumedLogs = records.size();
    }

    private static void appendOutput(Session session, int line, String level, String message) {
        session.seq++;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("seq", session.seq);
        entry.put("line", line);
        entry.put("level", level);
        entry.put("message", message);
        session.output.add(entry);
        while (session.output.size() > MAX_OUTPUT) {
            session.output.remove(0);
        }
    }

    /** 值的展示字符串用 PRL 自己的格式化：字符串带引号，与规则里看到的形态一致。 */
    private static String display(Object value) {
        return value == null ? "" : truncate(PrlValues.display(value));
    }

    private static String truncate(String text) {
        return text.length() <= MAX_VALUE_CHARS ? text : text.substring(0, MAX_VALUE_CHARS) + "…";
    }

    /** 运行时值的类型名，按 PRL 的类型词给；认不出来就退回 Java 类名。 */
    private static String typeName(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Double || value instanceof Float) {
            return "float";
        }
        if (value instanceof Number) {
            return "int";
        }
        if (value instanceof Boolean) {
            return "bool";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof List<?>) {
            return "list";
        }
        if (value instanceof Map<?, ?>) {
            return "map";
        }
        return value.getClass().getSimpleName();
    }

    private static int lineOf(DebugState snapshot) {
        return snapshot == null ? 0 : snapshot.line();
    }

    private static int intOf(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static boolean boolOf(Object value, boolean fallback) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text.trim());
        }
        return fallback;
    }

    // ------------------------------------------------------------------ 内部

    private Session require(String sessionId) {
        Session session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null) {
            throw new PrlException("调试会话不存在或已结束，请重新开始会话");
        }
        return session;
    }

    /** 回收已经跑完的会话：线程早退了，只剩断点与日志占着内存。开新会话时顺手清掉。 */
    private void purgeFinished() {
        sessions.values().removeIf(session -> {
            synchronized (session) {
                if (session.debugger.isRunning()) {
                    return false;
                }
                session.debugger.close();
                return true;
            }
        });
    }

    /** 一条调试会话：调试器句柄 + 面板要的元数据 + 执行日志。 */
    private static final class Session {

        final String id;
        final String ruleName;
        final String ruleVersion;
        final PrlDebugger debugger;
        final Map<String, Object> input;
        final List<Map<String, Object>> output = new ArrayList<>();

        /** 客户端下发的整张断点表，启停状态照原样留着，回显时用它。 */
        final List<Map<String, Object>> breakpoints = new ArrayList<>();

        /** 执行日志的自增序号，前端拿它当列表 key。 */
        int seq;
        /** 已经补进执行日志的日志点记录条数。 */
        int consumedLogs;

        Session(String id, String ruleName, String ruleVersion, PrlDebugger debugger,
                Map<String, Object> input) {
            this.id = id;
            this.ruleName = ruleName;
            this.ruleVersion = ruleVersion == null ? "" : ruleVersion;
            this.debugger = debugger;
            this.input = input;
        }
    }
}