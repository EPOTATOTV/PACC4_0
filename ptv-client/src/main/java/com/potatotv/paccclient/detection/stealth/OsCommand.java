package com.potatotv.paccclient.detection.stealth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 隐身探针的系统访问底座：受限执行外部命令、读取系统文件。
 *
 * <p>约定（与 {@code detection.telemetry} 一致）：任何失败都只返回 {@link Optional#empty()}，
 * 绝不抛出、绝不打印日志；命令不经 shell（直接 argv），避免注入；单次超时 3 秒、
 * 输出上限 512KB，防止探针本身拖慢客户端。</p>
 */
final class OsCommand {

    private static final long TIMEOUT_MS = 3000L;
    private static final int MAX_BYTES = 512 * 1024;

    private OsCommand() {
    }

    static String os() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    static boolean isWindows() {
        return os().contains("win");
    }

    static boolean isLinux() {
        return os().contains("linux");
    }

    /**
     * 执行命令并读取标准输出。先等待退出再读取，避免管道写满时探针被拖住；
     * 超时则强杀进程并放弃本次读取。
     */
    static Optional<String> output(String... argv) {
        Process p = null;
        try {
            p = new ProcessBuilder(argv).start();
            if (!p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return Optional.empty();
            }
            if (p.exitValue() != 0) return Optional.empty();
            byte[] out = p.getInputStream().readNBytes(MAX_BYTES);
            return Optional.of(new String(out, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    /** 读取系统文件全文（不可读返回 empty）；同样限制读取上限。 */
    static Optional<String> read(Path path) {
        try {
            if (!Files.isReadable(path)) return Optional.empty();
            byte[] raw = Files.newInputStream(path).readNBytes(MAX_BYTES);
            return Optional.of(new String(raw, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 目录存在且非空。 */
    static boolean nonEmptyDir(Path dir) {
        try {
            if (!Files.isDirectory(dir)) return false;
            try (var s = Files.list(dir)) {
                return s.findFirst().isPresent();
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}