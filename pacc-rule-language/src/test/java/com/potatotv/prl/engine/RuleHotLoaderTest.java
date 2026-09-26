package com.potatotv.prl.engine;

import com.potatotv.prl.runtime.PrlSecurityException;
import com.potatotv.prl.sandbox.PrlHostContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuleHotLoader} 测试，覆盖设计文档 §2.12.1 与验收项 R08「文件变更后 &lt;1s 生效」。
 *
 * <p>所有等待都用带 deadline 的轮询，绝不 sleep 一个固定时长 —— 固定 sleep 要么拖慢整个套件，
 * 要么在机器繁忙时偶发失败，两种都说明断言方式不对。编译失败、删除文件这两个负向用例同样如此。</p>
 */
class RuleHotLoaderTest {

    @TempDir
    Path dir;

    private static final class RecordingHost implements PrlHostContext {

        final List<String> logs = new ArrayList<>();

        @Override
        public Object callFunction(String name, Object[] args) {
            throw new PrlSecurityException("测试宿主没有注册函数 '" + name + "'");
        }

        @Override
        public Set<String> getAvailableFunctions() {
            return Set.of();
        }

        @Override
        public void log(String level, String message) {
            logs.add(level + "|" + message);
        }
    }

    /** 规则名与文件名一致的恒发告警规则，type 用来区分版本。 */
    private static String alertRule(String name, String type) {
        return """
                rule "%s" {
                    then:
                        emit_alert(type = "%s", confidence = 0.5, evidence = {"k": 1})
                }
                """.formatted(name, type);
    }

    /** 执行规则看它当前发出的告警类型；规则未装载或无告警时为 {@code null}。 */
    private static String alertType(RuleManager manager, String name) {
        return manager.executeRule(name, Map.of()).map(DetectionResult::type).orElse(null);
    }

    private static boolean threadAlive(String name) {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.getName().equals(name));
    }

    /** 轮询到条件成立为止；超时则在 fail 消息里点明违反了 1s 预算，而不是留下一个哑失败。 */
    private static void await(String description, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), description + "（等待超过 1s，违反 §2.12.1 / R08）");
    }

    @Test
    void 启动时装载目录里已有的规则() throws IOException {
        Files.writeString(dir.resolve("a.prl"), alertRule("a", "V1"));
        RuleManager manager = new RuleManager(new RecordingHost());

        try (RuleHotLoader loader = new RuleHotLoader(dir, manager)) {
            loader.start();

            assertEquals(dir, loader.directory());
            assertEquals(List.of("a"), loader.loadedRules(), "规则名 = 文件名去掉 .prl");
            assertTrue(loader.errors().isEmpty(), "合法文件不应有编译错误");
            assertEquals("V1", alertType(manager, "a"));
        }
    }

    @Test
    void 文件变更后一秒内生效() throws IOException, InterruptedException {
        Path file = dir.resolve("a.prl");
        Files.writeString(file, alertRule("a", "V1"));
        RuleManager manager = new RuleManager(new RecordingHost());

        try (RuleHotLoader loader = new RuleHotLoader(dir, manager)) {
            loader.start();
            assertEquals("V1", alertType(manager, "a"));

            Files.writeString(file, alertRule("a", "V2"));

            await("修改后的规则应在 1s 内生效", () -> "V2".equals(alertType(manager, "a")));
        }
    }

    @Test
    void 语法错误时保留旧规则并记录错误() throws IOException, InterruptedException {
        Path file = dir.resolve("b.prl");
        Files.writeString(file, alertRule("b", "V1"));
        RuleManager manager = new RuleManager(new RecordingHost());

        try (RuleHotLoader loader = new RuleHotLoader(dir, manager)) {
            loader.start();
            assertEquals("V1", alertType(manager, "b"));

            Files.writeString(file, "rule \"b\" {\n  let broken =\n}\n");
            await("写入语法错误的文件后 errors() 应记录该规则", () -> loader.errors().containsKey("b"));

            assertFalse(loader.errors().get("b").isBlank(), "错误描述不应为空");
            // §2.12.1：编译失败不卸载旧规则，规则文件写坏的瞬间正是最需要旧规则还活着的时候
            assertEquals("V1", alertType(manager, "b"), "编译失败时旧规则必须继续生效");
            assertTrue(manager.rule("b").isPresent());
        }
    }

    @Test
    void 删除文件后卸载规则() throws IOException, InterruptedException {
        Path file = dir.resolve("a.prl");
        Files.writeString(file, alertRule("a", "V1"));
        RuleManager manager = new RuleManager(new RecordingHost());

        try (RuleHotLoader loader = new RuleHotLoader(dir, manager)) {
            loader.start();
            assertTrue(manager.rule("a").isPresent());

            Files.delete(file);

            await("删除文件后规则应被卸载", () -> manager.rule("a").isEmpty());
            assertFalse(loader.loadedRules().contains("a"), "已卸载的规则不应留在 loadedRules 里");
        }
    }

    @Test
    void 关闭后热加载线程停止() throws IOException {
        Files.writeString(dir.resolve("a.prl"), alertRule("a", "V1"));
        RuleManager manager = new RuleManager(new RecordingHost());
        RuleHotLoader loader = new RuleHotLoader(dir, manager);
        loader.start();

        try {
            assertTrue(threadAlive("prl-hot-loader"), "start() 后应有热加载守护线程在跑");
        } finally {
            loader.close();
        }

        // close() 会 join 该线程，因此这里可以直接断言它已经结束，不必再 sleep
        assertFalse(threadAlive("prl-hot-loader"), "close() 后热加载线程必须停止（§2.12.1）");
    }
}