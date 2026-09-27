package com.potatotv.pcu;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 测试用的 python 调用桥。
 *
 * <p>bzip2 与 bsdiff 都要跟参考实现对得上，而参考实现在测试环境里就是 python 标准库的
 * {@code bz2} 与本仓库 tools 下的 {@code pcu_patchgen}。这台机器不一定装了 python，
 * 所以调用方先问 {@link #available()}，没有就 {@code assumeTrue} 跳过，
 * 而不是让用例硬失败。</p>
 */
final class PythonSupport {

    private static final String EXE = detect();

    private PythonSupport() {
    }

    /** 可用的 python 命令名；没有则返回 null。 */
    static String exe() {
        return EXE;
    }

    static boolean available() {
        return EXE != null;
    }

    /**
     * 执行 {@code python -c <script> <args...>}，返回标准输出。
     *
     * @param script 一行 python 脚本，参数从 {@code sys.argv[1]} 起
     */
    static byte[] runScript(String script, String... args) {
        List<String> command = new ArrayList<>();
        command.add(EXE);
        command.add("-c");
        command.add(script);
        for (String arg : args) {
            command.add(arg);
        }
        try {
            Process process = new ProcessBuilder(command).start();
            process.getOutputStream().close();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> copy(process.getInputStream(), stdout));
            reader.setDaemon(true);
            reader.start();
            byte[] stderr = readAll(process.getErrorStream());
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("python 脚本超时");
            }
            reader.join();
            if (process.exitValue() != 0) {
                throw new AssertionError("python 脚本失败（退出码 " + process.exitValue() + "）："
                        + new String(stderr, StandardCharsets.UTF_8));
            }
            return stdout.toByteArray();
        } catch (IOException e) {
            throw new AssertionError("调用 python 失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("调用 python 被中断", e);
        }
    }

    private static String detect() {
        for (String candidate : new String[]{"python", "python3"}) {
            try {
                Process process = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true).start();
                boolean done = process.waitFor(15, TimeUnit.SECONDS);
                readAll(process.getInputStream());
                if (done && process.exitValue() == 0) {
                    return candidate;
                }
                process.destroyForcibly();
            } catch (IOException e) {
                // 这个候选不存在，试下一个
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        try {
            in.transferTo(out);
        } catch (IOException e) {
            throw new AssertionError("读取 python 标准输出失败", e);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toByteArray();
    }
}