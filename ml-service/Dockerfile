# syntax=docker/dockerfile:1

FROM python:3.12-slim

WORKDIR /app

# opencv-python-headless usually needs no system OpenGL libs
COPY ml-service/requirements.txt /app/requirements.txt

# cpu (default) or a CUDA wheel index such as cu124 — see docker-compose.gpu.yml
ARG TORCH_INDEX=cpu
RUN pip install --no-cache-dir --default-timeout=300 --retries 10 \
      torch torchvision --index-url https://download.pytorch.org/whl/${TORCH_INDEX} \
 && pip install --no-cache-dir --default-timeout=300 --retries 10 \
      --trusted-host pypi.org --trusted-host files.pythonhosted.org \
      -r /app/requirements.txt

# Code and models stay root-owned (read-only to the service); the service runs unprivileged
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --home-dir /home/app --create-home app

COPY ml-service/ /app/
# calibration.json (`ml/calibration.py --promote`) and ensemble.json (`ml/phase29.py`) are optional; the bracket globs let COPY match nothing
COPY ml/models/checkpoint.pt ml/models/calibration.jso[n] ml/models/ensemble.jso[n] /models/

ENV FORGERY_CHECKPOINT=/models/checkpoint.pt
ENV NSFW_BACKEND=heuristic
ENV PYTHONUNBUFFERED=1
ENV LOG_FORMAT=json
ENV HOME=/home/app
ENV HF_HOME=/home/app/.cache/huggingface
ENV TORCH_HOME=/home/app/.cache/torch

USER 10001:10001
EXPOSE 8000
CMD ["sh", "/app/start.sh"]
