//! 统计工具（与 Java 端 `detection.analysis.Stats` 同口径）。

/// 算术均值；空序列返回 0。
pub fn mean(xs: &[f64]) -> f64 {
    if xs.is_empty() {
        return 0.0;
    }
    xs.iter().sum::<f64>() / xs.len() as f64
}

/// 总体方差（二阶中心矩，除以 n）。
pub fn variance(xs: &[f64]) -> f64 {
    if xs.len() < 2 {
        return 0.0;
    }
    let m = mean(xs);
    xs.iter().map(|x| (x - m) * (x - m)).sum::<f64>() / xs.len() as f64
}

/// 总体标准差。
pub fn std_dev(xs: &[f64]) -> f64 {
    variance(xs).sqrt()
}

/// 变异系数 std/mean；均值近 0 时返回 0。
pub fn cv(xs: &[f64]) -> f64 {
    let m = mean(xs);
    if m.abs() < 1e-9 {
        return 0.0;
    }
    std_dev(xs) / m.abs()
}

/// 偏度（三阶标准化中心矩）；样本不足或方差为 0 返回 0。
pub fn skewness(xs: &[f64]) -> f64 {
    if xs.len() < 3 {
        return 0.0;
    }
    let m = mean(xs);
    let s = std_dev(xs);
    if s < 1e-9 {
        return 0.0;
    }
    xs.iter().map(|x| ((x - m) / s).powi(3)).sum::<f64>() / xs.len() as f64
}

/// 峰度（四阶标准化中心矩减 3，超额峰度）。
pub fn kurtosis(xs: &[f64]) -> f64 {
    if xs.len() < 4 {
        return 0.0;
    }
    let m = mean(xs);
    let s = std_dev(xs);
    if s < 1e-9 {
        return 0.0;
    }
    xs.iter().map(|x| ((x - m) / s).powi(4)).sum::<f64>() / xs.len() as f64 - 3.0
}

/// 香农熵（bit）。概率之和按调用方保证为 1 附近，负数/零概率忽略。
pub fn shannon_entropy(p: &[f64]) -> f64 {
    let mut h = 0.0;
    for &pi in p {
        if pi > 1e-12 {
            h -= pi * pi.log2();
        }
    }
    h
}

/// 二阶差分均方根（加加速度近似）。
pub fn rms_second_difference(a: &[f64]) -> f64 {
    if a.len() < 3 {
        return 0.0;
    }
    let mut se = 0.0;
    let mut n = 0usize;
    for i in 2..a.len() {
        let d = a[i] - 2.0 * a[i - 1] + a[i - 2];
        se += d * d;
        n += 1;
    }
    if n == 0 {
        0.0
    } else {
        (se / n as f64).sqrt()
    }
}

/// 角度差（弧度），归一化到 (-π, π]。
pub fn angle_diff(a: f64, b: f64) -> f64 {
    let mut d = a - b;
    while d > std::f64::consts::PI {
        d -= 2.0 * std::f64::consts::PI;
    }
    while d < -std::f64::consts::PI {
        d += 2.0 * std::f64::consts::PI;
    }
    d
}

/// 离散曲率（三点法）。
pub fn discrete_curvature(ax: f64, az: f64, bx: f64, bz: f64, cx: f64, cz: f64) -> f64 {
    let v1x = bx - ax;
    let v1z = bz - az;
    let v2x = cx - bx;
    let v2z = cz - bz;
    let cross = (v1x * v2z - v1z * v2x).abs();
    let n1 = (v1x * v1x + v1z * v1z).sqrt();
    let n2 = (v2x * v2x + v2z * v2z).sqrt();
    let n3 = ((v1x + v2x).powi(2) + (v1z + v2z).powi(2)).sqrt();
    let denom = n1 * n2 * n3;
    if denom < 1e-9 {
        0.0
    } else {
        2.0 * cross / denom
    }
}

/// 二维余弦相似度。
pub fn cosine(x1: f64, y1: f64, x2: f64, y2: f64) -> f64 {
    let n1 = (x1 * x1 + y1 * y1).sqrt();
    let n2 = (x2 * x2 + y2 * y2).sqrt();
    if n1 < 1e-9 || n2 < 1e-9 {
        return 0.0;
    }
    (x1 * x2 + y1 * y2) / (n1 * n2)
}

/// 夹取到 [0,1]；NaN 归 0。
pub fn clamp01(x: f64) -> f64 {
    if x.is_nan() {
        return 0.0;
    }
    x.clamp(0.0, 1.0)
}

/// 最大值；空序列返回 0。
pub fn max_of(xs: &[f64]) -> f64 {
    xs.iter().copied().fold(0.0_f64, f64::max)
}
