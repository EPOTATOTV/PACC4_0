package com.potatotv.pcu.platform;

import com.potatotv.pcu.PcuException;
import com.potatotv.pcu.SemVer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Windows 适配（设计文档 §4.7.2）：PACC 玩家端在这里是一个常驻的 Java 进程
 * （安装目录下的 {@code ptv-client-<版本>.jar}），由 {@code tools/windows-gui} 的
 * {@code PaccManager.exe} 负责安装与托管。
 *
 * <p>两条命令都刻意不用「按镜像名杀进程」的做法：</p>
 * <ul>
 *   <li>停：按命令行里是否含安装目录来筛 {@code java*} 进程再杀。直接杀
 *       {@code javaw.exe}/{@code java.exe} 会把玩家机器上别的 Java 程序一起干掉。</li>
 *   <li>起：用当前 JVM 的 {@code javaw.exe} 启动客户端 JAR，不依赖 PATH 里有 java——
 *       PCU 本身就跑在 JVM 里，那个 java 一定存在。</li>
 * </ul>
 */
public final class WindowsPlatformAdapter extends ProcessPlatformAdapter {

    /** 客户端主 JAR 命名规则，与 {@code tools/windows-gui/build-client.ps1} 保持一致。 */
    private static final String CLIENT_JAR_PREFIX = "ptv-client-";
    private static final String CLIENT_JAR_SUFFIX = ".jar";

    public WindowsPlatformAdapter(Path installDir, Path tempDir) {
        super(installDir, tempDir, commands(installDir, resolveClientJar(installDir)));
    }

    private static Commands commands(Path installDir, Path clientJar) {
        // 客户端是常驻进程：startTimeout 给 null，拉起即返回
        return new Commands(stopScript(installDir), startCommand(clientJar),
                Duration.ofSeconds(15), null);
    }

    /**
     * 按安装目录匹配并结束客户端进程。
     *
     * <p>匹配用的是 {@code IndexOf(..., OrdinalIgnoreCase)} 而不是 {@code -like '*目录*'}：
     * {@code -like} 的模式串里 {@code [ ] * ?} 是通配符，安装目录一旦带这些字符
     * （{@code C:\Program Files [x64]\PACC}、用户自己起的带星号的名字），匹配就会跑偏——
     * 轻则一个进程都停不掉，重则把别的 Java 进程一起带走。</p>
     *
     * <p>PowerShell 单引号串里的 {@code '} 要写成 {@code ''}；目录里常带空格，用单引号
     * 包住比双引号省心。</p>
     */
    static List<String> stopScript(Path installDir) {
        String dir = installDir.toAbsolutePath().normalize().toString().replace("'", "''");
        String script = "Get-CimInstance Win32_Process -Filter \"Name like 'java%'\" | "
                + "Where-Object { $_.CommandLine -and "
                + "$_.CommandLine.IndexOf('" + dir + "', [System.StringComparison]::OrdinalIgnoreCase) -ge 0 } | "
                + "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }";
        return List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", script);
    }

    private static List<String> startCommand(Path clientJar) {
        Path javaw = Path.of(System.getProperty("java.home"), "bin", "javaw.exe");
        String launcher = Files.isRegularFile(javaw) ? javaw.toString() : "javaw";
        return List.of(launcher, "-jar", clientJar.toAbsolutePath().normalize().toString());
    }

    /**
     * 在安装目录里找版本号最高的客户端 JAR。
     *
     * <p>按 {@link SemVer} 比而不是按文件名字符串比：字符串序会把 {@code 5.9.0} 排在
     * {@code 5.10.0} 之后，跨小版本升级时选错文件。</p>
     */
    static Path resolveClientJar(Path installDir) {
        try (Stream<Path> entries = Files.list(installDir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .map(Candidate::of)
                    .flatMap(Optional::stream)
                    .max(Comparator.naturalOrder())
                    .map(Candidate::path)
                    .orElseThrow(() -> new PcuException("安装目录下找不到客户端 JAR（"
                            + CLIENT_JAR_PREFIX + "*" + CLIENT_JAR_SUFFIX + "）：" + installDir));
        } catch (IOException e) {
            throw new PcuException("读取安装目录失败：" + installDir, e);
        }
    }

    /**
     * 候选文件：路径 + 从文件名解析出的版本。
     *
     * <p>不用 {@code Map.entry} 装这一对——它不允许 null 值，安装目录里随便一个
     * 不叫 {@code ptv-client-*.jar} 的文件（校验和、日志、别的组件）都会把它炸掉。</p>
     */
    private record Candidate(Path path, SemVer version) implements Comparable<Candidate> {

        static Optional<Candidate> of(Path path) {
            SemVer version = versionOf(path.getFileName().toString());
            return version == null ? Optional.empty() : Optional.of(new Candidate(path, version));
        }

        @Override
        public int compareTo(Candidate other) {
            return version.compareTo(other.version);
        }
    }

    /** 从 {@code ptv-client-5.4.0.jar} 里取出 {@code 5.4.0}；不符合命名规则返回 null。 */
    private static SemVer versionOf(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(CLIENT_JAR_PREFIX) || !lower.endsWith(CLIENT_JAR_SUFFIX)) {
            return null;
        }
        String version = fileName.substring(CLIENT_JAR_PREFIX.length(),
                fileName.length() - CLIENT_JAR_SUFFIX.length());
        return SemVer.tryParse(version);
    }
}