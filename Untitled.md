# 

基于 PACC v4\.0 设计文档，以下是项目所需的完整环境清单，按开发、运行、服务端、基础设施四大类整理。

一、开发环境



1. 核心语言工具链

语言 版本 工具链 用途 

Java 21 LTS JDK 21 \+ Maven/Gradle \+ Spring Boot 3\.x 玩家端用户态服务、Java 版探针、PTV 微服务 

C/C\+\+ C11 / C\+\+20 MSVC（Windows）\+ WDF 驱动框架 / GCC \& Clang（Linux/Android NDK） 内核驱动、Native 检测引擎、游戏内注入模块、JVM TI 原生代理 

Rust stable rustup \+ cargo 安全敏感模块、高性能数据管道、本地加密持久化引擎 

Python 3\.11\+ pip \+ conda AI 模型训练、数据分析、特征工程、威胁情报分析 



2. 平台专属开发环境

平台 语言 IDE / SDK 用途 

Android Kotlin \+ C/C\+\+\(NDK\) Android Studio \+ Android SDK Android 客户端 

iOS / iPadOS Swift \+ Objective\-C \+ C Xcode \+ iOS SDK iOS/iPadOS 客户端、mach\_vm 内存访问 

HarmonyOS ArkTS \+ C/C\+\+ DevEco Studio \+ HarmonyOS SDK 鸿蒙原生客户端、原子化服务 

Windows 工具 C\# Visual Studio \+ \.NET 安装程序 GUI、配置管理工具、诊断工具 

服务端网关 Go Go toolchain API 网关、红屏广播推送、高性能反向代理 



3. 前端开发环境

类别 技术 用途 

框架 React \+ TypeScript PTV 管理后台、PTEID 注册登录页、玩家自助门户 

可视化 ECharts 数据大盘、趋势图表、作弊类型分布 

实时通信 WebRTC 管理员远程查端屏幕共享 

构建 Node\.js LTS \+ Vite/Webpack 前端构建工具链 



4. 脚本与运维语言

语言 用途 

PowerShell Windows 客户端部署、驱动安装、批量部署脚本 

Bash PTV 服务器运维、CI/CD 流水线、Linux 部署、日志分析 

Lua 动态规则引擎、热更新检测规则、玩家端轻量脚本扩展 



二、客户端运行环境

平台 系统版本 硬件要求 特殊要求 

Windows（核心） Windows 10/11 64位 x86\-64 CPU \+ SSE 4\.2，4GB\+ 内存，500MB 磁盘 管理员权限，WHQL 签名驱动，杀毒软件白名单 

Android Android 8\.0\+ ARM64\-v8a，2GB\+ 内存 Root/非Root双模式，无障碍服务权限（非Root） 

iOS iOS 14\.0\+ 64位设备 越狱/非越狱双模式，非越狱通过 TestFlight 分发 

iPadOS iPadOS 14\.0\+ 64位 iPad 与 iOS 共享代码库，平板适配 

HarmonyOS HarmonyOS 3\.0\+ 鸿蒙设备 原生 ArkTS，支持原子化服务免安装 

Linux（Java 版） Ubuntu 20\.04\+ / Debian 11\+ / Arch x86\-64，4GB\+ 内存 Java 8\-21（游戏 JVM），systemd 服务管理 



三、PTV 管控服务端环境



1. 应用运行时

组件 技术 用途 

核心微服务 Java 21 LTS \+ Spring Boot 3\.x PTEID 账号服务、检测数据服务、红屏警告服务、记录服务、特征库服务、运营后台服务 

API 网关 Go 统一入口、鉴权、限流、路由 

实时推送 Go \+ WebSocket 万级玩家并发红屏广播推送 

AI 推理 Python \+ PyTorch / scikit\-learn XGBoost 分类、LSTM\-AE 时序异常检测、行为画像 

远程查端 WebRTC \(SFU\) 管理员实时屏幕查看、证据远程提取 

高性能管道 Rust \+ Kafka 消费者 检测数据流式处理 



2. 数据存储

类型 技术 存储内容 

关系型数据库 MySQL 8\.0\+ PTEID 账号、作弊记录索引、管理员账号、操作审计日志 

时序数据库 ClickHouse 玩家行为数据、检测事件日志、性能指标（高吞吐写入） 

搜索引擎 Elasticsearch 日志全文检索、特征库搜索、记录多维查询 

缓存 Redis 7\+ 在线玩家状态、会话 Token、特征库热缓存、限流计数 

对象存储 S3 兼容对象存储 内存快照、行为数据回放、证据文件、日志归档 

消息队列 Apache Kafka 检测数据流、红屏事件总线、特征库更新通知 



3. 数据格式与协议

类别 技术 

通信协议 Protocol Buffers（二进制序列化）\+ TLS 1\.3 加密 

API 数据交换 JSON（RESTful） 

配置文件 YAML（服务端）、TOML（客户端）、XML（Windows 安装包） 

文档 Markdown 



四、基础设施与 DevOps



1. 容器化与编排

技术 用途 

Docker 所有微服务容器化打包 

Kubernetes 生产环境编排、自动扩缩容、滚动更新 

Docker Compose 开发/测试环境一键部署 

Helm K8s 应用包管理 



2. CI/CD

技术 用途 

Git 版本控制（多仓库：客户端/驱动/服务端/前端/AI） 

CI 流水线 Jenkins / GitLab CI / GitHub Actions 

代码质量 SonarQube（静态分析）、Clang\-Tidy（C/C\+\+）、Clippy（Rust） 

制品管理 Nexus / Harbor（Docker 镜像） 

驱动签名 微软硬件开发者中心（WHQL 签名） 



3. 监控与可观测性

技术 用途 

Prometheus \+ Grafana 服务指标监控、数据大盘、告警面板 

ELK / Loki 日志聚合与检索 

Jaeger / Zipkin 分布式链路追踪 

告警系统 Alertmanager \+ 邮件/短信/飞书通知 



4. 安全基础设施

技术 用途 

TLS 1\.3 证书 全链路加密传输 

KMS / Vault 密钥管理、API Key、数据库密码、加密密钥 

代码签名证书 Windows 驱动 WHQL 签名、客户端可执行文件签名 

HMAC\-SHA256 检测上报数据签名防篡改 

AES\-256\-GCM 本地数据加密、敏感数据存储加密 

bcrypt 密码哈希存储 



五、AI / 数据科学环境

类别 技术 用途 

深度学习 PyTorch / TensorFlow LSTM\-AE 时序异常检测、Transformer 行为序列建模 

机器学习 scikit\-learn（XGBoost、随机森林、Isolation Forest） 作弊分类、风险评分、异常聚类 

数据处理 pandas \+ NumPy 特征工程、数据清洗、统计分析 

实验管理 MLflow / Weights \& Biases 模型版本管理、实验追踪、模型注册 

交互式分析 Jupyter Notebook 数据探索、特征分析、误报排查 

向量计算 Java Vector API（玩家端） 内存特征码 SIMD 加速匹配 



六、汇总统计

维度 数量 清单 

编程语言 13 种 Java、C、C\+\+、Rust、Python、Kotlin、Swift、Objective\-C、ArkTS、C\#、Go、TypeScript、Lua 

数据库/存储 5 种 MySQL、ClickHouse、Elasticsearch、Redis、对象存储 

消息队列 1 种 Kafka 

容器/编排 3 种 Docker、Kubernetes、Docker Compose 

客户端平台 6 个 Windows、Android、iOS、iPadOS、HarmonyOS、Linux 

游戏版本 2 个 Minecraft 基岩版、Minecraft Java 版 



