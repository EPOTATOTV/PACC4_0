# PCU API 参考

PCU（`com.potatotv:pacc-cross-platform-updater`）是 PACC 玩家端的更新核心。它管的是
「检查 → 下载 → 校验 → 应用 → 验证启动」这条链路，已经封好的部分尽量别绕过去自己拼
HTTP 请求——链路里几个「什么时候上报、失败算什么状态」的判断点都收在
`UpdateOrchestrator` 里。

本文按源码实际签名写。构造方法、方法返回值和抛出的异常都以 `src/main/java/com/potatotv/pcu/`
为准，方法名、参数、字段都能在对应类里找到。

## 零第三方依赖这条约束

`pom.xml` 里挂了一条 maven-enforcer 规则：

```xml
<bannedDependencies>
    <searchTransitive>true</searchTransitive>
    <excludes>
        <exclude>*:*:*:*:compile</exclude>
        <exclude>*:*:*:*:runtime</exclude>
    </excludes>
</bannedDependencies>
```

意思是：编译与运行期的依赖，只要出现一个第三方 artifact（含传递依赖），`mvn validate`
直接失败。唯一允许的是 test 作用域的 JUnit（`org.junit.jupiter:junit-jupiter`，5.10.2）。

这条约束的实际后果，写代码时要心里有数：

- 下载走 `java.net.http.HttpClient`，校验走 `java.security`，解压走 `java.util.zip`，
  JSON 解析用自研的 `PcuJson`，bzip2 用自研的 `BZip2`，差分用自研的 `BsDiff`/`BsPatch`。
  换任何一个库（Gson、OkHttp、commons-compress 之类）都会被构建拦下。
- 发布用的 maven-javadoc / gpg / nexus-staging 这几个**插件**放在 `release` profile 里，
  插件不是 dependency，绕得开门禁；普通构建不受影响。

## 目录与包

| 包 / 位置 | 内容 |
|---|---|
| `com.potatotv.pcu` | 核心：配置、编排、检查、下载、校验、应用、回滚、备份、差分、上报、模型 |
| `com.potatotv.pcu.platform` | 各平台薄适配层（Windows/Linux/macOS/iOS/移动端） |

---

## 一、配置：`PcuConfig`

运行配置，用 builder 构造。`currentVersion` 是必填项，缺失或格式非法在 `build()` 阶段就抛。

```java
PcuConfig config = PcuConfig.builder()
        .baseUrl("https://api.potatotv.asia")      // 默认值即此
        .currentVersion("5.4.0")                    // 必填，会被 SemVer.parse 校验
        .platform(UpdatePlatform.WINDOWS)           // 不填时按运行环境推断
        .channel(UpdateChannel.STABLE)
        .pteid("PT0000000001")
        .adapter(new WindowsPlatformAdapter(installDir, tempDir))
        .signaturePublicKey(UpdateVerifier.decodePublicKey(pemBase64))  // 发行环境必填
        .mainArtifact(installDir.resolve("ptv-client-5.4.0.jar"))
        .backupRoot(installDir.resolve("backup"))
        .build();
```

Builder 的默认值（源码 `Builder` 字段初始化处）：

| 字段 | 默认值 | 说明 |
|---|---|---|
| `baseUrl` | `https://api.potatotv.asia` | 末尾斜杠会被去掉 |
| `channel` | `UpdateChannel.STABLE` | |
| `allowDelta` | `true` | |
| `silentUpdate` | `false` | |
| `maxRetries` | `3` | 负数会被夹成 0 |
| `retryBaseDelay` | `500ms` | 指数退避基数 |
| `connectTimeout` | `10s` | |
| `requestTimeout` | `30s` | |
| `healthCheckTimeout` | `30s` | |
| `healthCheckInterval` | `1s` | |
| `keepBackups` | `2` | 最小夹到 1 |
| `userAgent` | `PACC-PlayerClient/<当前版本> (pcu)` | |
| `backupRoot` | `adapter().getInstallDir()/backup` | |
| `healthProbe` | `HealthProbe.assumeHealthy()` | 直接算健康并告警 |

读取侧全部是访问器：`baseUrl()`、`platform()`、`currentVersion()`、`currentSemVer()`、
`channel()`、`pteid()`、`adapter()`、`signaturePublicKey()`、`backupRoot()`、`mainArtifact()`、
`allowDelta()`、`silentUpdate()`、`maxRetries()`、`retryBaseDelay()`、`connectTimeout()`、
`requestTimeout()`、`healthCheckTimeout()`、`healthCheckInterval()`、`healthProbe()`、
`keepBackups()`、`userAgent()`、`downloadHosts()`。

几个点值得单说：

- **`mainArtifact()`**：非 zip 单文件制品的落地路径（客户端 JAR）。为 `null` 时制品当 zip 解包，
  逐个覆盖安装目录同名相对路径；**差分只对单文件制品有意义**，所以为 null 时不会启用差分。
- **下载白名单**：`downloadHosts()` 不设时，默认是 `pacc.potatotv.asia` + `dl.potatotv.asia`
  + `baseUrl` 的主机（本地联调与测试假服务用）。清单一律小写、不含端口。制品放别处用
  `downloadHosts(Set<String>)` 覆盖，全空会抛 `PcuException`。
- 常量：`DEFAULT_BASE_URL`、`DOWNLOAD_HOST`（`pacc.potatotv.asia`）、
  `DOWNLOAD_SITE_HOST`（`dl.potatotv.asia`）。

`build()` 会抛 `PcuException`：`currentVersion` 为 null / 版本号非法 / 某个超时非正数 /
下载白名单全空。

---

## 二、编排：`UpdateOrchestrator`

总入口，把各环节串成一条链路，并决定「什么时候上报、失败算什么状态」。

```java
UpdateOrchestrator orchestrator = new UpdateOrchestrator(config);
```

- `UpdateOrchestrator(PcuConfig config)`：内部用 `HttpClients.create(config)` 造检查/上报客户端，
  用 `HttpClients.createForDownload(config)` 造下载客户端（后者强制 HTTP/1.1）。
- `UpdateOrchestrator(PcuConfig config, HttpClient controlHttp, HttpClient downloadHttp)`：
  两个客户端都可注入，测试里用得着。

方法：

| 方法 | 返回 | 说明 |
|---|---|---|
| `check()` | `UpdateManifest` | 只检查，不下载不安装 |
| `runOnce()` | `UpdateResult` | 检查并走完整个流程，进度回调为 `ProgressListener.NOOP` |
| `runOnce(ProgressListener listener)` | `UpdateResult` | 带下载进度回调 |

流程里的几个定死的取舍（源码注释里写得很直白）：

- **检查更新本身失败不产生上报**，返回 `Outcome.SKIPPED`。没有目标版本，报 FAILED 只会污染成功率统计。
- **差分失败不算更新失败**：自动回退全量下载（`deltaApplied` 保持 false）。
- **静默更新失败不弹通知，但照常上报**。
- 应用阶段若交由系统安装器（`ApplyOutcome.delegatedToInstaller()` 为 true），直接上报 SUCCESS 并返回，
  后续替换与重启由系统决定。
- 应用之后要过健康检查窗口（默认 30 秒轮询）；窗口内一次都不健康即回滚。健康检查通过后才按保留策略清备份。
- 制品落地文件名为 `pacc-update.bin`（`ARTIFACT_NAME`，包级常量），临时工作目录前缀 `pcu-`。

---

## 三、检查更新：`UpdateChecker`

`GET /v1/update/check`（`CHECK_PATH`）。

| 构造 | 说明 |
|---|---|
| `UpdateChecker(PcuConfig config)` | 内部造客户端 |
| `UpdateChecker(PcuConfig config, HttpClient http)` | 注入客户端 |

| 方法 | 返回 | 异常 |
|---|---|---|
| `check()` | `UpdateManifest` | 非 200 抛 IO 失败 → `Retry` 重试耗尽后抛 `PcuException`；响应非法 JSON 抛异常 |
| `buildUri()` | `URI` | 供测试与联调核对参数 |

查询参数：`platform`、`current_version`、`channel`，`pteid` 非空时才带。
异常与日志只带路径和状态码，不带完整 URL——URL 里含 `pteid`。

---

## 四、下载：`UpdateDownloader`

`GET`（可选 `Range`）下载制品，支持断点续传、进度回调、指数退避重试。

| 构造 | 说明 |
|---|---|
| `UpdateDownloader(PcuConfig config)` | 内部造下载客户端 |
| `UpdateDownloader(PcuConfig config, HttpClient http)` | 注入客户端 |

| 方法 | 返回 | 说明 |
|---|---|---|
| `download(String url, Path target, String expectedChecksum, long expectedSize)` | `Path` | 无进度回调 |
| `download(url, target, expectedChecksum, expectedSize, ProgressListener listener)` | `Path` | 落成品前完成「长度 + SHA-256」校验 |
| `static partFileFor(Path target)` | `Path` | 中间文件路径（`<target>.part`，`PART_SUFFIX`） |

编排层用的就是第二个重载。要点：

- 中间文件与目标同目录（保证 rename 原子），下载完成前不碰目标文件。
- 制品主机必须落在 `config.downloadHosts()` 内，且非回环时必须 https；**跟随重定向落地后会再核对一次主机名**，
  重定向到非授权主机直接中止。
- 服务端返回 206 按续传，200 从头写，416 视为已到末尾。
- 长度不符或 SHA-256 不匹配，删掉 `.part` 并抛 `PcuException`；IO 失败由 `Retry` 重试，
  **校验失败是确定性结果，不重试**。
- `PcuException`：URI 非法、非授权地址、长度不符、校验和不匹配、临时目录建不出来。

---

## 五、校验：`UpdateVerifier`

`UpdateVerifier(PcuConfig config)`。构造时若 `signaturePublicKey()` 为 null，会打一条 severe 日志：
后续链路只校验 SHA-256，不校验发布方签名。

| 方法 | 返回 | 抛异常的情形 |
|---|---|---|
| `verifyManifest(UpdateManifest manifest)` | `void` | 平台不匹配、目标版本不高于当前版本（判降级） |
| `verifyArtifact(Path artifact, UpdateManifest manifest)` | `void` | 长度不符、checksum 缺失/不符、签名缺失/不符 |
| `verifyChecksum(Path artifact, String expectedChecksum)` | `void` | 清单缺 checksum，或不匹配 |
| `verifySignature(Path artifact, String signatureBase64)` | `void` | 配了公钥却没给签名，或签名不过 |
| `forceRequired(UpdateManifest manifest)` | `boolean` | 不抛；服务端 `force_update` 或当前版本低于 `min_app_version` |
| `static decodePublicKey(String base64X509)` | `PublicKey` | 解析失败抛 `PcuException`；入参空返回 null |

签名是 **fail-closed** 的：配置里给了公钥、服务端却没下发签名，直接判失败，不允许静默降级为只校验哈希。
算法 `SHA256withRSA`（RSA-2048，PKCS#1 v1.5），签名值是 Base64。`decodePublicKey` 能识别
带 `-----BEGIN PUBLIC KEY-----` 头的 PEM，也能直接吃纯 Base64 的 X.509 SubjectPublicKeyInfo。

所有校验失败都是 `PcuException`（非受检）。

---

## 六、应用：`UpdateApplier`

`UpdateApplier(PcuConfig config, BackupManager backups)`。备份之后停服 → 原子替换 → 重启。

```java
UpdateApplier.ApplyOutcome apply(Path artifact, String version)
```

`ApplyOutcome` 是个 record：`backup`（`BackupManager.BackupHandle`）、`targets`（`List<Path>`）、
`delegatedToInstaller`（boolean）。方法 `rollbackable()` 判断有没有可回滚的本地备份。

两种制品形态：

- 配了 `mainArtifact()`：制品整体替换那一个文件。
- 没配：制品必须是 zip（内部校验 `PK` 魔数，否则抛 `PcuException`），按条目覆盖安装目录同名相对路径；
  条目名经 `resolveUnder` 校验不能越出安装目录（**Zip Slip**）。
- adapter 是 `PackageInstallerAdapter` 时，整包交给系统安装器，返回 `delegatedToInstaller=true`、
  backup 为 null，不做文件替换。

应用阶段自己兜底：替换过程中任何一步失败，都会用刚建的备份把文件写回并重启旧版本，
再抛 `PcuException`（消息里带「已恢复旧版本文件」「恢复备份同样失败，需人工介入」等说明）。
所以交给上层的失败一定是「旧版本还在跑」的状态。
`ApplyOutcome.backup` 为空的那些情况（系统安装器接管、本来就没有旧文件）由编排层判定为不可回滚。

---

## 七、回滚：`UpdateRollbacker`

`UpdateRollbacker(PcuConfig config, BackupManager backups)`。

```java
void rollback(BackupManager.BackupHandle handle)
```

停新版本 → 从备份恢复 → 启动旧版本。`handle` 为 null 或空时抛 `PcuException`（「没有可用于回滚的备份」）。
走到这里说明新版本已经装好并尝试启动过，只是没通过健康检查。上报由编排层负责。

---

## 八、备份：`BackupManager`

`BackupManager(PcuConfig config)`。备份落点 `{backupRoot}/{version}/`，内容按序号存放
（`%04d.bin`），另有 `pcu-backup.manifest.json` 记录「序号 → 原始绝对路径」和本次更新新增的文件。

| 方法 | 返回 | 说明 |
|---|---|---|
| `create(String version, List<Path> sources)` | `BackupHandle` | 不存在的文件跳过并记进 `added`（回滚时要删） |
| `restore(BackupHandle handle)` | `void` | 先校验全部条目的 SHA-256 再动安装目录，一块被动过就整体失败 |
| `delete(String version)` | `void` | 删某版本备份目录 |
| `prune()` | `int` | 只保留最近 `keepBackups` 个（成功后才调），返回删除数 |
| `hasBackup(String version)` | `boolean` | |
| `open(String version)` | `BackupHandle` | 打开已有备份，供进程重启后继续回滚 |
| `static sanitize(String version)` | `String` | 版本号收紧为 `[A-Za-z0-9._-]`，禁 `.`/`..` |

`BackupHandle` record：`version`、`dir`、`sources`、`added`，方法 `isEmpty()`。

`prune()` 只清理「带清单的目录」，备份根目录里的无关子目录不会被递归删除顺手带走。

---

## 九、差分：`DeltaApplier` / `BsDiff` / `BsPatch` / `DeltaInfo`

### `DeltaInfo`

record：`fromVersion`、`url`、`checksum`、`size`。紧凑构造里 `url` 为空直接抛 `PcuException`。
对应清单里的 `delta` 字段。

### `DeltaApplier`

`DeltaApplier(PcuConfig config, UpdateDownloader downloader)`。

| 方法 | 返回 | 说明 |
|---|---|---|
| `usable(UpdateManifest manifest)` | `boolean` | 开关开、有 delta、单文件制品、delta 的 `from_version` 正好等于当前版本 |
| `produce(UpdateManifest manifest, Path output, ProgressListener listener)` | `Path` | 下载补丁 → 打补丁 → 落地产出新制品 |

`usable` 里「来源版本必须正好等于当前版本」是硬条件：跨版本补丁需要链式回放，端侧不做，
对不上直接回退全量。`produce` 打完补丁会先自查 SHA-256 与清单是否一致，不符抛 `PcuException`；
补丁文件后缀 `.pcu-patch`（`PATCH_SUFFIX`）。产物仍要被调用方过一遍 `verifyArtifact`。

### `BsDiff` / `BsPatch`

- `static byte[] BsDiff.diff(byte[] oldData, byte[] newData)`：生成标准 `BSDIFF40` 补丁，三个子流各做 bzip2。
- `static byte[] BsPatch.patch(byte[] oldData, byte[] patchData)`：应用补丁还原新文件。

服务端生成、客户端应用。`BsPatch` 只按格式解析，不假设生成端是谁（能对得上 Python `bz2`）。
校验从严：魔数、长度、CTRL 三元组、复制/插入区间任一不对都抛 `PcuException`，绝不静默截断，
也**先校验声明的 `newSize` 再分配数组**，防止伪造补丁触发 OOM。

`BZip2` 是配套的纯 Java bzip2 实现，与 libbz2 / Python `bz2` 互通，公开 `compress` / `decompress`
以及 `MAX_DECOMPRESS_BYTES`（解压输出上限）。

---

## 十、平台适配：`PlatformAdapter`

`PlatformAdapter` 是接口，核心只依赖这 8 个动作：

```java
void stopPacc();
void startPacc();
default void restartPacc();            // 默认 stop 后 start，平台需要额外等待可覆写
Path getInstallDir();
Path getTempDir();
CompletableFuture<Boolean> requestStoragePermission();
void showUpdateNotification(String title, String message);
boolean hasEnoughSpace(long requiredBytes);
```

`hasEnoughSpace` 判断的是临时目录所在分区（下载与解包都在那里）。

### 桌面基类：`ProcessPlatformAdapter`

抽象类，用 `ProcessBuilder` 驱动外部命令。受保护的 `Commands` record：
`stop`、`start`（`List<String>`）、`stopTimeout`、`startTimeout`（`Duration`，`startTimeout` 为 null
表示「拉起即返回、不等退出」，用于常驻客户端）。stop/start 必须同时给出或同时留空，否则抛 `PcuException`。
其余动作（目录、空间、权限、通知）在这一层统一实现，子类只给命令。

### 各平台适配

| 类 | 构造 | 说明 |
|---|---|---|
| `WindowsPlatformAdapter` | `(Path installDir, Path tempDir)` | 按命令行含安装目录筛 `java*` 进程再停；用当前 JVM 的 `javaw.exe` 启动安装目录里版本最高的 `ptv-client-*.jar` |
| `LinuxPlatformAdapter` | `(installDir, tempDir)` 或 `(installDir, tempDir, String unit)` | `systemctl` stop/start；`restartPacc()` 覆写为 `systemctl restart`；`DEFAULT_UNIT = "pacc"` |
| `MacOsPlatformAdapter` | `(installDir, tempDir)` 或 `(installDir, tempDir, Path launchTarget)` | `pkill -f` + `open` |
| `IosPlatformAdapter` | `(dataDir, tempDir, appStoreUrl)` 或加 `NotificationSink`、`StoreOpener` | 不做停服/重启，只热更新数据目录里的配置/规则/模型；`openAppStore()` 跳商店（未接入出口只告警） |
| `MobilePlatformAdapter` | `(UpdatePlatform platform, Bridge bridge)` | Android / HarmonyOS 通用，制品交给系统安装器 |

`WindowsPlatformAdapter` 两个静态辅助：`stopScript(Path installDir)`（返回停止命令，供测试核对）、
`resolveClientJar(Path installDir)`（按 `SemVer` 取版本最高的客户端 JAR，找不到抛 `PcuException`）。

`IosPlatformAdapter` 内嵌两个函数式接口：`NotificationSink.notify(String,String)`、
`StoreOpener.open(String)`。`MobilePlatformAdapter.Bridge` 是平台侧桥接契约，方法为
`stopService` / `startService` / `installDir` / `tempDir` / `requestStoragePermission` / `notify` /
`hasEnoughSpace` / `installPackage(Path)`；非移动平台传入会抛 `PcuException`。

### 系统安装器能力：`PackageInstallerAdapter`

`interface PackageInstallerAdapter extends PlatformAdapter`，多一个：
`void installPackage(Path artifact)`。`UpdateApplier` 靠 `instanceof` 识别这个能力。
这一类平台（Android APK / HarmonyOS HAP）不替换安装目录文件，也没有本地回滚备份。

---

## 十一、上报：`UpdateReporter`

`POST /v1/update/report`（`REPORT_PATH`）。

| 构造 | 说明 |
|---|---|
| `UpdateReporter(PcuConfig config)` | 内部造客户端 |
| `UpdateReporter(PcuConfig config, HttpClient http)` | 注入客户端 |

| 方法 | 返回 | 说明 |
|---|---|---|
| `report(UpdateStatus status, String fromVersion, String toVersion, String errorMessage)` | `void` | 失败抛异常；重试耗尽后抛 `PcuException` |
| `reportQuietly(UpdateStatus status, String fromVersion, String toVersion, String errorMessage)` | `void` | 失败只记日志，不影响主流程 |

请求体是 JSON：`pteid`、`platform`、`from_version`、`to_version`、`status`，
`error_message` 非空时才带（截断到 500 字符）。主流程请用 `reportQuietly`——上报本身失败不该让
已经成功的更新变成失败。

---

## 十二、数据模型与枚举

### `UpdateManifest`（record）

字段：`hasUpdate`、`platform`、`latestVersion`、`downloadUrl`、`checksum`、`size`、
`forceUpdate`、`changelog`、`delta`（`DeltaInfo`）、`minAppVersion`、`signature`。

- `static UpdateManifest fromJson(Object json)`：解析响应；`has_update=true` 时缺 `download_url` /
  `latest_version` / `checksum` 任一，抛 `PcuException`。
- `Map<String,Object> toMap()`：序列化，只在服务端与测试里用到。
- delta 两种形态都能解析：优先嵌套 `delta` 对象，缺失回落到扁平 `delta_url`/`delta_checksum`/`delta_size`。

### `UpdateChannel`（enum）

`STABLE("stable")`、`BETA("beta")`、`ALPHA("alpha")`、`DEV("dev")`。
`wire()` 取线上名；`static fromWire(String)` 未知或空回落 `STABLE`。

### `UpdatePlatform`（enum）

`WINDOWS`、`ANDROID`、`IOS`、`HARMONY`、`LINUX`、`MACOS`，`wire()` 是小写名。
`fromWire` 认别名 `harmonyos`/`ohos`，未知抛 `PcuException`；`current()` 按运行环境推断
（先认 JVM 里的 Dalvik/Android，再按 `os.name`）。

### `UpdateStatus`（enum）

`SUCCESS("success")`、`FAILED("failed")`、`ROLLED_BACK("rolled_back")`、`SKIPPED("skipped")`。

### `UpdateResult`（record）

字段：`outcome`、`fromVersion`、`toVersion`、`deltaApplied`、`forceRequired`、`artifact`、`message`。
`Outcome` 枚举：`NO_UPDATE`、`SUCCESS`、`FAILED`、`ROLLED_BACK`、`SKIPPED`。
`status()` 映射到上报状态；`succeeded()`；`NO_UPDATE` 不上报。
失败（`FAILED`）与回滚（`ROLLED_BACK`）刻意分开——前者「什么都没变」，后者「新版本上来过又被打回去了」，
服务端要触发的动作不一样。

---

## 十三、工具与回调

- `SemVer`：`parse` / `tryParse`；`major()`/`minor()`/`patch()`/`preRelease()`；`compareTo`。
  只解析 `major.minor.patch`（允许 `v` 前缀、缺省段、`-pre`、`+build`）。预发布版排在正式版之前。
- `Sha256`：`digest`、`hex`、`hexOfFile`、`normalize`（去 `sha256:` 前缀并转小写）、
  `matches(expected, actualHex)`（大小写不敏感、允许算法前缀）。
- `HealthProbe`（函数式接口）：`boolean healthy()`。静态 `assumeHealthy()` 直接返回 true 并告警——
  没配探针时「启动失败自动回滚」只覆盖「启动命令直接失败」，要覆盖「起来了但不可用」必须提供真实探针。
- `ProgressListener`（函数式接口）：`void onProgress(long downloaded, long total)`；常量 `NOOP`；
  默认方法 `percent(downloaded, total)`（total ≤ 0 返回 -1）。
- `PcuException`（`RuntimeException`）：配置错误、网络失败、校验不通过、差分/替换失败统一走这里。
  用非受检是为了让失败都能落到「回滚 + 上报」这条路径上，不必每层写 try。

## 内部辅助（非公开 API）

以下类是包级可见（`final class`，非 public），只能在 `com.potatotv.pcu` 内部或测试里用到，
不建议宿主依赖：`HttpClients`、`Retry`、`AtomicReplace`、`PcuJson`、`DesktopNotifier`、`PlatformSupport`。
其中 `DesktopNotifier`、`PlatformSupport` 在 `platform` 子包，同样是包级可见。