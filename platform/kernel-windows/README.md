# PACC 内核驱动（Windows CDP）

底层检测层（Windows），基于 WDM + 对象回调 + 内存取证，已实现为完整驱动逻辑源码。
真机部署需在 WDK10 + EV/WHQL 签名环境下用 `msbuild` 编译与签名。

## 能力
- **进程对象回调**：`ObRegisterCallbacks` 拦截对受保护进程的高危句柄访问
  （`PROCESS_VM_WRITE/VIRTUAL_MEMORY_OPERATION/CREATE_THREAD/SUSPEND` 等），
  非授权请求记入拦截计数并可裁剪权限，防注入/Hack。
- **内存特征扫描**：附加目标进程分块读取，通配符 `??` 逐字节比对，返回命中地址。
- **设备白名单**：校验 USB/HID 设备实例 ID 是否在注册表白名单内（宏键盘识别）。
- **IOCTL 协议**：受保护 PID 登记、内存扫描、拦截统计查询，供 ptv-client 驱动接口对接。

## 目录结构
```
kernel-windows/
  pacc_common.h     -> 公共头（IOCTL / 结构 / 全局）
  pacc_driver.c     -> 驱动主入口（DriverEntry / IRP 分发 / 保护集合）
  pacc_obhook.c     -> 对象回调（NtOpenProcess/DuplicateHandle 拦截）
  pacc_scanner.c    -> 内存特征扫描（分块 + ?? 通配）
  pacc_device.c     -> 设备白名单校验（注册表持久化）
  sources           -> WDK 构建清单
```

## 构建（需 Windows Driver Kit 10）
```bat
:: 在 "x64" 开发者命令提示符下
msbuild /t:build /p:Configuration=Release /p:TargetVersion=Win10
```
产出 `PaccPtv.sys`，经 EV 签名 + WHQL 后随玩家端安装包发布。

## 说明
本源码为反作弊取证用途：仅当玩家显式安装反作弊系统且同意其条款时部署。
拦截策略默认"记录优先、按需拦截"，避免误伤正常系统与开发工具。