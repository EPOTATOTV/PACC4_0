"""PACC AI 推理引擎。

轻量在线学习评分引擎（纯 Python + NumPy）：
- 规则加权：按事件维度（底层/行为/环境/历史）加权合成风险分
- 统计异常：维护历史分数的滚动均值/标准差（z-score），偏离即抬升
- 增量训练：POST /api/inference/train 更新模型参数并持久化到 model.json

生产可无缝替换为 XGBoost / LSTM-AE（本模块仅保留接口与轻量实现）。
"""
from __future__ import annotations

import json
import math
import os
import time
from pathlib import Path

import numpy as np

# 模型持久化路径（容器内挂载可持久化）
MODEL_PATH = Path(os.environ.get("PACC_AI_MODEL_PATH", "/data/model.json"))

# 事件类型 -> 维度权重（与技术设计文档一致：底层35% 行为25% 历史20% AI15% 环境5%）
DIM_WEIGHTS = {
    "low_level": 0.95,   # 底层检测（内存/进程/设备）权重最高
    "behavior": 0.80,    # 行为检测（移动/战斗/交互）
    "history": 0.65,     # 历史劣迹加成
    "env": 0.60,         # 环境风险
}

SEVERITY_BASE = {"info": 20.0, "warning": 50.0, "critical": 85.0}


class AiEngine:
    def __init__(self, path: Path = MODEL_PATH):
        self.path = path
        self.mean: float = 50.0      # 历史风险分均值
        self.std: float = 15.0       # 历史风险分标准差
        self.n: int = 0              # 样本数
        self.weights: dict[str, float] = dict(DIM_WEIGHTS)
        self.anomaly_z: float = 2.0  # z-score 异常阈值
        self.load()

    # ---------- 持久化 ----------
    def load(self) -> None:
        if not self.path.exists():
            return
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            self.mean = float(data.get("mean", 50.0))
            self.std = float(data.get("std", 15.0))
            self.n = int(data.get("n", 0))
            self.weights.update({k: float(v) for k, v in data.get("weights", {}).items()})
            self.anomaly_z = float(data.get("anomaly_z", 2.0))
        except (ValueError, OSError, TypeError) as e:  # pragma: no cover
            print(f"[ai] 模型加载失败，使用默认参数: {e}")

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        data = {
            "mean": self.mean, "std": self.std, "n": self.n,
            "weights": self.weights, "anomaly_z": self.anomaly_z,
            "updated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        self.path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")

    # ---------- 推理 ----------
    def score(self, features: dict) -> dict:
        """features: {event_type, severity, client_risk, history_factor} -> {risk_score, detail}"""
        event_type = str(features.get("event_type", "behavior")).lower()
        severity = str(features.get("severity", "info")).lower()
        client_risk = _clamp(float(features.get("client_risk", 0)), 0, 100)
        history_factor = _clamp(float(features.get("history_factor", 0)), 0, 1)

        dim = _dimension(event_type)
        w = self.weights.get(dim, 0.8)
        base = SEVERITY_BASE.get(severity, 50.0) * 0.4 + client_risk * 0.6
        score = base * w + history_factor * 12.0

        # 统计异常：偏离历史均值越远，风险越高（z-score）
        if self.std > 1e-6:
            z = (score - self.mean) / self.std
            if z > 0:
                score += min(20.0, (z - self.anomaly_z) * 5.0) if z > self.anomaly_z else 0.0

        score = _clamp(score, 0, 100)
        return {
            "risk_score": round(score, 2),
            "level": _level(score),
            "dimension": dim,
            "z_score": round(z, 3) if self.std > 1e-6 else 0.0,
            "model": "pacc-ai-online-v1",
        }

    # ---------- 增量训练 ----------
    def train(self, samples: list[dict]) -> dict:
        """samples: [{event_type, severity, client_risk, history_factor, label}]，
        更新统计参数（历史分均值/标准差）。"""
        scores = []
        for s in samples:
            label = float(s.get("label", 0))          # 0 正常 / 1 作弊
            client_risk = _clamp(float(s.get("client_risk", 0)), 0, 100)
            severity = str(s.get("severity", "info")).lower()
            w = self.weights.get(_dimension(str(s.get("event_type", ""))), 0.8)
            s_raw = SEVERITY_BASE.get(severity, 50.0) * 0.4 + client_risk * 0.6
            s_raw = s_raw * w + float(s.get("history_factor", 0)) * 12.0
            # 作弊样本（label=1）校准：贴近高分段
            if label >= 1:
                s_raw = s_raw * 0.5 + 70.0
            scores.append(_clamp(s_raw, 0, 100))

        arr = np.asarray(scores, dtype=float)
        if arr.size == 0:
            return {"trained": 0}

        # 在线均值/方差（Welford 更新）
        for v in arr:
            self.n += 1
            delta = float(v) - self.mean
            self.mean += delta / self.n
            delta2 = float(v) - self.mean
            self.std = math.sqrt(max(0.0, (self.std ** 2 * (self.n - 1) + delta * delta2) / self.n))
        self.std = max(self.std, 5.0)  # 防止退化
        self.save()
        return {"trained": int(arr.size), "mean": round(self.mean, 2), "std": round(self.std, 2), "n": self.n}


def _dimension(event_type: str) -> str:
    t = event_type.lower()
    if t in ("memory_tamper", "process_injection", "usb_device", "signature_hit"):
        return "low_level"
    if t in ("debugger", "java_mod", "injection"):
        return "env"
    return "behavior"


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


engine = AiEngine()
