// PACC Go 边缘网关（API 网关 / WSS 入口）
//
// 功能（全部标准库实现，零第三方依赖）：
//   - HTTP 反向代理：/api、/ws 转发到 PTV 后端（含 WebSocket Upgrade 透传）
//   - 基于 IP 的令牌桶限流（防滥用）
//   - 管理后台鉴权：/api/admin/** 校验 X-Admin-Key（登录接口除外）
//   - Prometheus 指标：/metrics 暴露请求/限流/鉴权计数器
//   - 访问日志（host / 状态 / 耗时 / 来源 IP）
//
// 环境变量：
//
//	BACKEND_URL     后端地址（默认 http://ptv-backend:8080）
//	PORT            监听端口（默认 8080）
//	RATE_RPS        每 IP 每秒令牌（默认 20）
//	RATE_BURST      令牌桶容量（默认 50）
//	ADMIN_KEY       管理后台 API Key（为空则关闭校验）
package main

import (
	"fmt"
	"log"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

// ---------- 令牌桶限流 ----------
type bucket struct {
	tokens float64
	last   time.Time
}

type rateLimiter struct {
	mu      sync.Mutex
	buckets map[string]*bucket
	rps     float64
	burst   float64
	lastGC  time.Time
}

func newRateLimiter(rps, burst float64) *rateLimiter {
	return &rateLimiter{buckets: map[string]*bucket{}, rps: rps, burst: burst, lastGC: time.Now()}
}

func (r *rateLimiter) allow(ip string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	now := time.Now()
	// 定期清理空闲桶，避免内存膨胀
	if now.Sub(r.lastGC) > 5*time.Minute {
		for k, b := range r.buckets {
			if now.Sub(b.last) > 10*time.Minute {
				delete(r.buckets, k)
			}
		}
		r.lastGC = now
	}
	b, ok := r.buckets[ip]
	if !ok {
		b = &bucket{tokens: r.burst, last: now}
		r.buckets[ip] = b
	}
	elapsed := now.Sub(b.last).Seconds()
	b.tokens = minFloat(b.tokens+elapsed*r.rps, r.burst)
	b.last = now
	if b.tokens >= 1 {
		b.tokens--
		return true
	}
	return false
}

func minFloat(a, b float64) float64 {
	if a < b {
		return a
	}
	return b
}

// ---------- 指标（Prometheus 文本格式，标准库实现） ----------
type metrics struct {
	mu          sync.Mutex
	total       uint64
	byStatus    map[int]uint64
	rateLimited uint64
	unauth      uint64
	startedAt   time.Time
}

func newMetrics() *metrics {
	return &metrics{byStatus: map[int]uint64{}, startedAt: time.Now()}
}

func (m *metrics) record(status int, rateLimited bool, unauth bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.total++
	m.byStatus[status]++
	if rateLimited {
		m.rateLimited++
	}
	if unauth {
		m.unauth++
	}
}

func (m *metrics) write(w http.ResponseWriter) {
	m.mu.Lock()
	defer m.mu.Unlock()
	w.Header().Set("Content-Type", "text/plain; version=0.0.4")
	w.WriteHeader(http.StatusOK)
	up := float64(time.Since(m.startedAt).Seconds())
	_, _ = fmt.Fprintf(w, "# HELP pacc_gateway_requests_total Total HTTP requests handled by gateway\n")
	_, _ = fmt.Fprintf(w, "# TYPE pacc_gateway_requests_total counter\n")
	_, _ = fmt.Fprintf(w, "pacc_gateway_requests_total %d\n", m.total)
	_, _ = fmt.Fprintf(w, "# HELP pacc_gateway_rate_limited_total Requests rejected by rate limiter\n")
	_, _ = fmt.Fprintf(w, "# TYPE pacc_gateway_rate_limited_total counter\n")
	_, _ = fmt.Fprintf(w, "pacc_gateway_rate_limited_total %d\n", m.rateLimited)
	_, _ = fmt.Fprintf(w, "# HELP pacc_gateway_unauthorized_total Requests rejected by admin auth\n")
	_, _ = fmt.Fprintf(w, "# TYPE pacc_gateway_unauthorized_total counter\n")
	_, _ = fmt.Fprintf(w, "pacc_gateway_unauthorized_total %d\n", m.unauth)
	_, _ = fmt.Fprintf(w, "# HELP pacc_gateway_up Gateway uptime seconds\n")
	_, _ = fmt.Fprintf(w, "# TYPE pacc_gateway_up gauge\n")
	_, _ = fmt.Fprintf(w, "pacc_gateway_up %0.2f\n", up)
}

// ---------- 网关 ----------
func main() {
	backend := env("BACKEND_URL", "http://ptv-backend:8080")
	port := env("PORT", "8080")
	adminKey := env("ADMIN_KEY", "")
	rps := float64(parseFloat(env("RATE_RPS", "20"), 20))
	burst := float64(parseFloat(env("RATE_BURST", "50"), 50))

	target, err := url.Parse(backend)
	if err != nil {
		log.Fatalf("无效的 BACKEND_URL: %v", err)
	}
	proxy := httputil.NewSingleHostReverseProxy(target)
	limiter := newRateLimiter(rps, burst)
	met := newMetrics()

	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		clientIP := clientIP(r)
		status := http.StatusOK

		// 健康检查
		if r.URL.Path == "/healthz" {
			w.Header().Set("Content-Type", "text/plain")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("ok"))
			return
		}

		// 指标抓取
		if r.URL.Path == "/metrics" {
			met.write(w)
			return
		}

		// 管理后台鉴权（登录放行）
		if adminKey != "" && strings.HasPrefix(r.URL.Path, "/api/admin/") &&
			r.URL.Path != "/api/admin/login" {
			if r.Header.Get("X-Admin-Key") != adminKey {
				status = http.StatusUnauthorized
				http.Error(w, `{"error":"unauthorized"}`, status)
				log.Printf("[gateway] %s %s %s -> %d (unauthorized) %s",
					clientIP, r.Method, r.URL.Path, status, time.Since(start))
				met.record(status, false, true)
				return
			}
		}

		// 限流（除健康检查与登录外均计数）
		if r.URL.Path != "/api/admin/login" && !limiter.allow(clientIP) {
			status = http.StatusTooManyRequests
			http.Error(w, `{"error":"rate limited"}`, status)
			log.Printf("[gateway] %s %s %s -> %d (rate limited) %s",
				clientIP, r.Method, r.URL.Path, status, time.Since(start))
			met.record(status, true, false)
			return
		}

		// WebSocket 透传
		if strings.HasPrefix(r.URL.Path, "/ws/") {
			r.Header.Set("X-Forwarded-Proto", schemeOf(r))
		}

		proxy.ServeHTTP(w, r)
		status = 200
		log.Printf("[gateway] %s %s %s -> %d %s", clientIP, r.Method, r.URL.Path, status, time.Since(start))
		met.record(status, false, false)
	})

	log.Printf("[gateway] 启动: :%s backend=%s rate=%0.frps/%0.fburst", port, backend, rps, burst)
	if err := http.ListenAndServe(":"+port, handler); err != nil {
		log.Fatal(err)
	}
}

func clientIP(r *http.Request) string {
	if fwd := r.Header.Get("X-Forwarded-For"); fwd != "" {
		parts := strings.Split(fwd, ",")
		return strings.TrimSpace(parts[0])
	}
	return strings.Split(r.RemoteAddr, ":")[0]
}

func schemeOf(r *http.Request) string {
	if r.TLS != nil {
		return "https"
	}
	return "http"
}

func parseFloat(s string, def float64) float64 {
	var f float64
	if _, err := fmtSscan(s, &f); err != nil {
		return def
	}
	return f
}

func fmtSscan(s string, v *float64) (int, error) {
	_, err := fmtSscanf(s, v)
	return 0, err
}

func fmtSscanf(s string, v *float64) (int, error) {
	i := 0
	for i < len(s) && (s[i] >= '0' && s[i] <= '9' || s[i] == '.') {
		i++
	}
	if i == 0 {
		return 0, errParse
	}
	_, err := fmtParseFloat(s[:i], v)
	return i, err
}

var errParse = &parseError{}

type parseError struct{}

func (*parseError) Error() string { return "parse error" }

func fmtParseFloat(s string, v *float64) (int, error) {
	var x float64
	scale := 1.0
	frac := false
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case c >= '0' && c <= '9':
			if frac {
				scale /= 10
				x += float64(c-'0') * scale
			} else {
				x = x*10 + float64(c-'0')
			}
		case c == '.' && !frac:
			frac = true
		default:
			return i, errParse
		}
	}
	*v = x
	return len(s), nil
}
