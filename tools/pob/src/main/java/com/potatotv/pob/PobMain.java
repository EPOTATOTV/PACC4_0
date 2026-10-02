package com.potatotv.pob;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * POB 命令行入口。
 *
 * <pre>
 * java -jar pacc-obfuscator.jar \
 *   --in        target/ptv-client-5.4.0.jar      # 输入/输出同一个 jar（原地加固）
 *   --merge     target/pob-merge                 # 要并入产物的依赖 jar（目录或 jar 列表）
 *   --rules     pob-rules.pob
 *   --mapping   target/pob-mapping.txt
 * </pre>
 *
 * <p>{@code --merge} 对应 ProGuard 的 {@code includeDependencyInjar}：依赖类会被一起重命名、
 * 打平并写进产物，资源以主 jar 为准。发布件是单文件（installer.iss 只装一个 jar），
 * 所以依赖必须并进来，否则 CI 的 jdeps 门禁会报一堆 not found。</p>
 */
public final class PobMain {

    private static final String DEFAULT_PACKAGE = "com/potatotv/paccclient";

    private PobMain() {
    }

    public static void main(String[] args) throws IOException {
        Path input = null;
        Path rulesFile = null;
        Path mapping = null;
        String targetPackage = DEFAULT_PACKAGE;
        List<Path> mergedJars = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--in" -> input = Path.of(next(args, ++i, arg));
                case "--merge" -> mergedJars.addAll(expand(next(args, ++i, arg)));
                case "--rules" -> rulesFile = Path.of(next(args, ++i, arg));
                case "--mapping" -> mapping = Path.of(next(args, ++i, arg));
                case "--package" -> targetPackage = next(args, ++i, arg).replace('.', '/');
                default -> throw new IllegalArgumentException("未知参数：" + arg);
            }
        }
        if (input == null || rulesFile == null || mapping == null) {
            usage();
            throw new IllegalArgumentException("--in / --rules / --mapping 都是必填");
        }
        if (!Files.isRegularFile(input)) {
            throw new IOException("输入 jar 不存在：" + input);
        }

        PobRules rules = PobRules.parse(rulesFile);
        JarObfuscator obfuscator = new JarObfuscator(mapping, targetPackage);
        obfuscator.run(input, mergedJars, rules);
        System.out.printf("POB：%s 加固完成，并入依赖 %d 个 jar%n", input, mergedJars.size());
    }

    /**
     * --merge 既可以给目录（并入其中所有 jar），也可以给用系统分隔符连起来的 jar 列表。
     * 目录不存在时视为「没有依赖」，不要让本地调试因为缺少可选目录而失败。
     */
    private static List<Path> expand(String value) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String part : value.split(java.io.File.pathSeparator)) {
            if (part.isBlank()) {
                continue;
            }
            Path path = Path.of(part);
            if (Files.isDirectory(path)) {
                try (Stream<Path> entries = Files.list(path)) {
                    entries.filter(p -> p.getFileName().toString().endsWith(".jar"))
                            .sorted(Comparator.comparing(Path::toString))
                            .forEach(out::add);
                }
            } else if (Files.isRegularFile(path)) {
                out.add(path);
            }
        }
        return out;
    }

    private static String next(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " 缺少参数值");
        }
        return args[index];
    }

    private static void usage() {
        System.err.println("用法: pacc-obfuscator --in <jar> --rules <file> --mapping <file> "
                + "[--merge <dir|jar[;jar]>] [--package <pkg>]");
    }
}