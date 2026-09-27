package com.potatotv.pacc.agent;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 反射行为采样器：在不做字节码插桩的前提下，替代文档 §5.1.2 的「方法埋点」，由独立守护线程
 * 周期性反射读取真实游戏状态并产出 JSON findings。
 *
 * <p><b>采样内容</b>：玩家坐标 / 朝向 / 是否着地、由位移与时间差推算的水平移动速度、鼠标增量
 * （{@code MouseHandler}/{@code MouseHelper}）、以及按键按下状态（遍历 {@code Options}/{@code GameSettings}
 * 中登记的全部 {@code KeyMapping}/{@code KeyBinding}）。</p>
 *
 * <p><b>安全约定</b>：采样线程<b>只读字段</b>，不调用任何会触碰渲染 / 输入线程状态的游戏方法，
 * 避免跨线程调用崩溃；全部逻辑包裹 try/catch，目标类或方法缺失（混淆 / 版本差异）时静默降级，
 * 绝不抛出到游戏。移动速度持续超过阈值（默认 22 格/秒，可用
 * {@code -Dpacc.agent.speed.threshold} 调整）连续 2 个采样周期时产出 {@code abnormal_movement}。</p>
 */
final class RuntimeSampler {

    private static final int FAST_TICK_THRESHOLD = 2;

    private final Findings findings;
    private final double movementSpeedThreshold;

    private volatile ScheduledExecutorService scheduler;
    private boolean loggedDegraded;

    private Class<?> minecraftClass;

    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;
    private long lastSampleTs;
    private int fastTicks;

    RuntimeSampler(Findings findings) {
        this.findings = findings;
        this.movementSpeedThreshold = readDoubleProperty("pacc.agent.speed.threshold", 22.0);
    }

    /**
     * 启动周期采样。
     *
     * @param intervalMs 采样间隔（毫秒），下限 250ms，避免过密采样
     */
    void start(long intervalMs) {
        long period = Math.max(250L, intervalMs);
        scheduler = Executors.newSingleThreadScheduledExecutor(r ->
                Thread.ofPlatform().name("pacc-agent-sampler").daemon(true).unstarted(r));
        scheduler.scheduleWithFixedDelay(this::safeTick, period, period, TimeUnit.MILLISECONDS);
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            // 采样失败不影响游戏
        }
    }

    private void tick() {
        Object mc = minecraft();
        if (mc == null) {
            if (!loggedDegraded) {
                loggedDegraded = true;
                findings.add("sampler_degraded", "info",
                        "未解析到 Minecraft 实例（可能为混淆客户端或非预期版本），反射采样降级");
            }
            return;
        }
        loggedDegraded = false;

        long now = System.currentTimeMillis();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("t", now);
        payload.put("resolved", true);

        Object player = Reflect.field(minecraftClass, mc, List.of("player", "thePlayer"));
        if (player != null) {
            samplePlayer(player, now, payload);
        }
        sampleMouse(mc, payload);
        sampleKeys(mc, payload);

        findings.add("runtime_sample", "info", Json.encode(payload));
    }

    /** 采样玩家状态并检测异常移动。 */
    private void samplePlayer(Object player, long now, Map<String, Object> payload) {
        Class<?> pc = player.getClass();
        double x = numberFieldOrGetter(pc, player, List.of("posX", "getX"));
        double y = numberFieldOrGetter(pc, player, List.of("posY", "getY"));
        double z = numberFieldOrGetter(pc, player, List.of("posZ", "getZ"));
        if (Double.isNaN(x) || Double.isNaN(z)) return;
        payload.put("pos", List.of(round(x), round(y), round(z)));

        float yaw = floatFieldOrGetter(pc, player, List.of("rotationYaw", "getYRot", "getYaw"));
        float pitch = floatFieldOrGetter(pc, player, List.of("rotationPitch", "getXRot", "getPitch"));
        if (!Float.isNaN(yaw)) payload.put("yaw", round(yaw));
        if (!Float.isNaN(pitch)) payload.put("pitch", round(pitch));

        boolean onGround = Reflect.boolField(pc, player, List.of("onGround", "onGroundFlag"));
        payload.put("on_ground", onGround);
        Object horizontalCollision = Reflect.field(pc, player, List.of("horizontalCollision"));
        if (horizontalCollision instanceof Boolean hc) payload.put("horizontal_collision", hc);

        if (lastSampleTs > 0 && now > lastSampleTs) {
            double dt = (now - lastSampleTs) / 1000.0;
            double dx = x - lastX;
            double dz = z - lastZ;
            double speed = Math.sqrt(dx * dx + dz * dz) / dt;
            payload.put("speed", round(speed));
            if (onGround && speed > movementSpeedThreshold) {
                fastTicks++;
                if (fastTicks >= FAST_TICK_THRESHOLD) {
                    payload.put("anomaly", "abnormal_movement");
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "abnormal_movement");
                    detail.put("speed", round(speed));
                    detail.put("threshold", movementSpeedThreshold);
                    detail.put("pos", List.of(round(x), round(y), round(z)));
                    detail.put("on_ground", true);
                    findings.add("abnormal_movement", "high", Json.encode(detail));
                }
            } else {
                fastTicks = 0;
            }
        }
        lastX = x;
        lastZ = z;
        lastSampleTs = now;
    }

    /** 采样鼠标增量（{@code MouseHandler}/{@code MouseHelper}）。 */
    private void sampleMouse(Object mc, Map<String, Object> payload) {
        Object mouse = Reflect.field(minecraftClass, mc, List.of("mouseHandler", "mouseHelper"));
        if (mouse == null) return;
        double dx = Reflect.doubleField(mouse.getClass(), mouse, List.of("accumulatedDX", "deltaX", "x"));
        double dy = Reflect.doubleField(mouse.getClass(), mouse, List.of("accumulatedDY", "deltaY", "y"));
        if (!Double.isNaN(dx) || !Double.isNaN(dy)) {
            payload.put("mouse_delta", List.of(
                    Double.isNaN(dx) ? 0.0 : round(dx),
                    Double.isNaN(dy) ? 0.0 : round(dy)));
        }
    }

    /** 采样全部按键的按下状态（不调用会触碰输入线程的方法，只读字段）。 */
    private void sampleKeys(Object mc, Map<String, Object> payload) {
        Class<?> keyClass = Reflect.findClass(AgentTargets.candidatesFor("key_binding_is_down"));
        Object settings = Reflect.field(minecraftClass, mc, List.of("options", "gameSettings"));
        if (keyClass == null || settings == null) return;
        Class<?> settingsClass = settings.getClass();

        Object attack = Reflect.field(settingsClass, settings, List.of("attackKey", "keyAttack", "keyBindAttack"));
        Boolean attackPressed = keyState(attack);
        if (attackPressed != null) payload.put("attack_pressed", attackPressed);

        int down = 0;
        int total = 0;
        for (Field f : Reflect.fieldsOfType(settingsClass, keyClass)) {
            Object binding = Reflect.field(settingsClass, settings, List.of(f.getName()));
            if (binding == null) continue;
            Boolean state = keyState(binding);
            if (state == null) continue;
            total++;
            if (state) down++;
        }
        if (total > 0) {
            payload.put("keys_down", down);
            payload.put("keys_total", total);
        }
    }

    /** 读取按键状态（只读字段：clickCount / pressed / isDown，不调用方法）。 */
    private static Boolean keyState(Object keyBinding) {
        if (keyBinding == null) return null;
        Class<?> c = keyBinding.getClass();
        Object clicks = Reflect.field(c, keyBinding, List.of("clickCount"));
        if (clicks instanceof Number n && n.intValue() > 0) return Boolean.TRUE;
        Object pressed = Reflect.field(c, keyBinding, List.of("pressed", "isPressed", "isDown", "down"));
        if (pressed instanceof Boolean b) return b;
        if (pressed instanceof Number n) return n.intValue() > 0;
        return null;
    }

    private Object minecraft() {
        if (minecraftClass == null) {
            minecraftClass = Reflect.findClass(AgentTargets.candidatesFor("minecraft_run_tick"));
        }
        if (minecraftClass == null) return null;
        return Reflect.singleton(minecraftClass,
                List.of("getInstance", "getMinecraft"), List.of("theMinecraft", "instance"));
    }

    /** 先读字段，失败再尝试无参 getter（坐标 / 朝向在所有目标版本中均为纯读取）。 */
    private static double numberFieldOrGetter(Class<?> type, Object target, List<String> names) {
        Object v = Reflect.field(type, target, List.of(names.get(0)));
        if (v instanceof Number n) return n.doubleValue();
        Object m = Reflect.invoke(type, target, names.subList(1, names.size()));
        return m instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static float floatFieldOrGetter(Class<?> type, Object target, List<String> names) {
        Object v = Reflect.field(type, target, List.of(names.get(0)));
        if (v instanceof Number n) return n.floatValue();
        Object m = Reflect.invoke(type, target, names.subList(1, names.size()));
        return m instanceof Number n ? n.floatValue() : Float.NaN;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double readDoubleProperty(String key, double defaultValue) {
        try {
            String v = System.getProperty(key);
            return v == null || v.isBlank() ? defaultValue : Double.parseDouble(v.trim());
        } catch (Throwable t) {
            return defaultValue;
        }
    }
}