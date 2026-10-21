FROM python:3.11-slim-bookworm

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

COPY services/valhalla-service/requirements.txt /app/
RUN pip install --no-cache-dir -r requirements.txt

COPY services/valhalla-service/app /app/app

EXPOSE 8002

ENV PYTHONPATH=/app/app

HEALTHCHECK --interval=5s --timeout=3s --retries=5 \
  CMD curl -f http://localhost:8002/status || exit 1

CMD ["uvicorn", "main:app", "--app-dir", "/app/app", "--host", "0.0.0.0", "--port", "8002"]
