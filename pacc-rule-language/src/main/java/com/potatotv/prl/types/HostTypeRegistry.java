package com.potatotv.prl.types;

import com.potatotv.prl.ast.TypeRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 宿主上下文类型注册表（设计文档 §2.3.3）。
 *
 * <p>把 {@code TypeRef}（源码里的类型引用）解析成 {@link PrlType}，并提供成员访问所需的字段/方法表。
 * PACC 侧在自己的启动流程里注册真实类型；这里内置一份与文档一致的默认表，让规则在脱离宿主时也能
 * 通过类型检查和单测。</p>
 *
 * <p>事件类类型（{@code AttackEvent}/{@code MoveEvent}/...）文档只给出了名字与在 §2.4 函数签名里的
 * 用法，字段清单是照着 §2.2.1、§2.2.5 的样例反推的。</p>
 */
public final class HostTypeRegistry {

    private final Map<String, HostType> types = new LinkedHashMap<>();

    /** 默认注册表：文档 §2.3.3 的 PlayerContext 加上 §2.4 用到的全部事件/结果类型。 */
    public static HostTypeRegistry standard() {
        HostTypeRegistry registry = new HostTypeRegistry();
        registerStandardTypes(registry);
        return registry;
    }

    /** 空注册表，宿主想完全接管类型定义时用。 */
    public static HostTypeRegistry empty() {
        return new HostTypeRegistry();
    }

    public HostTypeRegistry register(HostType type) {
        types.put(type.name(), type);
        return this;
    }

    public HostType get(String name) {
        return types.get(name);
    }

    public boolean contains(String name) {
        return types.containsKey(name);
    }

    public Set<String> names() {
        return types.keySet();
    }

    /**
     * 把源码里的类型引用解析成 {@link PrlType}。
     *
     * @throws TypeException 类型名未注册，或泛型参数个数不对
     */
    public PrlType resolve(TypeRef ref) {
        List<PrlType> args = new ArrayList<>(ref.args().size());
        for (TypeRef arg : ref.args()) {
            args.add(resolve(arg));
        }
        return resolve(ref.name(), args, ref.line(), ref.col());
    }

    public PrlType resolve(String name, List<PrlType> args, int line, int col) {
        switch (name) {
            case "int" -> {
                requireArgs(name, args, 0, line, col);
                return PrlType.INT;
            }
            case "float" -> {
                requireArgs(name, args, 0, line, col);
                return PrlType.FLOAT;
            }
            case "bool" -> {
                requireArgs(name, args, 0, line, col);
                return PrlType.BOOL;
            }
            case "string" -> {
                requireArgs(name, args, 0, line, col);
                return PrlType.STRING;
            }
            case "list" -> {
                requireArgs(name, args, 1, line, col);
                return PrlType.list(args.get(0));
            }
            case "set" -> {
                requireArgs(name, args, 1, line, col);
                return PrlType.set(args.get(0));
            }
            case "map" -> {
                requireArgs(name, args, 2, line, col);
                return PrlType.map(args.get(0), args.get(1));
            }
            case "tuple" -> {
                if (args.isEmpty()) {
                    throw new TypeException("tuple 至少需要一个元素类型", line, col);
                }
                return PrlType.tuple(args);
            }
            default -> {
                HostType hostType = types.get(name);
                if (hostType == null) {
                    throw new TypeException("未注册的类型 '" + name + "'，可用类型: " + types.keySet(), line, col);
                }
                if (!args.isEmpty()) {
                    throw new TypeException("宿主类型 '" + name + "' 不支持泛型参数", line, col);
                }
                return hostType.asType();
            }
        }
    }

    private static void requireArgs(String name, List<PrlType> args, int expected, int line, int col) {
        if (args.size() != expected) {
            throw new TypeException("类型 " + name + " 需要 " + expected + " 个泛型参数，实际 " + args.size(),
                    line, col);
        }
    }

    // ------------------------------------------------------------------ 默认类型表

    private static void registerStandardTypes(HostTypeRegistry registry) {
        registry.register(HostType.builder("PlayerContext")
                .field("id", PrlType.STRING)
                .field("name", PrlType.STRING)
                .field("reputation", PrlType.INT)
                .field("platform", PrlType.STRING)
                .method("is_trusted", PrlType.BOOL)
                .build());

        registry.register(HostType.builder("AttackEvent")
                .field("target_changed", PrlType.BOOL)
                .field("damage", PrlType.FLOAT)
                .field("is_critical", PrlType.BOOL)
                .field("reach", PrlType.FLOAT)
                .field("target_id", PrlType.STRING)
                .field("time_ms", PrlType.INT)
                .build());

        registry.register(HostType.builder("MoveEvent")
                .field("speed", PrlType.FLOAT)
                .field("direction", PrlType.FLOAT)
                .field("duration_ms", PrlType.INT)
                .method("is_airborne", PrlType.BOOL)
                .method("is_grounded", PrlType.BOOL)
                .method("is_horizontal", PrlType.BOOL)
                .build());

        registry.register(HostType.builder("MiningEvent")
                .field("exposed", PrlType.BOOL)
                .field("path_straightness", PrlType.FLOAT)
                .field("distance_to_vein", PrlType.FLOAT)
                .field("duration_ms", PrlType.INT)
                .method("is_ore", PrlType.BOOL)
                .build());

        registry.register(HostType.builder("ClickEvent")
                .field("time_ms", PrlType.INT)
                .field("interval_ms", PrlType.FLOAT)
                .field("button", PrlType.STRING)
                .field("position_x", PrlType.FLOAT)
                .field("position_y", PrlType.FLOAT)
                .field("position_z", PrlType.FLOAT)
                .build());

        registry.register(HostType.builder("MouseMoveEvent")
                .field("time_ms", PrlType.INT)
                .field("delta_yaw", PrlType.FLOAT)
                .field("delta_pitch", PrlType.FLOAT)
                .field("precision", PrlType.FLOAT)
                .method("is_horizontal", PrlType.BOOL)
                .build());

        registry.register(HostType.builder("ClickPattern")
                .field("cps", PrlType.FLOAT)
                .field("interval_stddev", PrlType.FLOAT)
                .method("is_human_like", PrlType.BOOL)
                .method("to_json", PrlType.STRING)
                .build());

        registry.register(HostType.builder("Stats")
                .field("mean", PrlType.FLOAT)
                .field("stddev", PrlType.FLOAT)
                .field("median", PrlType.FLOAT)
                .field("count", PrlType.INT)
                .build());

        registry.register(HostType.builder("SystemState")
                .field("tps", PrlType.FLOAT)
                .field("tick", PrlType.INT)
                .field("online_players", PrlType.INT)
                .field("memory_used_mb", PrlType.FLOAT)
                .method("network_stable", PrlType.BOOL)
                .build());

        registry.register(HostType.builder("DetectionResult")
                .field("type", PrlType.STRING)
                .field("confidence", PrlType.FLOAT)
                .field("evidence", PrlType.map(PrlType.ANY, PrlType.ANY))
                .field("rule_name", PrlType.STRING)
                .field("timestamp_ms", PrlType.INT)
                .build());
    }

    /** 类型名 → 是否宿主类型，供错误信息与文档生成使用。 */
    public Map<String, HostType> asMap() {
        return Map.copyOf(types);
    }

    @Override
    public String toString() {
        return "HostTypeRegistry" + types.keySet();
    }
}