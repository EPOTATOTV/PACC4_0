package com.potatotv.pacc.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 反射访问辅助：以「候选名列表」在运行时解析游戏类 / 方法 / 字段，兼容 1.8 ~ 1.20 的
 * MCP / Mojmap 命名差异。
 *
 * <p><b>核心约定</b>：任何解析或读取失败都返回 {@code null} / {@link java.util.Optional#empty()}，
 * 绝不抛出。目标类或方法在某个 Minecraft 版本上不存在属于正常情况，调用方应静默降级。</p>
 */
final class Reflect {

    private Reflect() {
    }

    /**
     * 按候选全限定名（点分）解析类，依次尝试「系统类加载器 → 线程上下文类加载器 → 默认加载器」，
     * 以兼容 Forge / Fabric 等自定义启动类加载器场景。
     *
     * @return 首个可解析的类；全部失败返回 null
     */
    static Class<?> findClass(List<String> candidates) {
        for (String name : candidates) {
            Class<?> c = tryLoad(name, ClassLoader.getSystemClassLoader());
            if (c == null) c = tryLoad(name, Thread.currentThread().getContextClassLoader());
            if (c == null) {
                try {
                    c = Class.forName(name, false, Reflect.class.getClassLoader());
                } catch (Throwable ignore) {
                    // 所有加载器均不可见
                }
            }
            if (c != null) return c;
        }
        return null;
    }

    private static Class<?> tryLoad(String name, ClassLoader loader) {
        if (loader == null) return null;
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * 无参静态 / 实例方法调用，方法名取候选列表中首个存在者。
     *
     * @param type      目标类
     * @param target    实例（调静态方法时可为 null）
     * @param names     候选方法名
     * @return 返回值；解析或调用失败返回 null
     */
    static Object invoke(Class<?> type, Object target, List<String> names) {
        if (type == null) return null;
        for (String name : names) {
            try {
                Method m = findMethod(type, name);
                if (m == null) continue;
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable ignore) {
                // 尝试下一个候选名
            }
        }
        return null;
    }

    /** 在类及其父类中查找首个同名无参方法。 */
    private static Method findMethod(Class<?> type, String name) {
        Class<?> c = type;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * 读取字段值，字段名取候选列表中首个存在者（含父类字段）。
     *
     * @return 字段值；解析或读取失败返回 null
     */
    static Object field(Class<?> type, Object target, List<String> names) {
        if (type == null) return null;
        for (String name : names) {
            try {
                Field f = findField(type, name);
                if (f == null) continue;
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignore) {
                // 尝试下一个候选名
            }
        }
        return null;
    }

    /** 在类及其父类中查找首个同名字段。 */
    static Field findField(Class<?> type, String name) {
        Class<?> c = type;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name)) return f;
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * 读取静态单例：优先无参静态方法，其次静态字段。
     *
     * @param type         目标类
     * @param methodNames  单例方法候选名（如 {@code getInstance}）
     * @param fieldNames   静态字段候选名（如 {@code theMinecraft}）
     * @return 实例；不可得返回 null
     */
    static Object singleton(Class<?> type, List<String> methodNames, List<String> fieldNames) {
        if (type == null) return null;
        Object v = invoke(type, null, methodNames);
        if (v != null) return v;
        for (String name : fieldNames) {
            try {
                Field f = findField(type, name);
                if (f == null || !Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object o = f.get(null);
                if (o != null) return o;
            } catch (Throwable ignore) {
                // 尝试下一个候选名
            }
        }
        return null;
    }

    /** 读取 double 字段（字段名候选），失败返回 {@link Double#NaN}。 */
    static double doubleField(Class<?> type, Object target, List<String> names) {
        Object v = field(type, target, names);
        return v instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    /** 读取 boolean 字段（字段名候选），失败返回 false。 */
    static boolean boolField(Class<?> type, Object target, List<String> names) {
        Object v = field(type, target, names);
        return v instanceof Boolean b && b;
    }

    /** 读取 float 字段（字段名候选），失败返回 {@link Float#NaN}。 */
    static float floatField(Class<?> type, Object target, List<String> names) {
        Object v = field(type, target, names);
        return v instanceof Number n ? n.floatValue() : Float.NaN;
    }

    /** 收集目标类（含父类）中声明类型为 {@code fieldType} 的全部实例字段。 */
    static List<Field> fieldsOfType(Class<?> type, Class<?> fieldType) {
        List<Field> out = new ArrayList<>();
        Class<?> c = type;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() == fieldType) out.add(f);
            }
            c = c.getSuperclass();
        }
        return out;
    }
}