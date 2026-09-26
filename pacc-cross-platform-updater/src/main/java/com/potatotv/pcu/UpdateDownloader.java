package com.potatotv.pcu;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 下载更新包（设计文档 §4.4 第 2 步）：支持断点续传（Range 请求）、进度回调，
 * 失败按指数退避重试；重试时复用已下载的 {@code .part} 文件继续，而不是从零再来。
 */
public final class UpdateDownloader {

    private static final Logger LOG = Logger.getLogger(UpdateDownloader.class.getName());

    /** 断点续传的中间文件后缀。下载完成前不会碰目标文件，避免半包被当成成品。 */
    static final String PART_SUFFIX = ".part";

    private final PcuConfig config;
    private final HttpClient http;

    public UpdateDownloader(PcuConfig config) {
        this(config, HttpClients.createForDownload(config));
    }

    public UpdateDownloader(PcuConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    public Path download(String url, Path target, String expectedChecksum, long expectedSize) {
        return download(url, target, expectedChecksum, expectedSize, ProgressListener.NOOP);
    }

    /**
     * 下载到 target，返回 target。
     *
     * <p>校验在落成品之前完成：只有「字节数吻合 + SHA-256 吻合」的包才会被 rename 成目标文件。</p>
     */
    public Path download(String url, Path target, String expectedChecksum, long expectedSize,
                         ProgressListener listener) {
        URI uri = requireAllowed(url);
        Path part = partFileFor(target);
        try {
            Files.createDirectories(part.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new PcuException("创建临时目录失败：" + part.getParent(), e);
        }

        // 上一轮已经下满（比如验签阶段才失败），直接复用，不再浪费一次请求
        if (!alreadyComplete(part, expectedSize)) {
            Retry.call("下载更新包", config.maxRetries(), config.retryBaseDelay(), () -> {
                fetch(uri, part, expectedSize, listener);
                return null;
            });
        }

        long actualSize = sizeOf(part);
        if (expectedSize > 0 && actualSize != expectedSize) {
            AtomicReplace.deleteQuietly(part);
            throw new PcuException("更新包长度不符：期望 " + expectedSize + " 字节，实际 " + actualSize);
        }
        String actualChecksum = Sha256.hexOfFile(part);
        if (!Sha256.matches(expectedChecksum, actualChecksum)) {
            // 内容不对说明下到的不是目标版本（或被篡改），续传也无法修好，删掉重来
            AtomicReplace.deleteQuietly(part);
            throw new PcuException("更新包 SHA-256 不匹配，已丢弃");
        }
        AtomicReplace.move(part, target);
        LOG.info(() -> "更新包就绪：" + target + "（" + actualSize + " 字节）");
        return target;
    }

    /** 中间文件：与目标同目录同名，避免跨分区 rename 失去原子性。 */
    public static Path partFileFor(Path target) {
        return target.resolveSibling(target.getFileName() + PART_SUFFIX);
    }

    /**
     * §4.2.1：制品只从 {@code pacc.potatotv.asia}（联调时再加上 baseUrl 的主机）下载。
     * 下载地址来自服务端清单，清单是签过名的，但地址不该由它一个人说了算——端侧自己也要
     * 认一遍主机，免得清单被换、或以后接了个不检点的地方后台之后，端侧跟着去任意主机取包。
     *
     * <p>明文 http 只在回环地址上放行（本地联调、测试里的假服务），其余必须走 https。</p>
     */
    private URI requireAllowed(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new PcuException("下载地址不是合法 URI", e);
        }
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        if (host == null || !config.downloadHosts().contains(host) || !(https || isLoopback(host))) {
            // 只报 scheme/host/path，不带查询串：下载地址里可能带签名参数
            throw new PcuException("拒绝从非授权地址下载：" + uri.getScheme() + "://" + host + uri.getPath());
        }
        return uri;
    }

    private static boolean isLoopback(String host) {
        return "localhost".equals(host) || "::1".equals(host) || host.startsWith("127.");
    }

    private void fetch(URI uri, Path part, long expectedSize, ProgressListener listener) throws IOException {
        long existing = alreadyComplete(part, expectedSize) ? sizeOf(part) : (Files.exists(part) ? Files.size(part) : 0);
        // 目标长度已知且已有文件更长，说明上一轮下的是别的包，清掉重下
        if (expectedSize > 0 && existing > expectedSize) {
            Files.deleteIfExists(part);
            existing = 0;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(HttpClients.downloadTimeout(config))
                .header("User-Agent", config.userAgent())
                .GET();
        if (existing > 0) {
            builder.header("Range", "bytes=" + existing + "-");
        }
        HttpResponse<InputStream> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PcuException("下载被中断", e);
        }
        int status = response.statusCode();
        // 重定向是跟过去的（Redirect.NORMAL），落地后必须再认一次主机：
        // 允许的域把自己 302 到别处，白名单就等于没设。宁可中止，也不拿非授权主机的字节去验签。
        String finalHost = response.uri().getHost();
        if (finalHost == null || !config.downloadHosts().contains(finalHost.toLowerCase(Locale.ROOT))) {
            response.body().close();
            throw new PcuException("下载被重定向到非授权主机，已中止："
                    + response.uri().getScheme() + "://" + finalHost + response.uri().getPath());
        }

        boolean append;
        long total;
        if (status == 206) {
            append = true;
            long remaining = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            total = remaining >= 0 ? existing + remaining : -1L;
        } else if (status == 200) {
            // 服务端不支持 Range：从头写，覆盖已有片段
            append = false;
            total = response.headers().firstValueAsLong("Content-Length").orElse(expectedSize);
            if (existing > 0) {
                LOG.warning("服务端未返回 206，放弃续传，从头下载");
            }
        } else if (status == 416) {
            // 断点已到文件末尾：按完成处理，让外层做长度/校验和判定
            response.body().close();
            return;
        } else {
            response.body().close();
            throw new IOException("下载返回 HTTP " + status);
        }

        long written = append ? existing : 0;
        byte[] buf = new byte[64 * 1024];
        Set<StandardOpenOption> options = append
                ? EnumSet.of(StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)
                : EnumSet.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(part, options.toArray(new StandardOpenOption[0]))) {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                written += n;
                listener.onProgress(written, total);
            }
        }
        if (total > 0 && written != total) {
            // 连接中断导致半包：抛 IO 异常交给重试，下一轮从断点继续
            throw new IOException("下载中断：已写 " + written + " 字节，应为 " + total);
        }
    }

    private static boolean alreadyComplete(Path part, long expectedSize) {
        return expectedSize > 0 && Files.exists(part) && sizeOf(part) == expectedSize;
    }

    private static long sizeOf(Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }
}