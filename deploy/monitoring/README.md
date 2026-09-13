# PACC v5.0 监控栈（Prometheus + Grafana）

监控与可观测性环境（环境清单四-3）实现：
- **Prometheus**：抓取 PTV 后端 `/actuator/prometheus`、Go 网关 `/metrics`、AI 服务
- **Grafana**：数据源与仪表盘自动配置（provisioning），开箱即用
- **告警规则**：服务不可达、网关限流频繁

## 启用

监控栈以 Compose profile 隔离，不影响主业务栈：

```bash
docker compose --profile monitoring up -d --build
# 或带上业务栈一起
docker compose --profile monitoring up -d
```

| 服务 | 地址 | 默认账号 |
| --- | --- | --- |
| Grafana | http://localhost:3000 | admin / admin |
| Prometheus | http://localhost:9090 | - |

> 生产请通过 `.env` 覆盖 `GRAFANA_ADMIN_PASSWORD`。

## 结构

```
deploy/monitoring/
├── prometheus/
│   ├── prometheus.yml      # 抓取目标
│   └── rules/
│       └── pacc-alerts.yml # 告警规则
└── grafana/
    ├── provisioning/       # 数据源 + 仪表盘自动配置
    └── dashboards/
        └── pacc-overview.json  # 服务监控总览
```

## 指标端点

| 组件 | 端点 | 关键指标 |
| --- | --- | --- |
| PTV 后端 | `/actuator/prometheus` | `http_server_requests_seconds_*`、`jvm_*`、`process_cpu_usage` |
| Go 网关 | `/metrics` | `pacc_gateway_requests_total`、`pacc_gateway_rate_limited_total`、`pacc_gateway_unauthorized_total` |
| Prometheus 自身 | `/-/healthy` | `up` |
