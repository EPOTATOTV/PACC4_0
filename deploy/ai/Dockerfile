# PACC AI 推理服务镜像
FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PACC_AI_MODEL_PATH=/data/model.json

WORKDIR /srv

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt -i https://mirrors.huaweicloud.com/repository/pypi/simple

COPY app ./app

# 模型持久化目录
RUN mkdir -p /data
VOLUME ["/data"]

EXPOSE 8000

HEALTHCHECK --interval=15s --timeout=5s --retries=10 \
  CMD python -c "import urllib.request;urllib.request.urlopen('http://localhost:8000/health')" || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
