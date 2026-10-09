package com.potatotv.pob;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 常量池 UTF-8 改写规则：类名、点分名、描述符、泛型签名四类形态。 */
class NameRewriterTest {

    private final NameRewriter rewriter = new NameRewriter(Map.of(
            "com/potatotv/paccclient/detection/DetectionEngine", "com/potatotv/paccclient/a",
            "com/potatotv/paccclient/security/HookDetector", "com/potatotv/paccclient/b",
            "com/potatotv/paccclient/security/HookDetector$Finding", "com/potatotv/paccclient/c"));

    @Test
    void 内部类名整串替换() {
        assertEquals("com/potatotv/paccclient/a",
                rewriter.rewrite("com/potatotv/paccclient/detection/DetectionEngine"));
    }

    @Test
    void 点分类名字符串同步改写() {
        // Class.forName("...") 的实参就是这种形态，必须跟着改，否则运行时找不到类
        assertEquals("com.potatotv.paccclient.a",
                rewriter.rewrite("com.potatotv.paccclient.detection.DetectionEngine"));
    }

    @Test
    void 保留类的名字原样不动() {
        assertEquals("com.potatotv.paccclient.PaccClient",
                rewriter.rewrite("com.potatotv.paccclient.PaccClient"));
        assertEquals("com/potatotv/paccclient/Json", rewriter.rewrite("com/potatotv/paccclient/Json"));
    }

    @Test
    void 方法描述符里的类型引用被替换() {
        assertEquals("(Lcom/potatotv/paccclient/b;I)Lcom/potatotv/paccclient/a;",
                rewriter.rewrite("(Lcom/potatotv/paccclient/security/HookDetector;I)"
                        + "Lcom/potatotv/paccclient/detection/DetectionEngine;"));
    }

    @Test
    void 数组类型也认() {
        assertEquals("[Lcom/potatotv/paccclient/a;",
                rewriter.rewrite("[Lcom/potatotv/paccclient/detection/DetectionEngine;"));
    }

    @Test
    void 基本类型参数紧跟对象参数时也要替换() {
        // (ILcom/...;)V 里 L 前面是 I，漏判会把类型引用留在常量池，运行时 NoClassDefFoundError
        assertEquals("(ILcom/potatotv/paccclient/b;)V",
                rewriter.rewrite("(ILcom/potatotv/paccclient/security/HookDetector;)V"));
        assertEquals("(DLcom/potatotv/paccclient/a;Ljava/lang/String;II)V",
                rewriter.rewrite("(DLcom/potatotv/paccclient/detection/DetectionEngine;Ljava/lang/String;II)V"));
    }

    @Test
    void 泛型签名里的类型变量上界也要替换() {
        assertEquals("<T:Lcom/potatotv/paccclient/b;>Ljava/lang/Object;",
                rewriter.rewrite("<T:Lcom/potatotv/paccclient/security/HookDetector;>Ljava/lang/Object;"));
    }

    @Test
    void 泛型签名里的嵌套类点号形态() {
        assertEquals("Ljava/util/List<Lcom/potatotv/paccclient/c;>;",
                rewriter.rewrite("Ljava/util/List<Lcom/potatotv/paccclient/security/HookDetector.Finding;>;"));
    }

    @Test
    void 普通字符串不会被误伤() {
        assertEquals("https://api.potatotv.asia/v1/report",
                rewriter.rewrite("https://api.potatotv.asia/v1/report"));
        assertEquals("LoadFactor", rewriter.rewrite("LoadFactor"));
        assertEquals("", rewriter.rewrite(""));
    }

    @Test
    void 能取到方法描述符的返回类型() {
        assertEquals("com/potatotv/paccclient/a",
                NameRewriter.returnTypeOf("(I)Lcom/potatotv/paccclient/a;"));
        assertEquals(null, NameRewriter.returnTypeOf("(I)V"));
        assertEquals(null, NameRewriter.returnTypeOf("(Ljava/lang/String;)V"));
    }
}