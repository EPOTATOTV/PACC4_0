package com.potatotv.pob;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 端到端测试用的样例程序：现编现打包，覆盖继承、内部接口、lambda/方法引用、
 * 匿名类、枚举、record、外部接口实现（AutoCloseable/Runnable）、以及
 * {@code Class.forName("点分类名")} 这类「类名字符串」用法。
 */
final class Fixtures {

    private Fixtures() {
    }

    static final String PKG = "com/potatotv/paccclient/fx";
    static final String ENTRY = "com.potatotv.paccclient.fx.Fx";

    static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        String p = "package com.potatotv.paccclient.fx;\n\n";
        sources.put("Fx", p + """
                public final class Fx {
                    /** 只被下面的匿名类读写：Java 11+ 走 nestmate 访问，不是合成访问器。 */
                    private static int anonRuns;

                    public static void main(String[] args) throws Exception {
                        Greeter greeter = new GreeterImpl();
                        Sink length = String::length;
                        Sink plus = v -> v.length() + 1;
                        Object secret = Class.forName("com.potatotv.paccclient.fx.Secret")
                                .getDeclaredConstructor().newInstance();
                        Task task = new Task();
                        task.run();
                        String resource;
                        try (Resource res = new Resource()) {
                            resource = res.use();
                        }
                        Runnable anon = new Runnable() {
                            @Override
                            public void run() {
                                // 直接读写外层的私有静态字段：混淆后如果这个匿名类与外层
                                // 不在同一个运行时包，这一行会 LinkageError（nest host 校验）
                                anonRuns++;
                                FxHelper.mark();
                            }
                        };
                        anon.run();
                        Point point = new Point(1, 2);
                        // 与 java.util.Properties.load(InputStream)V 同名同描述符：
                        // 全局按签名分配映射时两者会命中同一个键，必须只改自研这边的引用
                        Loader loader = new Loader();
                        loader.load(null);
                        String props = Loader.viaProperties(
                                "k=v".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        System.out.println(String.join(",",
                                greeter.greet("a"),
                                String.valueOf(length.apply("abcd")),
                                String.valueOf(plus.apply("abcd")),
                                String.valueOf(Derived.of(3).score()),
                                String.valueOf(Chain.of(4).value()),
                                Color.RED.name(),
                                String.valueOf(Color.values().length),
                                String.valueOf(point.x() + point.y()),
                                String.valueOf(point.equals(new Point(1, 2))),
                                secret.toString(),
                                Task.LABEL,
                                String.valueOf(task.ran),
                                String.valueOf(anonRuns),
                                resource,
                                String.valueOf(FxHelper.hits()),
                                loader.data(),
                                props));
                    }
                }
                """);
        sources.put("Greeter", p + """
                public interface Greeter {
                    String greet(String who);
                }
                """);
        sources.put("GreeterImpl", p + """
                public final class GreeterImpl implements Greeter {
                    @Override
                    public String greet(String who) {
                        return prefix() + who;
                    }

                    private String prefix() {
                        return "hi-";
                    }
                }
                """);
        sources.put("Sink", p + """
                public interface Sink {
                    int apply(String value);
                }
                """);
        sources.put("Base", p + """
                public abstract class Base {
                    public abstract int score();

                    public final String describe() {
                        return "base:" + score();
                    }
                }
                """);
        sources.put("Derived", p + """
                public final class Derived extends Base {
                    private final int level;

                    private Derived(int level) {
                        this.level = level;
                    }

                    public static Derived of(int level) {
                        return new Derived(level);
                    }

                    @Override
                    public int score() {
                        return level * 2;
                    }
                }
                """);
        sources.put("Chain", p + """
                public final class Chain {
                    private static final int STEP = 7;
                    private final int seed;

                    private Chain(int seed) {
                        this.seed = seed;
                    }

                    public static Chain of(int seed) {
                        return new Chain(seed);
                    }

                    public int value() {
                        return scaled();
                    }

                    private int scaled() {
                        return seed * STEP;
                    }
                }
                """);
        sources.put("Color", p + """
                public enum Color {
                    RED("r"), GREEN("g");

                    private final String tag;

                    Color(String tag) {
                        this.tag = tag;
                    }

                    public String tag() {
                        return tag;
                    }
                }
                """);
        sources.put("Point", p + """
                public record Point(int x, int y) {
                }
                """);
        sources.put("Secret", p + """
                public final class Secret {
                    @Override
                    public String toString() {
                        return "SECRET";
                    }
                }
                """);
        sources.put("Task", p + """
                public final class Task implements Runnable {
                    public static final String LABEL = "task";
                    public boolean ran;

                    @Override
                    public void run() {
                        ran = true;
                    }
                }
                """);
        sources.put("Resource", p + """
                public final class Resource implements AutoCloseable {
                    public String use() {
                        return "used";
                    }

                    @Override
                    public void close() {
                    }
                }
                """);
        sources.put("FxHelper", p + """
                public final class FxHelper {
                    private static int hits;

                    private FxHelper() {
                    }

                    public static void mark() {
                        hits++;
                    }

                    public static int hits() {
                        return hits;
                    }
                }
                """);
        sources.put("Loader", p + """
                public final class Loader {
                    private String data = "init";

                    /**
                     * 故意与 {@code java.util.Properties.load(java.io.InputStream)} 同名同描述符：
                     * 成员映射按「名字 + 描述符」全局分配，两者会命中同一个键，
                     * 所以引用侧必须按 owner 区分，只改自研这边的。
                     */
                    public void load(java.io.InputStream in) {
                        data = "ours";
                    }

                    public String data() {
                        return data;
                    }

                    /** 真的去调 JDK 的同名方法：引用方是 java.util.Properties，名字绝不能被改。 */
                    public static String viaProperties(byte[] bytes) throws Exception {
                        java.util.Properties props = new java.util.Properties();
                        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(bytes)) {
                            props.load(in);
                        }
                        return props.getProperty("k");
                    }
                }
                """);
        return sources;
    }

    /** 编译样例源码并打成 jar；返回 jar 路径。 */
    static Path compileAndJar(Path workDir, Path jarPath) throws IOException {
        return jar(workDir, jarPath, sources(), ENTRY);
    }

    /** 编译任意一组源码并打成 jar，入口写进清单；返回 jar 路径。 */
    static Path jar(Path workDir, Path jarPath, Map<String, String> sources, String entry) throws IOException {
        Path srcDir = workDir.resolve("src");
        Path classesDir = workDir.resolve("classes");
        Files.createDirectories(classesDir);
        List<String> files = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            String relative = e.getKey().replace('.', '/') + ".java";
            Path file = srcDir.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue(), StandardCharsets.UTF_8);
            files.add(file.toString());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("当前不是 JDK，无法现场编译测试样例");
        }
        List<String> args = new ArrayList<>(List.of("-d", classesDir.toString(), "-encoding", "UTF-8"));
        args.addAll(files);
        int code = compiler.run(null, null, null, args.toArray(String[]::new));
        if (code != 0) {
            throw new IllegalStateException("样例编译失败，javac 退出码 " + code);
        }

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            Path manifest = classesDir.resolve("MANIFEST.MF");
            Files.writeString(manifest, "Manifest-Version: 1.0\r\n"
                    + "Main-Class: " + entry + "\r\n\r\n", StandardCharsets.UTF_8);
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write(Files.readAllBytes(manifest));
            zip.closeEntry();
            Files.delete(manifest);
            try (var stream = Files.walk(classesDir)) {
                for (Path path : stream.filter(Files::isRegularFile).toList()) {
                    String name = classesDir.relativize(path).toString().replace('\\', '/');
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(Files.readAllBytes(path));
                    zip.closeEntry();
                }
            }
        }
        return jarPath;
    }

    /** 运行 jar 入口类的 main，捕获标准输出（去掉首尾空白）。 */
    static String runMain(Path jar, String entry) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                Fixtures.class.getClassLoader())) {
            Class<?> main = Class.forName(entry, true, loader);
            PrintStream saved = System.out;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try {
                System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
                main.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            } finally {
                System.setOut(saved);
            }
            return buffer.toString(StandardCharsets.UTF_8).trim();
        }
    }

    /** 读取 jar 的所有条目（已解压）。 */
    static Map<String, byte[]> readJar(Path jar) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            var it = zip.entries();
            while (it.hasMoreElements()) {
                var entry = it.nextElement();
                entries.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
        }
        return entries;
    }

    /** 整个 jar 的任意条目里是否出现该 ASCII 明文。 */
    static boolean containsPlaintext(Map<String, byte[]> entries, String text) {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        for (byte[] bytes : entries.values()) {
            if (indexOf(bytes, needle) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}