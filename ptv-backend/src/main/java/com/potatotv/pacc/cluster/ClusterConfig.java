package com.potatotv.pacc.cluster;

import com.potatotv.pacc.service.LoginThrottle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多实例共享存储装配：默认进程内；当 {@code pacc.cluster.redis-enabled=true} 时切换为
 * Redis 集中式存储（限流窗口、验证码跨实例共享）。纯 JDK RESP 客户端，不引入 spring-data-redis。
 */
@Configuration
public class ClusterConfig {

    @Bean
    AttemptLedger attemptLedger(@Value("${pacc.cluster.redis-enabled:false}") boolean redisEnabled,
                                @Value("${pacc.cluster.redis-host:127.0.0.1}") String host,
                                @Value("${pacc.cluster.redis-port:6379}") int port,
                                @Value("${pacc.cluster.redis-auth:}") String auth) {
        if (redisEnabled) {
            return new RedisAttemptLedger(new RedisClient(host, port, auth, 2000), LoginThrottle.WINDOW_MS);
        }
        return new InMemoryAttemptLedger(LoginThrottle.WINDOW_MS);
    }

    @Bean
    OtpLedger otpLedger(@Value("${pacc.cluster.redis-enabled:false}") boolean redisEnabled,
                        @Value("${pacc.cluster.redis-host:127.0.0.1}") String host,
                        @Value("${pacc.cluster.redis-port:6379}") int port,
                        @Value("${pacc.cluster.redis-auth:}") String auth) {
        if (redisEnabled) {
            return new RedisOtpLedger(new RedisClient(host, port, auth, 2000));
        }
        return new InMemoryOtpLedger(10L * 60_000L);
    }
}