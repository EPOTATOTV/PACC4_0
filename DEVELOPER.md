# PACC 开发者文档

> 这份文档写给**刚接触或经验不多**的开发者。会从「这项目是啥」「要装什么」「怎么跑起来」「怎么加接口 / 加页面」一路讲到更深入的话题。不涉及内部检测算法等敏感内容——那些内容不适合公开，也没必要为了开发而掌握。

---

## 1. 先说人话：这个项目是什么

PACC 是一套「玩家本地检测 + PTV 管控」的反作弊系统。你可以把它理解成两个部分：

1. **玩家端**：装在被检玩家的设备上，在本地采集检测数据、出警告。
2. **管控端（PTV）**：管理后台 + 后端服务，管理员在这里查看记录、远程查端、发红屏警告。

里面的数据流大概是：

```
玩家设备 → 玩家端服务(ptv-client) → 后端(ptv-backend) → 管理后台/小程序/桌面壳查看
```

作为开发者，你绝大多数时候打交道的是**后端**（`ptv-backend`，Java）和**前端**（`ptv-frontend`，React），以及把两者串起来的**通信协议**和**部署编排**（Docker）。

## 2. 需要先装的东西（英文名词 + 人话解释）

| 工具 | 用于 | 装机说明 |
|---|---|---|
| **JDK 21** | 跑 Java 后端、玩家端 | Java 的运行时 + 编译工具，装 LTS 21 版 |
| **Maven** | 管理 Java 依赖和构建 | 类似「Java 的包管理器」，帮你下载库、编译、打包 |
| **Node.js 18+** | 跑前端 | 前端 JavaScript 运行的运行时 |
| **npm** | 前端依赖管理 | Node 自带的「包管理器」 |
| **Docker** | 一键跑整套服务（可选，但要部署就得装） | 把服务打包成容器，隔离开来跑 |

> 你不需要一开始就懂 Docker。本地开发可以直接用命令跑后端和前端，不碰 Docker 也行。只有「部署到服务器」才需要 Docker。

## 3. 目录结构速览

```
d:\pacc\
├── pacc-binary-protocol/  # 自研二进制协议（PBP）：mdl 定义 + 五个运行时（java/ts/rust/csharp/python）
├── tools/pbpgen/       # MDL 代码生成器（Python 标准库，生成五种语言的消息类）
├── proto/              # 旧 Protocol Buffers 定义（已被 PBP 取代，仅存档，不再有生成步骤）
├── ptv-backend/        # 管控后端（Java 21 + Spring Boot 3）★ 后端开发主战场
├── ptv-frontend/       # 管理后台/玩家门户前端（TypeScript + React + Vite）★ 前端主战场
├── ptv-client/         # 玩家端用户态服务（Java 21）
├── platform/           # 平台相关（内核/Java Agent/移动端/Linux 探针，偏硬件/底层）
├── deploy/             # 部署相关（网关、下载站、监控、单机脚本）
├── tools/
│   ├── windows-gui/    # Windows 管理工具（C#/.NET：装探针、诊断、更新）
│   └── installer/      # 安装向导（Inno Setup）
├── docker-compose.yml  # 一键部署整套服务的编排文件
├── .env.example        # 环境变量模板（把想要的密钥填进去）
└── README.md           # 入口说明，先读它
```

**先装协议运行时再编后端/玩家端**：仓库没有 root 聚合 pom，`ptv-backend` 与 `ptv-client` 都依赖
`com.potatotv:pacc-binary-protocol`，所以第一次构建前要把它装进本地 Maven 仓库，否则会以
「无法解析依赖」失败：

```bash
mvn -B -f pacc-binary-protocol/runtime-java/pom.xml install
```

这个模块的版本是独立的 1.0.0，不跟 PACC 整体升版走。改了 `pacc-binary-protocol/mdl/*.mdl` 之后要重新生成
各语言消息类（生成物入库，不是构建期产物）。生成器一次生成五种语言，`--check` 会把五种语言的
漂移一起比出来：

```bash
cd tools/pbpgen
python -m pbpgen            # 重新生成 Java/TS/Rust/C#/Python
python -m pbpgen --check    # 只校验有没有漂移（CI 用这条）
```

各语言运行时的测试（跨语言互操作由 `pacc-binary-protocol/test-vectors/interop.txt` 的向量锁住，
向量是 Java 侧导出的生成物，勿手改）：

```bash
mvn -B -f pacc-binary-protocol/runtime-java/pom.xml test
cd pacc-binary-protocol/runtime-ts     && npm ci && npm test
cd pacc-binary-protocol/runtime-rust   && cargo test
cd pacc-binary-protocol/runtime-csharp && dotnet run --project tests/Tests.csproj
cd pacc-binary-protocol/runtime-python && python -m unittest discover -s tests -t .
```

自研的 zstd 子集（Java 运行时内置，零第三方依赖）要和参考实现对齐，改压缩相关代码后跑一次双向
交叉校验（需要 `python -m pip install zstandard`，只是开发/CI 工具，不是仓库依赖）：

```bash
python pacc-binary-protocol/tools/zstd-crosscheck/crosscheck.py --mvn mvn
```

**给新手的最短路径**：先看 `README.md`，然后把 `ptv-backend` 和 `ptv-frontend` 跑起来，其它目录先放着。

## 4. 本地把后端跑起来（逐步）

### 4.1 确认环境

```bash
java -version         # 应显示 21 或更高
mvn -version          # 应显示 Maven 3.8+
```

### 4.2 启动后端

```bash
cd ptv-backend
mvn clean package          # 编译 + 打包（第一次会比较久，因为要下载依赖）
mvn spring-boot:run        # 或者用这条直接跑，省去上面手动打包
```

启动成功后，后端默认监听 **8080** 端口。验证：

```bash
curl http://localhost:8080/actuator/health
# 返回 {"status":"UP"} 之类的就说明起来了
```

**重要**：本地默认用的是 **H2 内存数据库**，不需要你额外安装 MySQL，数据关机就没了，非常适合开发。

### 4.3 想启动后自带演示数据

```bash
# 在 ptv-backend 目录，把环境变量设上再启动
set PACC_SEED=true    # Windows（PowerShell 用 $env:PACC_SEED="true"）
mvn spring-boot:run
```

这样会预置演示账号、设备、赛事等数据，方便你点一点就有效果。

## 5. 本地把前端跑起来（逐步）

```bash
cd ptv-frontend
npm install          # 安装依赖（第一次较久）
npm run dev          # 启动开发服务器
```

- 默认地址：**http://localhost:5173**（Vite 的默认端口）
- 开发时前端会把自己的请求代理到后端 8080（代理配置在 `vite.config.ts`）。
- 所以开发模式你**不需要手动启动 nginx**，前端 dev server 自己会把 `/api` 请求转发到后端。

> 如果前端一直报「请求失败 / 连接被拒」，先确认后端是不是真的在 8080 跑起来了。

## 6. 前端要连哪个后端？聊聊「代理」

浏览器里的页面出于安全**不能随便跨域**。开发时最常见的坑就是：前端在 5173，后端在 8080，直接请求会被「跨域」拦下。

解决方式是**代理**：让前端开发服务器（Vite）把 `/api/**` 的请求原样转发给 `http://localhost:8080`。你写前端代码时只需要请求 `/api/...` 的**相对路径**，Vite 帮你转发，浏览器以为请求的就是同源地址，就不会报跨域。

> 生产环境里，这个「代理」的角色由部署层的网关（nginx）扮演。原理一样：按域名/路径把请求分发给对应服务。

## 7. 鉴权：三套系统各管各的

这项目有三套「登录/授权」，用**路由前缀**区分：

| 前缀 | 给谁 | 认证方式 | 最简单的理解 |
|---|---|---|---|
| `/api/auth`、`/api/player` | 玩家 | 登录后下发 **Cookie**（HttpOnly） | 浏览器自动带 Cookie，服务端认出是谁 |
| `/api/admin/**` | 管理员 | 请求头 `X-Admin-Key` **或**登录后的 Cookie | 管理员登录后也走 Cookie |
| `/api/v1/**` | 第三方接入方 | API Key + **HMAC-SHA256 签名** | 靠「签名」证明请求是真的、没被改 |

关键记忆点：

- **Cookie 是浏览器自动的**：玩家/管理员登录成功后，浏览器自动持有会话，后续请求自动带上，前端代码基本不用管。
- **别再手动把 token 存 localStorage**：这项目设计要求走 Cookie，更安全。
- **开放 API 要自己算签名**：`X-PTV-Signature = HMAC-SHA256(secret, 规范化字符串)`。你只要保证「时间戳在窗口内、nonce 唯一、用同一个 secret 算」，签名就对得上。具体算法见下文 API 参考。

> 安全性要求里有一条：**登录/鉴权失败一律返回通用提示**，不告诉你「这个账号存在不存在」或「账号被锁了」，这是防爆破。你测接口时别惊讶为什么错误都一样。

## 8. 我最想加一个新接口，怎么加（后端）

以「给后端加一个返回版本号的接口」为例，讲清楚套路（Spring Boot）：

1. 找到放接口的包路径：`ptv-backend/src/main/java/com/potatotv/pacc/controller/`。
2. 新建或打开一个 Controller（Java 类，用 `@RestController` 标注）。
3. 写一个方法，用注解挂到路径上：

```java
@RestController
public class DemoController {

    @GetMapping("/api/demo/version")
    public Map<String, String> version() {
        return Map.of("version", "5.0.0");
    }
}
```

4. 重启后端，访问 `http://localhost:8080/api/demo/version` 就能看到结果。

**几个 Spring Boot 要点**：

- `@RestController`：这个类的方法返回的是纯数据（自动转成 JSON），不是页面。
- `@GetMapping("/路径")`：把方法绑到「GET + 该路径」。还有 `@PostMapping`、`@PutMapping`、`@DeleteMapping`。
- 方法的返回值会被自动转成 JSON 发给调用方（Map、自定义对象的字段都能转）。
- 想接路径里的参数用 `@PathVariable`，想接查询参数用 `@RequestParam`，想接请求体用 `@RequestBody`。

> 别把内部逻辑一股脑写进 Controller。复杂业务放 `service/` 目录，数据存取放 `repository/` 目录，Controller 只负责「收请求、调服务、返结果」。这样代码好维护，也是这项目的分层约定。

## 9. 我想加一个新页面（前端）

以「加一个 /about 页面」为例：

1. 在 `ptv-frontend/src/pages/` 下新建 `About.tsx`（一个新的 React 组件）。
2. 在路由配置里加一条 `/about → About`。（路由/菜单相关文件在 `src/` 的导航配置里，一般是 `App.tsx` 或单独的路由文件。）
3. 打开前端：`http://localhost:5173/about` 就能看到。

**前端常用结构**：

- `src/pages/`：页面级组件，一页一个。
- `src/components/`：可复用的小组件。
- `src/api/`：封装了请求后端函数的地方，统一走这里，**别在页面里裸写 fetch**。这样改接口地址、加统一请求头都只动一处。
- `src/locales/`：多语言文案（中文/英文/日/韩等）。加了新文案记得同步多语言，不然切换语言会缺字。

**两个开发习惯**（代码规约里明确要求）：

- 组件里用 `useCallback` 稳定函数引用，避免依赖数组导致重复渲染（也避免 ESLint 的 `exhaustive-deps` 报错）。
- 请求响应别用 `any`，用真正的 TypeScript 类型，类型更安全、编辑器补全更好用。

## 10. 部署到服务器（一句话版，详细见 deploy/server/README.md）

```bash
cp .env.example .env    # 把里面的占位符换成你的真实密码/密钥
docker compose up -d --build   # 一键构建并启动整套服务
docker compose ps        # 看是否全部 healthy
```

- 优点：一套命令把所有服务（MySQL + 后端 + 前端 + 网关）都跑起来，还带健康检查和自动重启。
- 密钥从哪来：`.env.example` 只是模板，真正的 `.env` 文件是被 git 忽略的，不会提交到仓库。
- 生产要配 HTTPS、要把四个子域解析到服务器等，具体看部署教程。

> `.env` 是「环境变量」概念的落地：把密码、密钥、各家服务的开关放在一个文件里，程序启动时读，**不写进源码**。好处是：换环境（本地/测试/生产）只改文件不改代码，也避免把密码不小心提交到 git。

## 11. 常用命令速查

**后端**：
```bash
cd ptv-backend
mvn -B -f ../pacc-binary-protocol/runtime-java/pom.xml install   # 首次 / 协议改动后（见 §3）
mvn clean package                 # 编译打包
mvn spring-boot:run               # 直接运行
mvn test                          # 跑单元测试
```

**前端**：
```bash
cd ptv-frontend
npm install                       # 装依赖
npm run dev                       # 开发模式
npm run lint                      # 检查代码规范
npm run build                     # 生产构建（tsc 类型检查 + vite 打包）
```

**部署**：
```bash
docker compose ps                 # 各容器状态
docker compose logs -f ptv-backend  # 看后端日志
docker compose down               # 停止（数据保留）
```

## 12. 数据类型备忘：字节 / 端口 / 端口命名

- 后端 REST 默认 `8080`，前端 dev 默认 `5173`，前端生产容器里用 `80`（由 nginx 托管）。
- 玩家长连接是 WebSocket，地址形如 `ws://localhost:8080/ws/ptv`。
- 绝大多数接口返回 JSON，错误用 **HTTP 状态码**表达（见下面状态码表）。

## 13. 状态码速查

| 状态码 | 含义 | 常见场景 |
|---|---|---|
| `200` | 成功 | 请求处理完成 |
| `400` | 请求参数不对 | 缺字段、格式错 |
| `401` | 未登录 / token 无效 / 签名错 | 没带 Cookie，或开放 API 签名不对 |
| `403` | 有身份但没权限 | 角色不够、来源受限 |
| `404` | 路径/资源不存在 | 拼错接口路径 |
| `429` | 触发限流 | 请求太频繁 |
| `5xx` | 服务器内部出错 | 后端异常（响应只给通用提示，不泄露堆栈） |

## 14. 开放 API 怎么调（第三方接入）

这是给「想用 PACC 提供数据」的第三方看的。需要服务端先给你签发一个 API Key（含 `key id` 和 `secret`）。每次请求带四个请求头：

| 请求头 | 含义 |
|---|---|
| `X-PTV-Key` | 你的 Key ID（告诉服务端用哪把钥匙） |
| `X-PTV-Timestamp` | 毫秒时间戳，和服务端时钟差要小 |
| `X-PTV-Nonce` | 一次性随机串，防重放 |
| `X-PTV-Signature` | `HMAC-SHA256(secret, 规范化字符串)` 的十六进制 |

**规范化和签名的原理（一步步教你）**：

```
1. 拼一段规范字符串 canonical：
   "{方法}\n{路径}\n{时间戳}\n{请求体SHA256的十六进制}"
   例： "GET\n/api/v1/detections\n1710000000123\n<空串的sha256>"

2. 用 secret 对 canonical 做 HMAC-SHA256，结果转十六进制，放进 X-PTV-Signature。
```

**为什么这么设计（面试/理解用）**：

- 带上方法、路径、时间戳、请求体，是为了「请求的每一部分都不能被偷偷改」——改了签名就配不上。
- 带时间戳 + nonce，是为了「同一份请求不能被人录下来重放」。
- `secret` 只有服务端和你手里有，所以别人不知道就伪造不出签名。

伪造/乱来的结果：`401`（签名/时间戳/nonce 校不过）、`403`（IP 不在白名单）、`429`（限流）。

## 15. 数据库

- **生产**：MySQL 8。表结构由 **Flyway** 管理——就是一堆有编号的 SQL 脚本（`ptv-backend/src/main/resources/db/migration/`），启动时会按编号自动按顺序执行，保证数据库迁移可追溯。
- **本地开发**：H2 内存库，不用装任何东西，关停即清空。跑演示数据用 `PACC_SEED=true`。

> Flyway 和「改表」：如果你改了实体（Entity），不要手改现成的库，而是去 `db/migration/` 新加一个递增编号的 `.sql` 脚本去改表。这样每个环境的库结构都能一致升级。

## 16. 环境变量总览（配置从哪来）

项目设计的硬约束：**所有密码、密钥、开关一律走环境变量 / `.env`，绝不写死在源码里**。

- 模板在根目录 `.env.example`。
- 真正生效的是 `.env`（已被 git 忽略）。
- 生产环境如果关键密钥缺失，后端会**拒绝启动**（fail-closed），防止带病上线。

常用变量（以 `.env.example` 为准，这里提示几个高频的）：

| 变量 | 作用 |
|---|---|
| `MYSQL_*` | 数据库账号密码 |
| `PACC_ADMIN_API_KEY` | 管理后台登录 Key |
| `PACC_SECURITY_JWT_SECRET` | JWT 签名密钥 |
| `PACC_SECURITY_WSS_SIGN_SECRET` | WebSocket 消息签名密钥 |
| `PACC_MAIL_STUB_ENABLED` | 邮件是否用「打桩」模式（本地调试用 true，生产改 false） |
| `PACC_SEED` | 是否生成演示数据（生产默认 false） |
| `PACC_FEISHU_*` | 管理员飞书登录相关（可选） |

## 17. 常见坑（经验之谈）

- **端口占用**：后端/前端起不来，先看是不是 8080/5173 被占了。`netstat -ano | findstr 8080`。
- **前端代理失效**：看看项目里是不是同时存在 `vite.config.ts` 和 `vite.config.js`——Vite 会优先用 `.js`，可能造成代理目标不对。删掉多余的 `.js/.d.ts`。
- **改了实体但库没变**：本地 H2 或生产 MySQL 的表结构要靠 Flyway 迁移，别指望 JPA 自动改。参考第 15 节。
- **启动一次失败、改完还失败**：Java 项目建议 **clean**（`mvn clean package`）而不是增量，避免旧编译缓存掩盖错误。
- **密钥配了还是不生效**：后端很多配置是启动时读的，改完 `.env` 要**重启服务**。
- **登录老报「账号不存在或密码错误」**：这是刻意的通用提示（防爆破），不代表你密码真的错。先确认数据有没有（比如有没有用 `PACC_SEED=true` 生成演示账号）。

## 18. 安全红线（每个开发者都该记）

- 🔴 密钥、证书、私钥、SMTP 密码一律进 `.env`，**绝不写进代码或提交 git**。
- 🔴 不要提交 `.env`、`*.key`、`*.pem`、`*.p12` 等（`.gitignore` 已拦，别强行 `-f` 加）。
- 🔴 登录/鉴权失败返回通用提示，不暴露内部细节。
- 🔴 不要在日志里打印密码、token、Cookie、请求头。
- 🔴 别把内部设计文档、部署教程里的服务器/域名细节提交到公开仓库。
- 🟡 写代码时保持分层（Controller / Service / Repository），不混在一坨。

---

## 附：进一步阅读

- 部署教程：`deploy/server/README.md`
- 网关证书/HTTPS：`deploy/gateway/certs/README.md`
- 前端工程细节：`ptv-frontend/` 内各自的 README（若有）
- 各平台（桌面/移动/Android 探针）说明：`ptv-desktop/README.md`、`ptv-mobile/README.md`、`platform/*/README.md`

> 遇到不确定的，先读对应子目录的 README，再看代码里的注释和 `README.md` 总入口。开发中要有「先最小复现、再动手改」的习惯，改一处跑一次，别一次性大改。