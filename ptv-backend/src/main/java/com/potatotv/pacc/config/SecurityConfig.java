package com.potatotv.pacc.config;

import com.potatotv.pacc.service.AdminTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.channel.ChannelProcessingFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全配置：
 * <ul>
 *   <li>玩家端注册/登录与 WebSocket（含 H2 控制台）放行；</li>
 *   <li>/api/admin/** 由管理 API Key 过滤；</li>
 *   <li>玩家端受保护 REST 由 JWT 过滤器解析 PTEID。</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Value("${pacc.security.admin-api-key}")
    private String adminApiKey;

    @Value("${pacc.security.jwt-secret}")
    private String jwtSecret;

    /** 管理后台允许的跨域来源（Origin 白名单，逗号分隔）。 */
    @Value("${pacc.security.allowed-origins}")
    private String allowedOrigins;

    private final AdminTokenService adminTokenService;

    public SecurityConfig(AdminTokenService adminTokenService) {
        this.adminTokenService = adminTokenService;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> {
                })
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/ws/**", "/h2-console/**").permitAll()
                        .requestMatchers("/api/admin/**").permitAll() // 由 AdminKeyFilter 校验
                        .anyRequest().permitAll())
                // 放行 H2 控制台 frame
                .headers(h -> h.frameOptions(f -> f.sameOrigin()));
        // 安全响应头 + 统一访问日志：置于过滤器链最前，覆盖所有请求
        http.addFilterBefore(new SecurityHeadersFilter(), ChannelProcessingFilter.class);
        http.addFilterBefore(new AccessLogFilter(), ChannelProcessingFilter.class);
        http.addFilterBefore(new AdminKeyFilter(adminApiKey, adminTokenService, allowedOrigins), UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(new JwtAuthFilter(jwtSecret), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}