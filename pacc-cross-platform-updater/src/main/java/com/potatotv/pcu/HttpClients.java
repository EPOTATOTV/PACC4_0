package com.potatotv.pcu;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * HttpClient 构造：检查更新与上报共用一套超时/重定向策略。
 */
final class HttpClients {

    private HttpClients() {
    }

    static HttpClient create(PcuConfig config) {
        return HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 下载用的客户端：连接超时同检查更新，整体读取超时由请求级 timeout 控制。 */
    static HttpClient createForDownload(PcuConfig config) {
        return HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    static Duration downloadTimeout(PcuConfig config) {
        // 下载整体耗时远大于接口请求，沿用请求超时会让大包必被掐断
        Duration requestTimeout = config.requestTimeout();
        return requestTimeout.multipliedBy(20);
    }
}