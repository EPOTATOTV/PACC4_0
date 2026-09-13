# SBOM 物料清单（Software Bill of Materials）

本目录存放 PACC 各软件组件的依赖物料清单，用于合规审计、供应链安全与漏洞追踪。

| 文件 | 说明 | 生成方式 |
|---|---|---|
| `frontend-cyclonedx.json` | PTV 管控后台前端依赖（CycloneDX 格式） | `cd ptv-frontend && npm sbom --sbom-format=cyclonedx --omit=dev > ../deploy/sbom/frontend-cyclonedx.json` |
| `backend-dependencies.txt` | PTV 管控后端 Maven 依赖树 | `cd ptv-backend && mvn -s ../tools-local/maven-settings.xml dependency:tree -DoutputFile=../deploy/sbom/backend-dependencies.txt` |

> 说明：前端采用 CycloneDX 规范（AGPLv3 兼容，纯格式开放）。后端当前以 `dependency:tree`
> 文本形式输出，生产交付时建议接入 CycloneDX-Maven 插件生成完整 CycloneDX JSON/XML。

## v5.0 合规自检关联端点

- 后端 API `GET /api/admin/compliance/sbom` 返回本目录产物索引与再生成命令。
- 管理后台「合规 · SLA · 客服」页面会显示上述清单路径。

## 预留占位（未修改，请按需替换）

> 按用户要求，Logo / 企业外观标识本版本一律不替换，仅在下方预留空间与挂载点。

| 待替换资产 | 位置 | 说明 |
|---|---|---|
| Favicon / 标题 | `ptv-frontend/index.html`（`<link rel="icon">` + `<title>`） | 已留注释 |
| 侧边栏 LOGO | `ptv-frontend/src/components/Layout.tsx`（`<img src="/logo.png">` 挂载点） | 已留注释 |
| Logo / 图标文件 | `ptv-frontend/public/favicon.ico`、`ptv-frontend/public/logo.png` | 待放入资产 |
| 下载站页头 LOGO | `deploy/dl-web/index.html` + `deploy/dl-web/styles.css` | 待替换 |
| 下载站资源 | `deploy/dl-web/assets/` | 待放入资产 |

替换完成后，将 `ComplianceController` 的 `/branding` 端点 `status` 由 `PENDING_BRAND_ASSET`
改为 `READY`，并登记实际资产路径即可。