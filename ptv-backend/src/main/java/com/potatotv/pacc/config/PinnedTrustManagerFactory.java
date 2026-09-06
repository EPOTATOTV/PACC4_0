package com.potatotv.pacc.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import java.net.Socket;
import java.net.http.HttpClient;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 出站 TLS 可选证书固定：当环境变量 {@code PACC_TLS_PIN_SHA256}（逗号分隔的 SPKI SHA-256
 * Base64 指纹）非空时，为出站请求启用“系统信任库校验 + 叶子证书 SPKI 指纹匹配”双重要求。
 * <p>未配置任何 pin 时 {@link #active()} 返回 false，各调用方退回默认系统信任库（与现状一致），
 * 因此该加固默认不改变对外行为——只有显式配置指纹才强约束目标主机证书。</p>
 * <p>威胁模型：防御 MITM 时下发伪造（即便审计证书链被穿透）的证书，避免凭库中毒导致的
 * 流量旁路。配置者需自行为目标主机导出合法 SPKI 指纹（如 {@code openssl x509 -pubkey -noout
 * | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64}）。</p>
 */
@Slf4j
@Component
public class PinnedTrustManagerFactory {

    private final List<byte[]> pins;

    public PinnedTrustManagerFactory(@Value("${PACC_TLS_PIN_SHA256:}") String pinsCsv) {
        List<byte[]> parsed = new ArrayList<>();
        if (pinsCsv != null && !pinsCsv.isBlank()) {
            for (String s : pinsCsv.split(",")) {
                String v = s.trim();
                if (v.isEmpty()) continue;
                try {
                    parsed.add(Base64.getDecoder().decode(v));
                } catch (IllegalArgumentException e) {
                    log.warn("忽略无效的 TLS SPKI 指纹: {}", v);
                }
            }
        }
        this.pins = List.copyOf(parsed);
        if (!pins.isEmpty()) {
            log.info("已启用出站 TLS 证书固定，共 {} 个 SPKI 指纹", pins.size());
        }
    }

    /** 是否已配置指纹并启用固定。 */
    public boolean active() {
        return !pins.isEmpty();
    }

    /**
     * 构建带 pin 的 HTTP 请求工厂；未启用 pin 时为空，调用方应退回默认。
     * 返回的工厂使用 java.net.http 通道，注入带固定校验的 SSLContext。
     */
    public ClientHttpRequestFactory requestFactory() {
        if (!active()) return null;
        HttpClient httpClient = HttpClient.newBuilder()
                .sslContext(sslContext())
                .build();
        return new JdkClientHttpRequestFactory(httpClient);
    }

    /** 单例懒建 SSLContext（校验链用系统信任库 + 叶子 SPKI 匹配）。 */
    private SSLContext sslContext() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((java.security.KeyStore) null); // 系统信任库
            TrustManager[] tms = tmf.getTrustManagers();
            X509ExtendedTrustManager system = null;
            for (TrustManager tm : tms) {
                if (tm instanceof X509ExtendedTrustManager x) { system = x; break; }
            }
            if (system == null) {
                throw new IllegalStateException("系统信任管理器不可用，无法启用证书固定");
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new PinningTrustManager(system, pins)}, null);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("初始化 TLS 证书固定失败", e);
        }
    }

    /** 包装系统信任管理器：先走完整链校验，再校验叶子 SPKI 命中配置指纹。 */
    private static final class PinningTrustManager extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager delegate;
        private final List<byte[]> pins;

        PinningTrustManager(X509ExtendedTrustManager delegate, List<byte[]> pins) {
            this.delegate = delegate;
            this.pins = pins;
        }

        private void checkPinned(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("证书链为空，无法校验 SPKI 指纹");
            }
            byte[] spki = chain[0].getPublicKey().getEncoded();
            byte[] digest;
            try {
                digest = MessageDigest.getInstance("SHA-256").digest(spki);
            } catch (Exception e) {
                throw new CertificateException("证书摘要计算失败", e);
            }
            for (byte[] pin : pins) {
                if (MessageDigest.isEqual(pin, digest)) return;
            }
            throw new CertificateException(
                    "叶子证书 SPKI 与配置固定的指纹不匹配（疑似 MITM 或证书变更），拒绝连接");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
            checkPinned(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            delegate.checkClientTrusted(chain, authType, socket);
            checkPinned(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            delegate.checkClientTrusted(chain, authType, engine);
            checkPinned(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
            checkPinned(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            delegate.checkServerTrusted(chain, authType, socket);
            checkPinned(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            delegate.checkServerTrusted(chain, authType, engine);
            checkPinned(chain);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            // 仍以系统信任库为准，仅叠加叶子指纹约束
            return delegate.getAcceptedIssuers() == null ? new X509Certificate[0] : delegate.getAcceptedIssuers();
        }
    }
}