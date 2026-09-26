package com.potatotv.prl.types;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 宿主注册的上下文类型（设计文档 §2.3.3）。
 *
 * <p>PRL 不允许在规则里定义类型，能用的上下文类型全部由 PACC 注册。字段与方法分开存：</p>
 * <ul>
 *   <li>字段用 {@code player.name} 访问；</li>
 *   <li>方法用 {@code click_pattern.is_human_like()} 访问；</li>
 * </ul>
 *
 * <p>文档 §2.3.3 把 {@code player.is_trusted} 写在「字段」列表里，§2.2.1 又写成
 * {@code player.is_trusted()}；零参方法因此允许省略括号，两种写法在检查器里等价。</p>
 *
 * <p>宿主对象在规则里是只读的：字段与方法都只提供读路径，没有 setter 之类的注册入口。</p>
 */
public final class HostType {

    private final String name;
    private final Map<String, PrlType> fields;
    private final Map<String, HostFunction> methods;

    private HostType(String name, Map<String, PrlType> fields, Map<String, HostFunction> methods) {
        this.name = name;
        this.fields = Map.copyOf(fields);
        this.methods = Map.copyOf(methods);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public PrlType field(String fieldName) {
        return fields.get(fieldName);
    }

    public boolean hasField(String fieldName) {
        return fields.containsKey(fieldName);
    }

    public HostFunction method(String methodName) {
        return methods.get(methodName);
    }

    public boolean hasMethod(String methodName) {
        return methods.containsKey(methodName);
    }

    public Set<String> fieldNames() {
        return fields.keySet();
    }

    public Set<String> methodNames() {
        return methods.keySet();
    }

    /** 对应的 {@link PrlType}。 */
    public PrlType asType() {
        return PrlType.host(name);
    }

    @Override
    public String toString() {
        return name + "{fields=" + fields.keySet() + ", methods=" + methods.keySet() + "}";
    }

    /** 注册用的构建器，保持插入顺序以便错误信息里的成员列表稳定。 */
    public static final class Builder {

        private final String name;
        private final Map<String, PrlType> fields = new LinkedHashMap<>();
        private final Map<String, HostFunction> methods = new LinkedHashMap<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder field(String fieldName, PrlType type) {
            fields.put(fieldName, type);
            return this;
        }

        /** 注册零参方法，如 {@code player.is_trusted()}。 */
        public Builder method(String methodName, PrlType returnType) {
            methods.put(methodName, HostFunction.of(methodName, returnType, "", new HostFunction.Param[0]));
            return this;
        }

        public Builder method(HostFunction function) {
            methods.put(function.name(), function);
            return this;
        }

        public HostType build() {
            return new HostType(name, fields, methods);
        }
    }
}