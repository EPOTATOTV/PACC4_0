//! A21 性能基准：跨平台检测核心的「特征采集 + 判定」路径吞吐。
//!
//! 为什么不用 criterion：本仓库约定零第三方依赖（见 `deploy/pipe-rust`），
//! 引入基准框架会破坏离线可构建。这里用 `harness = false` + `std::time::Instant`
//! 自己计时，够用且不引依赖。
//!
//! 测量三段，逐段收窄，便于定位耗时到底花在哪：
//! * `feature`  —— 仅 `Collector::collect()`（178 维增量折算，纯计算）
//! * `pipeline` —— 采集 + 行为分析回写 + 规则引擎判定（= `PaccCore::snapshot` 的计算部分）
//! * `snapshot` —— 完整 `PaccCore::snapshot()`，含平台只读采样与上报体 JSON 组装
//!
//! 运行：
//! ```text
//! cargo bench -p pacc-core                 # 默认 5000 次
//! PACC_BENCH_ITERS=50000 cargo bench -p pacc-core
//! ```
//!
//! 注意 `snapshot` 一段是否含真实系统 I/O 取决于当前平台的 `Platform` 实现：
//! Linux 上是真实 `/proc` 读取，其余平台是如实返回 `Unsupported` 的桩，几乎无 I/O。
//! 因此**跨机器对比时只能比 `feature` / `pipeline` 两段**。

use pacc_core::collector::{
    ActionKind, ActionSample, AttackSample, BlockSample, Collector, PositionSample,
};
use pacc_core::engine::{AnalysisContext, Engine};
use pacc_core::platform::{
    AntiDebugReport, Capabilities, InputSample, IntegrityManifest, IntegrityReport, MemoryHit,
    Platform, PlatformError, ProcessInfo,
};
use pacc_core::PaccCore;
use std::hint::black_box;
use std::time::{Duration, Instant};

/// 默认迭代次数。够到稳定区间，又不至于让 `cargo clippy --all-targets` 之外
/// 的偶发运行（比如 `cargo test --all-targets`）等太久。
const DEFAULT_ITERS: u32 = 5_000;
/// 预热轮数（把 CPU 频率拉到稳态、把分支预测器喂热）。
const WARMUP: u32 = 300;
/// 缓冲区规模：与 `pacc-collector` 的 `BUFFER_CAP = 512` 对齐，
/// 让每轮都按「滑窗已满」的最坏情况测量。
const SAMPLES: u64 = 512;

fn iterations() -> u32 {
    std::env::var("PACC_BENCH_ITERS")
        .ok()
        .and_then(|v| v.parse::<u32>().ok())
        .filter(|n| *n > 0)
        .unwrap_or(DEFAULT_ITERS)
}

/// 测试用平台桩：全部能力如实声明为不可用，不做任何真实系统调用。
///
/// 基准要量的是计算路径；用桩把平台 I/O 摘出去，跨机器/跨平台才有可比性。
struct NullPlatform;

impl Platform for NullPlatform {
    fn name(&self) -> &'static str {
        "bench-null"
    }

    fn capabilities(&self) -> Capabilities {
        Capabilities::default()
    }

    fn enumerate_processes(&self) -> Result<Vec<ProcessInfo>, PlatformError> {
        Err(PlatformError::Unsupported("bench"))
    }

    fn scan_memory(&self, _pid: u32, _pattern: &[u8]) -> Result<Vec<MemoryHit>, PlatformError> {
        Err(PlatformError::Unsupported("bench"))
    }

    fn sample_input_events(&self, _window_ms: u64) -> Result<Vec<InputSample>, PlatformError> {
        Err(PlatformError::Unsupported("bench"))
    }

    fn anti_debug_probe(&self) -> Result<AntiDebugReport, PlatformError> {
        Err(PlatformError::Unsupported("bench"))
    }

    fn verify_install_integrity(
        &self,
        _manifest: &IntegrityManifest,
    ) -> Result<IntegrityReport, PlatformError> {
        Err(PlatformError::Unsupported("bench"))
    }
}

/// 可复现的伪随机数（xorshift64*），避免引 `rand`。
struct Rng(u64);

impl Rng {
    fn new(seed: u64) -> Self {
        Self(seed | 1)
    }

    fn next_u64(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.0 = x;
        x.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    /// `[0, 1)` 之间的浮点。
    fn unit(&mut self) -> f64 {
        (self.next_u64() >> 11) as f64 / (1u64 << 53) as f64
    }

    /// `[lo, hi)` 之间的浮点。
    fn range(&mut self, lo: f64, hi: f64) -> f64 {
        lo + self.unit() * (hi - lo)
    }
}

/// 构造一个「滑窗已满、含真人噪声」的采集器。
///
/// 刻意混入接近阈值的高 CPS 连击与大幅瞄准抖动，让规则引擎真的会走到
/// 阈值比较与命中分支——全零输入会让分支预测过于乐观，测出来的数字偏快。
fn loaded_collector() -> Collector {
    let mut c = Collector::new();
    load_collector(&mut c);
    c
}

/// 把同一份确定性工作负载填进任意采集器（`Collector` 不实现 `Clone`）。
fn load_collector(c: &mut Collector) {
    let mut rng = Rng::new(0x9E37_79B9_7F4A_7C15);
    let mut ts = 1_000u64;

    for _ in 0..SAMPLES {
        // 20% 的概率打出 25ms 连击（接近 BURST_INTERVAL_MS=30 的爆发区）。
        let gap = if rng.unit() < 0.2 {
            24 + (rng.next_u64() % 4)
        } else {
            90 + (rng.next_u64() % 120)
        };
        ts += gap;
        c.record_click(ts);
        c.record_swing(ts + 5);
    }

    for i in 0..SAMPLES {
        let t = 1_000 + i * 50;
        // 距离在合法区与越界区之间来回，覆盖 REACH_HARD=4.0 两侧。
        let distance = if i % 7 == 0 {
            rng.range(4.2, 6.0)
        } else {
            rng.range(2.0, 3.9)
        };
        c.record_attack(AttackSample {
            ts_millis: t,
            distance,
            pitch: rng.range(-40.0, 40.0),
            yaw: (i as f64 * 7.3) % 360.0,
            target_id: (i % 5) as i32,
            critical: i % 3 == 0,
            airborne: i % 2 == 0,
            blocked: i % 11 == 0,
            hit: i % 4 != 0,
        });
    }

    let mut x = 0.0;
    let mut y = 64.0;
    let mut vy = 0.0;
    for i in 0..SAMPLES {
        let t = 1_000 + i * 50;
        let on_ground = i % 9 != 0;
        if on_ground {
            vy = 0.0;
        } else {
            vy += 0.08;
        }
        x += rng.range(0.0, 0.16);
        y += vy;
        c.record_position(PositionSample {
            ts_millis: t,
            x,
            y,
            z: (i as f64 * 0.03).sin(),
            vx: rng.range(2.0, 5.6),
            vy,
            vz: rng.range(-0.4, 0.4),
            on_ground,
            sprinting: i % 3 == 0,
            fall_damage_taken: false,
        });
        c.record_block(BlockSample {
            ts_millis: t,
            place: i % 2 == 0,
            sneaking: i % 4 == 0,
            face_angle: rng.range(0.0, 180.0),
            radius: rng.range(0.0, 6.0),
        });
    }

    for i in 0..SAMPLES {
        let t = 1_000 + i * 50;
        let kind = match i % 4 {
            0 => ActionKind::Eat,
            1 => ActionKind::Chest,
            2 => ActionKind::Inventory,
            _ => ActionKind::Armor,
        };
        c.record_action(ActionSample {
            ts_millis: t,
            kind,
            duration_millis: rng.range(80.0, 900.0),
            amount: rng.range(0.0, 8.0),
        });
    }

    // 鼠标轨迹：带轻微手抖的弧线，走到曲率/熵/拟合误差的计算分支。
    for i in 0..SAMPLES {
        let t = i as f64 / SAMPLES as f64;
        c.record_mouse(
            400.0 + 260.0 * (t * std::f64::consts::PI).sin() + rng.range(-1.2, 1.2),
            300.0 + 180.0 * (t * 2.0).cos() + rng.range(-1.2, 1.2),
        );
    }

    c.set_aim_target(Some((640.0, 480.0)));
}

/// 计时一段闭包：先预热，再在若干个分片里取「最快分片」，降低被系统抢占污染的概率。
fn measure(label: &str, iters: u32, mut body: impl FnMut()) -> Duration {
    for _ in 0..WARMUP {
        body();
    }

    // 分 5 片跑，取最快的一片：单次 get 到调度抖动时用最优值比用均值更稳。
    const SLICES: u32 = 5;
    let per_slice = (iters / SLICES).max(1);
    let mut best = Duration::MAX;
    for _ in 0..SLICES {
        let start = Instant::now();
        for _ in 0..per_slice {
            body();
        }
        let elapsed = start.elapsed();
        if elapsed < best {
            best = elapsed;
        }
    }

    let per_op = best.as_nanos() as f64 / per_slice as f64;
    println!(
        "  {label:<34} {:>9.3} us/op   {:>12.0} ops/s   ({} ops/slice, {} slices)",
        per_op / 1_000.0,
        1e9 / per_op.max(1e-9),
        per_slice,
        SLICES
    );
    best
}

fn main() {
    let iters = iterations();
    println!("PACC 检测核心基准 (A21)");
    println!("  目标架构   : {}", std::env::consts::ARCH);
    println!("  操作系统   : {}", std::env::consts::OS);
    println!(
        "  profile    : {}",
        if cfg!(debug_assertions) {
            "debug（数字偏慢，请用 `cargo bench`）"
        } else {
            "release"
        }
    );
    println!("  滑窗规模   : {SAMPLES} 采样/类，预热 {WARMUP} 轮，迭代 {iters} 轮");
    println!();

    // ---- 一段：纯特征采集 ----
    let mut collector = loaded_collector();
    let fv = collector.collect();
    assert_eq!(fv.as_slice().len(), 178, "特征向量必须恒为 178 维");
    println!("[1/3] 特征采集（178 维增量折算）");
    measure("Collector::collect()", iters, || {
        let fv = collector.collect();
        black_box(fv.as_slice()[0]);
    });

    // ---- 二段：采集 + 行为回写 + 规则判定（PaccCore::snapshot 的计算部分）----
    let mut collector = loaded_collector();
    let mut behavior = pacc_core::behavior::BehaviorAnalyzer::new();
    for i in 0..SAMPLES {
        behavior.record_click(1_000 + i * 50);
        behavior.record_attack(1_000 + i * 50, (i % 5) as i32);
        behavior.record_aim_speed(90.0 + (i % 40) as f64 * 3.0);
    }
    let engine = Engine::new();
    let ctx = AnalysisContext::default();

    println!();
    println!("[2/3] 判定管线（采集 + 行为分析 + 规则引擎）");
    measure("collect + apply + evaluate", iters, || {
        let mut fv = collector.collect();
        behavior.apply(&mut fv);
        let verdict = engine.evaluate(&fv, &ctx);
        black_box(verdict.risk);
    });

    // ---- 三段：完整 snapshot（含平台采样与上报体 JSON）----
    println!();
    println!("[3/3] 完整快照（含平台只读采样 + 上报体 JSON）");
    let mut core = PaccCore::with_platform(Box::new(NullPlatform));
    load_collector(core.collector_mut());
    measure("PaccCore::snapshot() [计算] ", iters, || {
        let snap = core.snapshot();
        black_box(snap.verdict.risk);
    });

    // 真实平台：Linux 上会走 /proc，其余平台是如实 Unsupported 的桩。
    let mut core = PaccCore::new();
    println!(
        "  平台实现   : {} （{}）",
        core.platform().name(),
        if cfg!(target_os = "linux") {
            "含真实 /proc 读取"
        } else {
            "桩实现，无真实系统 I/O"
        }
    );
    measure("PaccCore::snapshot() [本机] ", iters.min(2_000), || {
        let snap = core.snapshot();
        black_box(snap.verdict.risk);
    });

    println!();
    println!("说明：跨机器/跨平台对比请只比 [1]/[2] 两段——[3] 是否含系统 I/O 取决于平台实现。");
}
