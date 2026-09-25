package com.potatotv.pacc.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Java 版探针的 10 个监控目标（对应设计文档 §5.1.2 插桩点表格）。
 *
 * <p><b>与本设计文档的偏差说明（重要）</b>：本探针坚持「零第三方依赖」（不引入 ASM / ByteBuddy
 * 等字节码库，因其会被注入进 Minecraft 且需保持 jar 极小），因此 {@code ClassFileTransformer}
 * <em>无法改写方法体</em>，也就无法做文档字面意义上的「字节码插桩」。本实现将该层替换为
 * 语义等价的<strong>「按目标匹配 + 运行时字节码捕获 + 反射采样」</strong>：</p>
 * <ul>
 *   <li>{@code ClassFileTransformer} 在类装载时按这里的类名匹配目标类，把其运行时字节码存入
 *       {@link ClassCaptureRegistry}，供完整性比对与装载审计；</li>
 *   <li>{@link RuntimeSampler} 用这里的候选名在运行时反射解析真实游戏状态并采样，替代插桩埋点。</li>
 * </ul>
 *
 * <p><b>版本兼容</b>：候选类名覆盖 1.8 ~ 1.20 的 MCP / Mojmap 命名；若目标类缺失（混淆客户端、
 * 版本差异），所有解析方法都返回空并静默降级，绝不抛出。</p>
 */
final class AgentTargets {

    /**
     * 单个监控目标：一个「类 + 方法」级插桩点。
     *
     * @param id              目标标识（与文档 §5.1.2 表格行一一对应）
     * @param method          目标方法名
     * @param classCandidates 目标类的候选全限定名（点分），按版本从新到旧排列
     */
    record Target(String id, String method, List<String> classCandidates) {
    }

    /** 玩家相关目标共享的候选类（1.17+ Mojmap / 1.8-1.16 MCP）。 */
    private static final List<String> PLAYER_CLASSES = List.of(
            "net.minecraft.world.entity.player.Player",
            "net.minecraft.entity.player.Player",
            "net.minecraft.entity.player.EntityPlayer");

    /** 客户端玩家控制器（PlayerController）候选类。 */
    private static final List<String> CONTROLLER_CLASSES = List.of(
            "net.minecraft.client.multiplayer.MultiPlayerGameMode",
            "net.minecraft.client.multiplayer.PlayerControllerMP",
            "net.minecraft.client.multiplayer.PlayerController");

    /** 本地玩家（EntityPlayerSP / LocalPlayer）候选类。 */
    private static final List<String> LOCAL_PLAYER_CLASSES = List.of(
            "net.minecraft.client.player.LocalPlayer",
            "net.minecraft.client.player.AbstractClientPlayer",
            "net.minecraft.client.entity.EntityPlayerSP");

    private static final List<Target> TARGETS = List.of(
            new Target("minecraft_run_tick", "runTick",
                    List.of("net.minecraft.client.Minecraft")),
            new Target("player_attack", "attack", PLAYER_CLASSES),
            new Target("player_swing", "swing", PLAYER_CLASSES),
            new Target("player_controller_damage_block", "onPlayerDamageBlock", CONTROLLER_CLASSES),
            new Target("player_controller_use_item_on", "processRightClickBlock", CONTROLLER_CLASSES),
            new Target("local_player_update", "onUpdate", LOCAL_PLAYER_CLASSES),
            new Target("mouse_xy_change", "mouseXYChange", List.of(
                    "net.minecraft.client.MouseHandler",
                    "net.minecraft.util.MouseHelper",
                    "net.minecraft.client.MouseHelper")),
            new Target("key_binding_is_down", "isKeyDown", List.of(
                    "net.minecraft.client.KeyMapping",
                    "net.minecraft.client.settings.KeyBinding")),
            new Target("client_velocity", "handleEntityVelocity", List.of(
                    "net.minecraft.client.multiplayer.ClientPacketListener",
                    "net.minecraft.client.network.NetHandlerPlayClient",
                    "net.minecraft.client.network.PlayNetHandler",
                    "net.minecraft.client.network.ClientPlayNetHandler")),
            new Target("render_entity", "renderEntityStatic", List.of(
                    "net.minecraft.client.renderer.entity.EntityRenderDispatcher",
                    "net.minecraft.client.renderer.entity.RenderManager")));

    private AgentTargets() {
    }

    /** 全部监控目标（不可变）。 */
    static List<Target> all() {
        return TARGETS;
    }

    /**
     * 返回给定类名命中的全部目标。
     *
     * @param className 类名，斜杠形式（{@code a/b/C}）或点分形式均可
     * @return 命中目标；未命中返回空列表（不会返回 null）
     */
    static List<Target> matchClass(String className) {
        if (className == null || className.isBlank()) return List.of();
        String dotted = className.indexOf('/') >= 0 ? className.replace('/', '.') : className;
        List<Target> hit = new ArrayList<>(2);
        for (Target t : TARGETS) {
            for (String c : t.classCandidates()) {
                if (c.equals(dotted)) {
                    hit.add(t);
                    break;
                }
            }
        }
        return hit;
    }

    /** 按目标 id 返回其候选类名；未知 id 返回空列表。 */
    static List<String> candidatesFor(String id) {
        for (Target t : TARGETS) {
            if (t.id().equals(id)) return t.classCandidates();
        }
        return List.of();
    }
}