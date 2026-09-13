"""PACC v5.0 AI 行为画像引擎。

在轻量评分引擎之上新增 v4.1 专章能力（纯 NumPy，零外部 ML 依赖，生产可替换为
XGBoost / LSTM / Transformer 真实模型）：

- 行为分类：对 128 维行为特征向量做加权逻辑回归（LR）评分，替代 XGBoost 分类器
- 时序异常：滑动窗口重建误差（近似 LSTM-AE），对时间序列检测隐身后缀/突变
- 人类行为模拟度：鼠标轨迹曲率/抖动熵/停驻分布 与人类基线对照，识别微自瞄
- 跨账号关联：行为特征向量间的余弦相似度矩阵，检测同源作弊器
- 自适应对抗学习：增量样本更新权重（梯度下降一次迭代）

全部模型参数持久化到 model.json（与 engine 共用同目录）。
"""
from __future__ import annotations

import json
import math
import os
import time
from pathlib import Path

import numpy as np

MODEL_PATH = Path(os.environ.get("PACC_AI_MODEL_PATH", "/data/model.json"))

# 人类行为基线（鼠标轨迹统计，用于微自瞄/自动点击检测）
HUMAN_BASELINE = {
    "trajectory_curvature": 0.55,   # 人类轨迹平均曲率
    "jitter_entropy": 3.2,          # 微抖动信息熵
    "pause_ratio": 0.35,            # 停驻时间占比
    "click_interval_cv": 0.28,      # 点击间隔变异系数
}


class BehaviorProfiler:
    def __init__(self, path: Path = MODEL_PATH):
        self.path = path
        # 行为分类权重（128 维特征权重映射：feature_xxx -> 权重）
        self.lr_weights: dict[str, float] = {}
        self.lr_bias: float = 0.0
        self.feature_keys: list[str] = []
        self.temporal_mean: list[float] = []
        self.temporal_std: list[float] = []
        self.sample_profiles: list[dict] = []   # 跨账号关联样本 {pteid, features}
        self.load()

    # ---------- 持久化 ----------
    def load(self) -> None:
        if not self.path.exists():
            return
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            self.lr_weights = {k: float(v) for k, v in data.get("lr_weights", {}).items()}
            self.lr_bias = float(data.get("lr_bias", 0.0))
            self.feature_keys = list(data.get("feature_keys", []))
            self.temporal_mean = [float(x) for x in data.get("temporal_mean", [])]
            self.temporal_std = [float(x) for x in data.get("temporal_std", [])]
            self.sample_profiles = data.get("sample_profiles", []) or []
        except (ValueError, OSError, TypeError):
            pass

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        data = {
            "lr_weights": self.lr_weights,
            "lr_bias": self.lr_bias,
            "feature_keys": self.feature_keys,
            "temporal_mean": self.temporal_mean,
            "temporal_std": self.temporal_std,
            "sample_profiles": self.sample_profiles[-200:],  # 仅保留最近样本
            "updated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        self.path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")

    # ---------- 行为分类（XGBoost 替代：LR + 特征加权） ----------
    def classify(self, features: dict) -> dict:
        """features: {feature_xxx: 数值, ...}（128 维行为特征）-> 作弊概率/等级"""
        vec = _vector(features)
        if not vec:
            return {"cheat_prob": 0.0, "level": "normal", "n_features": 0}
        # 加权逻辑回归
        z = self.lr_bias
        for k, v in vec.items():
            w = self.lr_weights.get(k, 0.0)
            if w == 0.0:
                # 未训练特征：按语义前缀赋予先验权重（1.0 / 前缀计数）
                w = _prior_weight(k)
            z += w * v
        prob = 1.0 / (1.0 + math.exp(-z))
        prob = _clamp(prob, 0.0, 1.0)
        return {
            "cheat_prob": round(prob, 4),
            "level": "cheat" if prob >= 0.7 else ("suspicious" if prob >= 0.5 else "normal"),
            "n_features": len(vec),
            "model": "pacc-behavior-lr-v1",
        }

    # ---------- 时序异常（LSTM-AE 替代：滑动窗口重建误差） ----------
    def temporal_anomaly(self, sequence: list[dict]) -> dict:
        """sequence: [{feature_xxx: 数值}, ...] 按时间排序的特征帧列表
        -> 每帧 z-score 误差，最大误差即异常分。"""
        if not sequence:
            return {"anomaly_score": 0.0, "level": "normal", "frames": 0}
        # 取第一帧特征键为通道
        keys = list(_vector(sequence[0]).keys())
        if not keys:
            return {"anomaly_score": 0.0, "level": "normal", "frames": 0}
        # 汇总为通道时间序列（均值）
        series = {k: [] for k in keys}
        for frame in sequence:
            v = _vector(frame)
            for k in keys:
                series[k].append(v.get(k, 0.0))
        # 归一化 + 重建误差（前向差分近似：|x_t - x_{t-1}|）
        errors: list[float] = []
        for k in keys:
            arr = np.asarray(series[k], dtype=float)
            if arr.size < 2:
                continue
            if np.std(arr) > 1e-9:
                arr = (arr - np.mean(arr)) / np.std(arr)
            err = np.abs(np.diff(arr))
            errors.extend(float(x) for x in err)
        if not errors:
            return {"anomaly_score": 0.0, "level": "normal", "frames": len(sequence)}
        score = float(np.mean(errors)) * 100.0
        return {
            "anomaly_score": round(_clamp(score, 0, 100), 2),
            "level": _level(score),
            "frames": len(sequence),
            "channels": len(keys),
            "model": "pacc-temporal-recon-v1",
        }

    # ---------- 人类行为模拟度 ----------
    def human_likeness(self, trajectory: dict) -> dict:
        """trajectory: {curvature, jitter_entropy, pause_ratio, click_interval_cv, ...}
        -> 与人类基线的相似度 0-1，过低提示微自瞄/自动点击。"""
        fields = ["trajectory_curvature", "jitter_entropy", "pause_ratio", "click_interval_cv"]
        diffs = []
        for f in fields:
            v = float(trajectory.get(f, HUMAN_BASELINE[f]))
            b = HUMAN_BASELINE[f]
            diffs.append(abs(v - b) / max(b, 1e-6))
        deviation = float(np.mean(diffs))
        likeness = _clamp(1.0 - deviation, 0.0, 1.0)
        return {
            "human_likeness": round(likeness, 4),
            "verdict": "human" if likeness >= 0.6 else ("suspicious" if likeness >= 0.4 else "bot"),
            "deviation": round(deviation, 4),
            "model": "pacc-human-model-v1",
        }

    # ---------- 跨账号关联 ----------
    def correlate(self, pteid: str, features: dict, threshold: float = 0.9) -> dict:
        """与历史样本做余弦相似度，识别同源作弊器（同特征签名）。"""
        vec = _vector(features)
        if not vec:
            return {"matches": [], "max_similarity": 0.0}
        # 当前样本入库（供后续关联）
        self.sample_profiles.append({"pteid": pteid, "features": vec, "ts": time.time()})
        self.save()
        best = []
        for other in self.sample_profiles:
            if other["pteid"] == pteid:
                continue
            sim = _cosine(vec, other.get("features", {}))
            if sim >= threshold:
                best.append({"pteid": other["pteid"], "similarity": round(sim, 4)})
        best.sort(key=lambda x: -x["similarity"])
        return {
            "pteid": pteid,
            "matches": best[:20],
            "max_similarity": round(best[0]["similarity"], 4) if best else 0.0,
            "model": "pacc-correlate-cosine-v1",
        }

    # ---------- 自适应对抗学习 ----------
    def adapt(self, samples: list[dict]) -> dict:
        """samples: [{pteid, features, label(0/1)}]，梯度下降更新 LR 权重（对抗学习）。"""
        updated = 0
        for s in samples:
            label = 1.0 if float(s.get("label", 0)) >= 0.5 else 0.0
            vec = _vector(s.get("features", {}))
            if not vec:
                continue
            self.feature_keys = list(dict.fromkeys([*self.feature_keys, *vec.keys()]))
            for k in vec.keys():
                self.lr_weights.setdefault(k, _prior_weight(k))
            # 单步梯度下降（sigmoid 交叉熵）
            z = self.lr_bias
            for k, v in vec.items():
                z += self.lr_weights[k] * v
            prob = 1.0 / (1.0 + math.exp(-z))
            grad = prob - label
            lr = 0.05
            for k, v in vec.items():
                self.lr_weights[k] -= lr * grad * v
            self.lr_bias -= lr * grad
            updated += 1
        self.save()
        return {"trained": updated, "feature_keys": len(self.feature_keys), "model": "pacc-behavior-lr-v1"}


def _vector(features: dict) -> dict[str, float]:
    """提取 feature_ 前缀的数值特征。"""
    out: dict[str, float] = {}
    for k, v in (features or {}).items():
        if not isinstance(k, str) or not k.startswith("feature_"):
            continue
        try:
            out[k] = float(v)
        except (TypeError, ValueError):
            continue
    return out


def _prior_weight(key: str) -> float:
    """按特征语义前缀的先验权重：战斗类高权重，环境类中等。"""
    if "killaura" in key or "aimbot" in key or "click" in key or "reach" in key:
        return 0.35
    if "fly" in key or "speed" in key or "velocity" in key or "scaffold" in key:
        return 0.3
    if "dma" in key or "driver" in key or "inject" in key or "ghost" in key:
        return 0.5
    return 0.15


def _cosine(a: dict, b: dict) -> float:
    keys = set(a) & set(b)
    if not keys:
        return 0.0
    dot = sum(a[k] * b[k] for k in keys)
    na = math.sqrt(sum(v * v for v in a.values()))
    nb = math.sqrt(sum(v * v for v in b.values()))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


def _clamp(v: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, v))


def _level(score: float) -> str:
    if score >= 85:
        return "critical"
    if score >= 60:
        return "high"
    if score >= 30:
        return "suspicious"
    return "normal"


profiler = BehaviorProfiler()