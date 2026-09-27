# syntax=docker/dockerfile:1

FROM python:3.12-slim

WORKDIR /app

# opencv-python-headless usually needs no system OpenGL libs
COPY ml-service/requirements.txt /app/requirements.txt

RUN pip install --no-cache-dir --default-timeout=300 --retries 10 \
      torch torchvision --index-url https://download.pytorch.org/whl/cpu \
 && pip install --no-cache-dir --default-timeout=300 --retries 10 \
      --trusted-host pypi.org --trusted-host files.pythonhosted.org \
      -r /app/requirements.txt

COPY ml-service/ /app/
COPY ml/models/checkpoint.pt /models/checkpoint.pt

ENV FORGERY_CHECKPOINT=/models/checkpoint.pt
ENV NSFW_BACKEND=heuristic
ENV PYTHONUNBUFFERED=1

EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
