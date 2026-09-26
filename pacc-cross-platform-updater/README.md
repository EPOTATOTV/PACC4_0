# PACC Cross-Platform Updater (PCU)

PACC 玩家端的跨平台更新核心：检查更新、断点续传下载、校验、差分、备份替换、失败回滚。
Java 21，零第三方依赖——下载、校验、差分全部用 JDK 标准库实现，JUnit 只进 test 作用域。

## 边界

- 不做封禁，不接游戏服务器 API，更新数据只来自本机与发布站。
- 端侧只做「检查 → 下载 → 校验 → 暂存」。`apply` 与 `rollback` 由安装目录之外的管理器进程执行：
  Windows 上运行中的 JVM 锁着 JAR，自己换不掉自己；Linux 的 systemd 服务同理，自杀式更新收不了尾。
- 下载只走白名单主机，跟随重定向落地后会再核对一次主机名。

## 构建与测试

```bash
mvn -B verify
```

## 目录

```
src/main/java/com/potatotv/pcu/            核心：检查、下载、校验、差分、备份、回滚、编排
src/main/java/com/potatotv/pcu/platform/   各平台薄适配层
src/test/resources/vectors/                差分金标向量（入库的生成物）
tools/pcu-patchgen/                        向量的生成与校验（Python，只用标准库）
```

## 差分

bsdiff 见 `BsDiff.java` 与 `BsPatch.java`，bzip2 是纯 Java 实现（`BZip2.java`），
与 Python 标准库 `bz2` 互通。补丁头、补丁块与解压输出都带长度上限，
解压要多大内存不由补丁自己声明。

`src/test/resources/vectors/` 里是入库的金标向量。改了生成端逻辑却没重新生成，
或者手改了向量字节，都会在 CI 的校验步骤挂掉：

```bash
cd tools/pcu-patchgen
python -m pcu_patchgen --check   # 比对向量与生成器，不一致时退出码 1
python -m pcu_patchgen           # 重新生成
```

## 版本与仓库

PCU 的版本随 PACC 整体走，由主仓库的 `scripts/bump-version.sh` 统一替换，不单独升降。

PACC_PCU 仓库是从主仓库 `pacc-cross-platform-updater/` 目录用 `git subtree` 切出来的镜像：
改动提交到主仓库，再同步过来，别直接在 PACC_PCU 里改。