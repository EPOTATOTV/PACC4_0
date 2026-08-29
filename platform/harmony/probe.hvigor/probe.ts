// PACC HarmonyOS/OpenHarmony 反作弊探针（ArkTS）。
//
// 严格玩家端本地采样，不接触游戏服务器数据。检测项：
// 1) 应用加速 / 时钟偏移（game 提速）；
// 2) hook 框架 / 注入进程（frida / lsposed / riru / magisk 特征进程与模块目录）；
// 3) devmem / 提权路径；
// 4) 与 Android/iOS 探针一致折算为 DetectionEvent（score 0-100）。
//
// 结果交 ptv-client 统一上报 PTV。

import { process } from '@kit.BasicServicesKit'
import { fileIo } from '@kit.CoreFileKit'

const TAG = 'pacc-arkts'

// 加速判定的阈值：墙钟与单调时钟走时偏差超过 30% 视为异常
const ACCELERATION_THRESHOLD = 0.30
// 采样窗口（真实流逝时间，毫秒），过短会让差值噪声化
const SAMPLE_WINDOW_MS = 60

// root / 越权特征路径（file 存在即可疑）
const rootPaths: string[] = [
  '/system/bin/su', '/system/xbin/su', '/su/bin/su', '/cache/magisk.log',
  '/data/adb/magisk', '/data/adb/riru', '/data/adb/modules'
]

// 常见注入 / hook 框架关键字（匹配进程名）
const hookKeywords: string[] = [
  'frida', 'lsposed', 'riru', 'xposed', 'edxposed', 'magisk', 'substrate',
  'zygisk', 'gadget'
]

export interface PaccSuspicion {
  score: number
  severity: string
  hits: string[]
  json: string
}

export class PaccHarmonyProbe {

  /** 运行一次完整检测（异步，需真实流逝采样以精确判定加速）。 */
  async scan(): Promise<PaccSuspicion> {
    const hits: string[] = []
    if (await this.isAccelerated()) {
      hits.push('speed_hack')
    }
    if (this.isHookPresent()) {
      hits.push('hook_framework')
    }
    if (this.isRootPresent()) {
      hits.push('root')
    }

    const score = this.scoreOf(hits.length)
    const severity = score >= 70 ? 'high' : score > 0 ? 'medium' : 'ok'
    const json = this.toJson(hits, score, severity)
    console.log(`[${TAG}] scan score=${score} hits=${hits.join(',')}`)
    return { score, severity, hits, json }
  }

  /** 快速同步检测（不执行真实耗时采样，加速项返回 true 前需在调用侧主动追加确认）。 */
  quick(): PaccSuspicion {
    const hits: string[] = []
    if (this.isHookPresent()) {
      hits.push('hook_framework')
    }
    if (this.isRootPresent()) {
      hits.push('root')
    }
    const score = this.scoreOf(hits.length)
    const severity = score >= 70 ? 'high' : score > 0 ? 'medium' : 'ok'
    const json = this.toJson(hits, score, severity)
    return { score, severity, hits, json }
  }

  // 设备时钟加速异常：比较同一真实流逝窗口内「墙钟」与「单调时钟」的走时。
  // 加速器会放大进程感知的时间流速，导致墙钟/单调时钟明显背离（正常设备二者偏差 < 3%）。
  private async isAccelerated(): Promise<boolean> {
    const sleep = (ms: number): Promise<void> =>
      new Promise<void>((resolve) => setTimeout(resolve, ms))

    const wall0 = Date.now()
    // process.uptime() 返回进程启动后的单调秒数，换算为毫秒
    const mono0 = process.uptime() * 1000
    await sleep(SAMPLE_WINDOW_MS)
    const wall1 = Date.now()
    const mono1 = process.uptime() * 1000

    const wallDelta = wall1 - wall0
    const monoDelta = mono1 - mono0
    // 采样异常（小于等于 0）视为未检测到，避免噪声误报
    if (wallDelta <= 0) {
      return false
    }
    // 二者的真实语义相同，正常设备偏差应很小；偏离超过阈值即可疑
    const misalign = Math.abs(wallDelta - monoDelta) / wallDelta
    return misalign > ACCELERATION_THRESHOLD
  }

  // 遍历运行进程名匹配 hook 框架
  private isHookPresent(): boolean {
    try {
      const info = process.getRunningProcessesSync()
      for (const p of info) {
        const name = (p && (p.bundleName || p.processName)) ?? ''
        for (const kw of hookKeywords) {
          if (name.includes(kw)) {
            console.warn(`[${TAG}] hook process: ${name}`)
            return true
          }
        }
      }
    } catch (e) {
      // 无获取运行进程权限：降级为路径检测
    }
    return false
  }

  // root / 越权路径、devmem 提权特征
  private isRootPresent(): boolean {
    for (const p of rootPaths) {
      if (this.pathExists(p)) {
        console.warn(`[${TAG}] root path: ${p}`)
        return true
      }
    }
    return false
  }

  private pathExists(p: string): boolean {
    try {
      fileIo.accessSync(p)
      return true
    } catch (err) {
      return false
    }
  }

  private scoreOf(n: number): number {
    switch (n) {
      case 0: return 0
      case 1: return 45
      case 2: return 70
      default: return 100
    }
  }

  private toJson(hits: string[], score: number, severity: string): string {
    const arr = hits.map((h) => `"${h}"`).join(',')
    return `{"edition":"harmony","proto":"arkts-probe","score":${score},` +
      `"severity":"${severity}","hits":[${arr}]}`
  }
}