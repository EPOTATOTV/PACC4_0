package com.potatotv.prl.sandbox;

import com.potatotv.prl.engine.DetectionResult;
import com.potatotv.prl.runtime.PrlHostObject;
import com.potatotv.prl.runtime.PrlSecurityException;
import com.potatotv.prl.types.HostTypeRegistry;

import java.util.Map;
import java.util.Set;

/**
 * 宿主（PACC）向规则引擎注册能力的入口（设计文档 §2.11.2）。
 *
 * <p>只有 {@link #callFunction} 与 {@link #getAvailableFunctions} 是必须实现的 —— 它们对应 §2.11.1
 * L3 的 API 白名单。规则里出现的每个函数名，先查宿主白名单；宿主没接管的交给 {@code PrlStdlib}
 * 兜底，两条路都不通就是编译期/运行期拒绝。</p>
 *
 * <p>其余方法给默认实现，因为绝大多数宿主只关心函数那一层，成员访问与告警回调是可选能力：
 * 不实现 {@link #acceptAlert} 时告警只作为 {@code emit_alert} 的返回值回到规则里，宿主侧拿不到。</p>
 */
public interface PrlHostContext {

    /** 空宿主：所有函数都走标准库，不接收任何副作用。脱离 PACC 单独跑规则时用它。 */
    PrlHostContext EMPTY = new PrlHostContext() {
        @Override
        public Object callFunction(String name, Object[] args) {
            throw new PrlSecurityException("宿主没有注册函数 '" + name + "'");
        }

        @Override
        public Set<String> getAvailableFunctions() {
            return Set.of();
        }
    };

    /**
     * 调用一个由宿主接管的白名单函数。
     *
     * @throws PrlSecurityException 函数不在白名单内
     */
    Object callFunction(String name, Object[] args);

    /**
     * 宿主接管的函数白名单。
     *
     * <p>同时是编译期白名单的来源：{@code TypeChecker} 拿它与标准库签名表取并集，
     * 表外的调用在编译期就被拒（§2.11.1 L3）。</p>
     */
    Set<String> getAvailableFunctions();

    /**
     * 读取宿主对象的成员。
     *
     * <p>没有反射：宿主对象自己实现 {@link PrlHostObject}。这里额外放行 {@link Map}，
     * 规则里把 map 当结构体用（{@code e["damage"]} 与 {@code e.damage} 等价）不需要宿主写代码。</p>
     */
    default Object getMember(Object target, String member) {
        if (target instanceof PrlHostObject hostObject) {
            return hostObject.getMember(member);
        }
        if (target instanceof Map<?, ?> map && map.containsKey(member)) {
            return map.get(member);
        }
        throw new PrlSecurityException("宿主对象 "
                + (target == null ? "null" : target.getClass().getSimpleName())
                + " 没有成员 '" + member + "'");
    }

    /** 调用宿主对象的方法；同样不经过反射，由 {@link PrlHostObject} 自己分发。 */
    default Object callMethod(Object target, String method, Object[] args) {
        if (target instanceof PrlHostObject hostObject) {
            return hostObject.callMethod(method, args);
        }
        throw new PrlSecurityException("宿主对象 "
                + (target == null ? "null" : target.getClass().getSimpleName())
                + " 没有方法 '" + method + "'");
    }

    /** 规则发出告警（§2.4.7 {@code emit_alert}）。 */
    default void acceptAlert(DetectionResult alert) {
    }

    /** 规则记录一条证据（§2.4.7 {@code record_evidence}）。 */
    default void recordEvidence(String key, Object value) {
    }

    /** 规则要求红屏警告（§2.4.7 {@code trigger_redscreen}）。 */
    default void triggerRedScreen(String reason) {
    }

    /** 规则写日志（§2.4.7 {@code log}）。 */
    default void log(String level, String message) {
        System.out.println("[PRL " + level + "] " + message);
    }

    /**
     * 临时禁用某个客户端功能，注意不是封禁玩家（§2.4.7 {@code ban_feature}，
     * 与「系统不加任何封禁能力」的约束一致：这里禁的是功能开关，不是账号）。
     */
    default void disableFeature(String feature, long durationMillis) {
    }

    /** 宿主注册的上下文类型（§2.3.3）。默认给标准的那批，宿主可以覆盖成自己的子集。 */
    default HostTypeRegistry getTypes() {
        return HostTypeRegistry.standard();
    }
}