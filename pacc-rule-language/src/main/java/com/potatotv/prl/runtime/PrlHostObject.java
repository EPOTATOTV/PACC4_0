package com.potatotv.prl.runtime;

/**
 * 宿主对象可选的成员访问协议。
 *
 * <p>规则里的 {@code player.name}、{@code click_pattern.to_json()} 要落到宿主对象上，而沙箱明确
 * 禁止反射（§2.11.1 L1「无反射/无类加载」），所以成员读取得由宿主动交出入口：</p>
 *
 * <ul>
 *   <li>宿主对象实现本接口 → 默认的 {@code PrlHostContext.getMember/callMethod} 直接委托过来；</li>
 *   <li>宿主想自己控管 → 覆写 {@code PrlHostContext} 的两个默认方法，本接口可以不实现。</li>
 * </ul>
 *
 * <p>本模块自带的 {@code Stats}、{@code ClickPattern} 默认实现会实现它，好让规则脱离 PACC 也能跑。</p>
 */
public interface PrlHostObject {

    /**
     * 读取字段。
     *
     * @return 字段值
     * @throws PrlSecurityException 该对象没有这个字段
     */
    Object getMember(String member);

    /**
     * 调用方法。默认不提供任何方法。
     *
     * @throws PrlSecurityException 该对象没有这个方法
     */
    default Object callMethod(String method, Object[] args) {
        throw new PrlSecurityException("宿主对象没有方法 '" + method + "'");
    }

    /** 可用于错误信息的成员清单。 */
    default String describableMembers() {
        return "";
    }
}