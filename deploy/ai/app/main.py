"""PACC v5.0 AI 推理服务入口（FastAPI）。

端到端：
  GET  /health                       健康检查（容器探针）
  GET  /api/model/info               当前模型参数
  POST /api/inference/score          单条特征评分  -> {risk_score, level, ...}
  POST /api/inference/batch          批量评分
  POST /api/inference/train          增量训练（更新均值/标准差并持久化）
  # ---- v4.1 行为画像专章 ----
  POST /api/inference/behavior       128 维行为分类（XGBoost 替代：加权 LR）
  POST /api/inference/temporal       时序异常检测（LSTM-AE 替代：重建误差）
  POST /api/inference/humanize       人类行为模拟度评估（微自瞄检测）
  POST /api/inference/correlate      跨账号行为关联（同源作弊器聚类）
  POST /api/inference/adapt          自适应对抗学习（在线更新权重）
"""
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from .engine import engine
from .profiler import profiler

app = FastAPI(title="PACC AI 推理服务", version="5.4.0")


class Feature(BaseModel):
    event_type: str = "behavior"
    severity: str = "info"
    client_risk: float = 0.0
    history_factor: float = 0.0


class TrainSample(Feature):
    label: float = 0.0  # 0 正常 / 1 作弊（可由查端结论回流生成标签）


class BatchRequest(BaseModel):
    features: list[Feature]


class TrainRequest(BaseModel):
    samples: list[TrainSample]


# ---- v4.1 模型 ----

class BehaviorFeatures(BaseModel):
    features: dict[str, float] = {}          # 128 维行为特征 feature_xxx


class TemporalRequest(BaseModel):
    sequence: list[dict[str, float]] = []    # 按时间排序的特征帧


class TrajectoryRequest(BaseModel):
    trajectory: dict[str, float] = {}        # 轨迹统计 {curvature, jitter_entropy, ...}


class CorrelateRequest(BaseModel):
    pteid: str
    features: dict[str, float] = {}
    threshold: float = 0.9


class AdaptSample(BaseModel):
    pteid: str = ""
    features: dict[str, float] = {}
    label: float = 0.0


class AdaptRequest(BaseModel):
    samples: list[AdaptSample]


# ---- v4.0 基础端点 ----

@app.get("/health")
def health():
    return {"status": "UP", "service": "pacc-ai", "version": "5.4.0", "n": engine.n}


@app.get("/api/model/info")
def model_info():
    return {
        "mean": engine.mean,
        "std": engine.std,
        "n": engine.n,
        "weights": engine.weights,
        "profiler_feature_keys": len(profiler.feature_keys),
        "profiler_samples": len(profiler.sample_profiles),
    }


@app.post("/api/inference/score")
def score(f: Feature):
    try:
        return engine.score(f.model_dump())
    except Exception as e:  # noqa: BLE001
        raise HTTPException(status_code=400, detail=str(e)) from e


@app.post("/api/inference/batch")
def batch(req: BatchRequest):
    return {"results": [engine.score(f.model_dump()) for f in req.features]}


@app.post("/api/inference/train")
def train(req: TrainRequest):
    if not req.samples:
        raise HTTPException(status_code=400, detail="samples 不能为空")
    return engine.train([s.model_dump() for s in req.samples])


# ---- v4.1 行为画像端点 ----

@app.post("/api/inference/behavior")
def behavior(req: BehaviorFeatures):
    try:
        return profiler.classify(req.features)
    except Exception as e:  # noqa: BLE001
        raise HTTPException(status_code=400, detail=str(e)) from e


@app.post("/api/inference/temporal")
def temporal(req: TemporalRequest):
    if not req.sequence:
        raise HTTPException(status_code=400, detail="sequence 不能为空")
    return profiler.temporal_anomaly(req.sequence)


@app.post("/api/inference/humanize")
def humanize(req: TrajectoryRequest):
    return profiler.human_likeness(req.trajectory)


@app.post("/api/inference/correlate")
def correlate(req: CorrelateRequest):
    if not req.pteid:
        raise HTTPException(status_code=400, detail="pteid 不能为空")
    return profiler.correlate(req.pteid, req.features, req.threshold)


@app.post("/api/inference/adapt")
def adapt(req: AdaptRequest):
    if not req.samples:
        raise HTTPException(status_code=400, detail="samples 不能为空")
    return profiler.adapt([s.model_dump() for s in req.samples])