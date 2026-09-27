# PCU 使用示例

下面都是能对上真实签名的片段。生产代码里优先用 `UpdateOrchestrator` 走完整链路；
直接调 `UpdateDownloader`、`UpdateVerifier` 这些是为了讲清楚每一步，不是说可以绕开编排。

## 1. 一次完整更新流程

最省事的写法：配好 `PcuConfig`，交给编排层。

```java
Path installDir = Path.of(System.getenv("PACC_HOME"));
Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));

PcuConfig config = PcuConfig.builder()
        .baseUrl("https://api.potatotv.asia")
        .currentVersion("5.4.0")
        .platform(UpdatePlatform.WINDOWS)
        .pteid("PT0000000001")
        .channel(UpdateChannel.STABLE)
        .adapter(new WindowsPlatformAdapter(installDir, tempDir))
        .signaturePublicKey(UpdateVerifier.decodePublicKey(pubKeyBase64))
        .mainArtifact(installDir.resolve("ptv-client-5.4.0.jar"))
        .healthProbe(() -> probeLocalControlPort(installDir))   // 宿主提供真实探针
        .build();

UpdateOrchestrator orchestrator = new UpdateOrchestrator(config);
UpdateResult result = orchestrator.runOnce(new ProgressListener() {
    @Override
    public void onProgress(long downloaded, long total) {
        double pct = percent(downloaded, total);
        System.out.printf("下载 %d/%d（%.1f%%）%n", downloaded, total, pct);
    }
});

switch (result.outcome()) {
    case SUCCESS -> System.out.println(result.deltaApplied() ? "差分更新完成" : "全量更新完成");
    case NO_UPDATE -> System.out.println("已是最新版本");
    case ROLLED_BACK -> System.out.println("新版本健康检查没过，已回滚：" + result.message());
    case FAILED, SKIPPED -> System.out.println("未更新：" + result.message());
}
```

上报（`/v1/update/report`）由 `runOnce` 内部完成，不用自己调。

要把每一步拆开看，等价于：

```java
UpdateChecker checker = new UpdateChecker(config);
UpdateManifest manifest = checker.check();          // 1. 检查
if (!manifest.hasUpdate()) {
    return;
}

UpdateVerifier verifier = new UpdateVerifier(config);
verifier.verifyManifest(manifest);                  // 平台匹配 + 防降级

UpdateDownloader downloader = new UpdateDownloader(config);
Path artifact = downloader.download(               // 2. 下载（断点续传在内）
        manifest.downloadUrl(),
        tempDir.resolve("pacc-update.bin"),
        manifest.checksum(),
        manifest.size(),
        ProgressListener.NOOP);
verifier.verifyArtifact(artifact, manifest);        // 3. 校验（长度 + SHA-256 + 签名）

BackupManager backups = new BackupManager(config);
UpdateApplier applier = new UpdateApplier(config, backups);
UpdateApplier.ApplyOutcome outcome = applier.apply(artifact, manifest.latestVersion()); // 4. 应用

if (!outcome.delegatedToInstaller() && !awaitHealthy(config)) {           // 5. 验证启动
    new UpdateRollbacker(config, backups).rollback(outcome.backup());
    new UpdateReporter(config).reportQuietly(
            UpdateStatus.ROLLED_BACK, "5.4.0", manifest.latestVersion(), "健康检查未通过");
} else {
    backups.prune();
    new UpdateReporter(config).reportQuietly(
            UpdateStatus.SUCCESS, "5.4.0", manifest.latestVersion(), null);
}
```

`progressListener` 里 `percent(downloaded, total)` 是接口的默认方法，total ≤ 0 时返回 -1。

## 2. 断点续传

`download` 用 `<target>.part` 作中间文件，只有「长度 + SHA-256 都对上」才 rename 成目标文件。
上一轮已经下满、或下到一半断开，下一轮都会接着来，不会从零重下。

```java
UpdateDownloader downloader = new UpdateDownloader(config);
Path target = tempDir.resolve("pacc-update.bin");

System.out.println("中间文件在：" + UpdateDownloader.partFileFor(target)); // .../pacc-update.bin.part

try {
    downloader.download(manifest.downloadUrl(), target, manifest.checksum(), manifest.size());
} catch (PcuException e) {
    // 网络中断：.part 还在，下次调用同一对 url/target 会带 Range 续传
    System.out.println("下载未完成，保留断点：" + e.getMessage());
}
```

`download` 内部在需要重试时也会复用已有 `.part`。若服务端不支持 Range（回了 200），
会放弃续传从头写并打一条 warning。目标长度已知而 `.part` 比它还长，说明是别的包，会清掉重下。

## 3. 差分更新

差分只对单文件制品生效（`mainArtifact` 非 null），且补丁的 `from_version` 必须正好等于当前版本。

```java
UpdateDownloader downloader = new UpdateDownloader(config);
DeltaApplier deltaApplier = new DeltaApplier(config, downloader);   // 构造签名 (PcuConfig, UpdateDownloader)

Path output = tempDir.resolve("pacc-update.bin");
if (deltaApplier.usable(manifest)) {
    Path newArtifact = deltaApplier.produce(manifest, output, ProgressListener.NOOP);
    // 产物仍要过全量校验，SHA-256 才是最终判据
    new UpdateVerifier(config).verifyArtifact(newArtifact, manifest);
} else {
    // 回退全量
    downloader.download(manifest.downloadUrl(), output, manifest.checksum(), manifest.size());
}
```

`produce` 内部已经先自查了一道 SHA-256，不符会抛 `PcuException`（「补丁或基线版本不正确」）。
`usable` 返回 false 的常见原因：`allowDelta` 关着、清单没有 `delta`、制品不是单文件、
`from_version` 解析不出来或与当前版本对不上。

服务端生成补丁用 `BsDiff.diff`，客户端应用用 `BsPatch.patch`：

```java
byte[] oldJar = Files.readAllBytes(installDir.resolve("ptv-client-5.4.0.jar"));
byte[] newJar = Files.readAllBytes(buildOutput.resolve("ptv-client-5.5.0.jar"));

byte[] patch = BsDiff.diff(oldJar, newJar);                 // 服务端
Path patchFile = tempDir.resolve("5.4.0-to-5.5.0.pcu-patch");
Files.write(patchFile, patch);

byte[] restored = BsPatch.patch(oldJar, Files.readAllBytes(patchFile));  // 客户端
if (!Arrays.equals(restored, newJar)) {
    throw new IllegalStateException("补丁应用结果与目标文件不符");
}
```

## 4. 失败回滚

回滚由「应用阶段兜底」和「健康检查失败」两条路触发。

应用阶段内部兜底：替换过程中任何一步失败，`UpdateApplier.apply` 会用刚建的备份写回并重启旧版本，
再抛 `PcuException`，所以抛出来的时候旧版本还在跑。

健康检查失败：`UpdateOrchestrator` 在窗口内一次都没探到健康即调用 `UpdateRollbacker`。手动复现：

```java
// 一个永远不健康的探针，用来触发回滚路径
PcuConfig config = PcuConfig.builder()
        .baseUrl(server.baseUrl())
        .currentVersion("5.4.0")
        .platform(UpdatePlatform.WINDOWS)
        .adapter(stubAdapter)
        .mainArtifact(jar)
        .healthProbe(() -> false)
        .healthCheckTimeout(Duration.ofMillis(300))
        .healthCheckInterval(Duration.ofMillis(50))
        .build();

UpdateResult result = new UpdateOrchestrator(config).runOnce();
// result.outcome() == UpdateResult.Outcome.ROLLED_BACK
```

也可以在进程重启后继续回滚，靠的是备份盘上的清单：

```java
BackupManager backups = new BackupManager(config);
if (backups.hasBackup("5.4.0")) {
    BackupManager.BackupHandle handle = backups.open("5.4.0");
    new UpdateRollbacker(config, backups).rollback(handle);
}
```

`rollback` 会先停下新版本、从备份恢复（恢复前逐个核对清单里的 SHA-256），再启动旧版本。
handle 为空会抛 `PcuException`。

## 5. 按通道取清单

通道由端侧配置决定，服务端按 `platform × channel` 查已发布版本。取测试版就把 channel 设成 `BETA`：

```java
PcuConfig betaConfig = PcuConfig.builder()
        .baseUrl("https://api.potatotv.asia")
        .currentVersion("5.4.0")
        .channel(UpdateChannel.BETA)
        .platform(UpdatePlatform.ANDROID)
        .adapter(new MobilePlatformAdapter(UpdatePlatform.ANDROID, androidBridge))
        .pteid("PT0000000001")
        .build();

UpdateChecker checker = new UpdateChecker(betaConfig);
System.out.println("检查更新的 URL：" + checker.buildUri());
// 形如 https://api.potatotv.asia/v1/update/check?platform=android&current_version=5.4.0&channel=beta&pteid=...

UpdateManifest manifest = checker.check();
```

未识别的通道名会回落到 `stable`（`UpdateChannel.fromWire`），不会因为服务端多一个通道名就把链路打断。

## 6. 自定义 `PlatformAdapter`

各平台差异全收敛在 `PlatformAdapter` 的 8 个动作里。宿主自己实现一个（比如嵌入式场景）：

```java
public final class MyAdapter implements PlatformAdapter {

    private final Path installDir;
    private final Path tempDir;

    public MyAdapter(Path installDir, Path tempDir) {
        this.installDir = installDir;
        this.tempDir = tempDir;
    }

    @Override public void stopPacc() { /* 停本地服务 */ }
    @Override public void startPacc() { /* 起本地服务 */ }
    @Override public Path getInstallDir() { return installDir; }
    @Override public Path getTempDir() { return tempDir; }
    @Override public CompletableFuture<Boolean> requestStoragePermission() {
        return CompletableFuture.completedFuture(true);   // 桌面/嵌入式没有运行期存储权限
    }
    @Override public void showUpdateNotification(String title, String message) { /* 通知宿主 UI */ }
    @Override public boolean hasEnoughSpace(long requiredBytes) {
        // 判断的是临时目录所在分区：下载与解包都发生在那里
        return tempDir.toFile().getUsableSpace() >= requiredBytes;
    }
}
```

只要制品必须交给系统安装器，就改成实现 `PackageInstallerAdapter`，多写一个
`installPackage(Path artifact)`；`UpdateApplier` 会靠 `instanceof` 识别并把安装动作转交出去，
不做文件替换与本地回滚备份。

需要自定义停/起命令时，继承 `ProcessPlatformAdapter` 更省事：

```java
public final class CustomProcessAdapter extends ProcessPlatformAdapter {
    public CustomProcessAdapter(Path installDir, Path tempDir) {
        super(installDir, tempDir, new Commands(
                List.of("myctl", "stop"),
                List.of("myctl", "start"),
                Duration.ofSeconds(30),
                null));   // 常驻进程：拉起即返回、不等退出
    }
}
```

## 7. 用 `FakeUpdateServer` 写集成测试

`FakeUpdateServer`（`src/test/java/com/potatotv/pcu/FakeUpdateServer.java`）是包级可见的测试脚手架，
只实现 `/v1/update/check`、`/v1/update/report` 和制品下载三条路径，够跑通端侧整条链路。
测试类要和它同包（`com.potatotv.pcu`）才能拿到。

```java
package com.potatotv.pcu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MyUpdateTest {

    @Test
    void 走完整流程并上报success(@TempDir Path root) throws IOException {
        Path install = Files.createDirectories(root.resolve("install"));
        Path temp = Files.createDirectories(root.resolve("temp"));
        Path jar = install.resolve("ptv-client-5.4.0.jar");
        Files.writeString(jar, "旧客户端", StandardCharsets.UTF_8);

        try (FakeUpdateServer server = new FakeUpdateServer()) {
            server.artifact = "新客户端".getBytes(StandardCharsets.UTF_8);

            StubAdapter adapter = new StubAdapter(install, temp);
            PcuConfig config = PcuConfig.builder()
                    .baseUrl(server.baseUrl())
                    .currentVersion("5.4.0")
                    .platform(UpdatePlatform.WINDOWS)
                    .pteid("PT0000000001")
                    .adapter(adapter)
                    .backupRoot(root.resolve("backup"))
                    .mainArtifact(jar)
                    .maxRetries(0)
                    .healthCheckTimeout(Duration.ofMillis(300))
                    .healthCheckInterval(Duration.ofMillis(50))
                    .build();

            UpdateResult result = new UpdateOrchestrator(config).runOnce();

            assertEquals(UpdateResult.Outcome.SUCCESS, result.outcome());
            assertEquals("新客户端", Files.readString(jar, StandardCharsets.UTF_8));
            assertEquals("success", server.reports.get(0).get("status"));
        }
    }
}
```

`FakeUpdateServer` 上可调的字段：`artifact`、`latestVersion`、`hasUpdate`、`forceUpdate`、
`minAppVersion`，以及用来构造失败场景的 `checksumOverride`、`sizeOverride`；
记录侧有 `reports`（收到的上报体）和 `checkCount`。`StubAdapter` 是同样包级可见的适配层桩，
只记录停/起调用，可置 `failStart`、`failStop`、`enoughSpace` 触发对应分支。

想覆盖「差分路径」，把 `mainArtifact` 指向旧制品、在清单里带 `delta` 字段即可；
`FakeUpdateServer` 目前只下发全量清单，差分清单需要在测试里自行拼 JSON 或扩展脚手架。