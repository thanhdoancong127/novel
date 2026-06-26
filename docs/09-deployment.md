# Triển khai (Deployment) — MeTruyenChu

> **File:** 09-deployment.md
> **Part of:** metruyenchu rebuild spec series

---

## Mục lục

1. [Docker Compose (Development)](#1-docker-compose-development)
2. [Environment Variables](#2-environment-variables)
3. [Kubernetes Deployment (Production)](#3-kubernetes-deployment-production)
4. [CI/CD Pipeline (GitHub Actions)](#4-cicd-pipeline-github-actions)
5. [Monitoring & Alerting](#5-monitoring--alerting)
6. [Backup Strategy](#6-backup-strategy)
7. [Security](#7-security)

---

## 1. Docker Compose (Development)

### 1.1 File `deployment/docker-compose/docker-compose.yml`

```yaml
version: "3.9"

x-logging: &default-logging
  driver: "json-file"
  options:
    max-size: "10m"
    max-file: "3"

networks:
  metruyenchu-net:
    driver: bridge
    ipam:
      config:
        - subnet: 172.25.0.0/16

volumes:
  postgres-auth-data:
  postgres-story-data:
  postgres-social-data:
  postgres-audio-data:
  redis-data:
  rabbitmq-data:
  minio-data:
  localai-models:
  localai-images:
  prometheus-data:
  grafana-data:
  loki-data:
  tempo-data:

services:
  # ============================================================
  # PostgreSQL — Auth Service
  # ============================================================
  postgres-auth:
    image: postgres:16-alpine
    container_name: metruyenchu-postgres-auth
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "5432:5432"
    environment:
      POSTGRES_DB: auth_db
      POSTGRES_USER: mtc_auth
      POSTGRES_PASSWORD: ${AUTH_DB_PASSWORD:-mtc_auth_dev}
    volumes:
      - postgres-auth-data:/var/lib/postgresql/data
      - ./init/postgres-auth-init.sql:/docker-entrypoint-initdb.d/init.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U mtc_auth -d auth_db"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
    logging: *default-logging

  # ============================================================
  # PostgreSQL — Story Service
  # ============================================================
  postgres-story:
    image: postgres:16-alpine
    container_name: metruyenchu-postgres-story
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "5433:5432"
    environment:
      POSTGRES_DB: story_db
      POSTGRES_USER: mtc_story
      POSTGRES_PASSWORD: ${STORY_DB_PASSWORD:-mtc_story_dev}
    volumes:
      - postgres-story-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U mtc_story -d story_db"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
    logging: *default-logging

  # ============================================================
  # PostgreSQL — Social Service
  # ============================================================
  postgres-social:
    image: postgres:16-alpine
    container_name: metruyenchu-postgres-social
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "5434:5432"
    environment:
      POSTGRES_DB: social_db
      POSTGRES_USER: mtc_social
      POSTGRES_PASSWORD: ${SOCIAL_DB_PASSWORD:-mtc_social_dev}
    volumes:
      - postgres-social-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U mtc_social -d social_db"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
    logging: *default-logging

  # ============================================================
  # PostgreSQL — Audio Service
  # ============================================================
  postgres-audio:
    image: postgres:16-alpine
    container_name: metruyenchu-postgres-audio
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "5435:5432"
    environment:
      POSTGRES_DB: audio_db
      POSTGRES_USER: mtc_audio
      POSTGRES_PASSWORD: ${AUDIO_DB_PASSWORD:-mtc_audio_dev}
    volumes:
      - postgres-audio-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U mtc_audio -d audio_db"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
    logging: *default-logging

  # ============================================================
  # Redis — Cache & Rate Limiting & Session Store
  # ============================================================
  redis:
    image: redis:7-alpine
    container_name: metruyenchu-redis
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    command: redis-server --appendonly yes --maxmemory 512mb --maxmemory-policy allkeys-lru
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 3s
      retries: 5
    logging: *default-logging

  # ============================================================
  # RabbitMQ — Event Bus
  # ============================================================
  rabbitmq:
    image: rabbitmq:4-management-alpine
    container_name: metruyenchu-rabbitmq
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "5672:5672"   # AMQP
      - "15672:15672"  # Management UI
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_USER:-mtc_rabbit}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD:-mtc_rabbit_dev}
      RABBITMQ_DEFAULT_VHOST: /
    volumes:
      - rabbitmq-data:/var/lib/rabbitmq
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "check_port_connectivity"]
      interval: 15s
      timeout: 5s
      retries: 5
      start_period: 30s
    logging: *default-logging

  # ============================================================
  # MinIO — Audio File Storage
  # ============================================================
  minio:
    image: minio/minio:latest
    container_name: metruyenchu-minio
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "9000:9000"   # API
      - "9001:9001"   # Console
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER:-mtc_minio}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:-mtc_minio_dev}
      MINIO_DOMAIN: minio
    volumes:
      - minio-data:/data
    command: server /data --console-address ":9001"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 15s
      timeout: 5s
      retries: 5
      start_period: 30s
    logging: *default-logging

  # ============================================================
  # MinIO Bucket Initialization
  # ============================================================
  minio-init:
    image: minio/mc:latest
    container_name: metruyenchu-minio-init
    depends_on:
      minio:
        condition: service_healthy
    networks:
      - metruyenchu-net
    entrypoint: >
      /bin/sh -c "
      mc alias set local http://minio:9000 ${MINIO_ROOT_USER:-mtc_minio} ${MINIO_ROOT_PASSWORD:-mtc_minio_dev};
      mc mb local/audio-files --ignore-existing;
      mc mb local/audio-cache --ignore-existing;
      mc anonymous set download local/audio-files;
      exit 0;
      "
    logging: *default-logging

  # ============================================================
  # LocalAI — TTS Engine (OmniVoice)
  # ============================================================
  local-ai:
    image: quay.io/go-skynet/local-ai:latest-cpu
    container_name: metruyenchu-localai
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "8080:8080"
    environment:
      - THREADS=${LOCALAI_THREADS:-4}
      - DEBUG=${LOCALAI_DEBUG:-false}
      - MODELS_PATH=/models
      - IMAGE_PATH=/images
      - CONTEXT_SIZE=2048
    volumes:
      - localai-models:/models
      - localai-images:/images
      - ./localai/models.yaml:/models/models.yaml:ro
    devices:
      - /dev/dri:/dev/dri  # GPU acceleration (Intel/NVIDIA)
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/healthz"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 120s
    logging: *default-logging

  # ============================================================
  # LocalAI Model Downloader
  # ============================================================
  local-ai-init:
    image: alpine/curl:latest
    container_name: metruyenchu-localai-init
    depends_on:
      local-ai:
        condition: service_healthy
    networks:
      - metruyenchu-net
    entrypoint: >
      /bin/sh -c "
      echo 'Waiting for LocalAI to accept model install...' &&
      sleep 10 &&
      curl -X POST http://local-ai:8080/models/apply \
        -H 'Content-Type: application/json' \
        -d '{\"url\": \"github:go-skynet/model-gallery/omni-voice.yaml\"}' &&
      echo 'OmniVoice model installation triggered.' &&
      exit 0
      "
    logging: *default-logging

  # ============================================================
  # API Gateway
  # ============================================================
  gateway-service:
    build:
      context: ../../services/gateway-service
      dockerfile: Dockerfile
      args:
        JAR_FILE: build/libs/gateway-service-*.jar
    container_name: metruyenchu-gateway
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "8080:8080"
    env_file: .env
    environment:
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-dev}
      JWT_SECRET: ${JWT_SECRET}
      JWT_EXPIRATION_MS: ${JWT_EXPIRATION_MS:-900000}
      JWT_REFRESH_EXPIRATION_MS: ${JWT_REFRESH_EXPIRATION_MS:-604800000}
      REDIS_HOST: redis
      REDIS_PORT: 6379
      AUTH_SERVICE_URL: http://auth-service:8081
      STORY_SERVICE_URL: http://story-service:8082
      SOCIAL_SERVICE_URL: http://social-service:8083
      AUDIO_SERVICE_URL: http://audio-service:8084
      NOTIFICATION_SERVICE_URL: http://notification-service:8085
      ANALYTICS_SERVICE_URL: http://analytics-service:8086
      CORS_ALLOWED_ORIGINS: ${CORS_ALLOWED_ORIGINS:-http://localhost:3000}
    depends_on:
      redis:
        condition: service_healthy
      auth-service:
        condition: service_started
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 60s
    logging: *default-logging

  # ============================================================
  # Auth Service
  # ============================================================
  auth-service:
    build:
      context: ../../services/auth-service
      dockerfile: Dockerfile
    container_name: metruyenchu-auth
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "8081:8081"
    env_file: .env
    environment:
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-dev}
      SERVER_PORT: 8081
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres-auth:5432/auth_db
      SPRING_DATASOURCE_USERNAME: mtc_auth
      SPRING_DATASOURCE_PASSWORD: ${AUTH_DB_PASSWORD:-mtc_auth_dev}
      SPRING_REDIS_HOST: redis
      SPRING_REDIS_PORT: 6379
      SPRING_RABBITMQ_HOST: rabbitmq
      SPRING_RABBITMQ_PORT: 5672
      SPRING_RABBITMQ_USERNAME: ${RABBITMQ_USER:-mtc_rabbit}
      SPRING_RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD:-mtc_rabbit_dev}
      JWT_SECRET: ${JWT_SECRET}
      JWT_EXPIRATION_MS: ${JWT_EXPIRATION_MS:-900000}
      JWT_REFRESH_EXPIRATION_MS: ${JWT_REFRESH_EXPIRATION_MS:-604800000}
      OAUTH2_GOOGLE_CLIENT_ID: ${OAUTH2_GOOGLE_CLIENT_ID}
      OAUTH2_GOOGLE_CLIENT_SECRET: ${OAUTH2_GOOGLE_CLIENT_SECRET}
      OAUTH2_FACEBOOK_CLIENT_ID: ${OAUTH2_FACEBOOK_CLIENT_ID}
      OAUTH2_FACEBOOK_CLIENT_SECRET: ${OAUTH2_FACEBOOK_CLIENT_SECRET}
      OAUTH2_ZALO_CLIENT_ID: ${OAUTH2_ZALO_CLIENT_ID}
      OAUTH2_ZALO_CLIENT_SECRET: ${OAUTH2_ZALO_CLIENT_SECRET}
      MAIL_HOST: ${MAIL_HOST:-smtp.gmail.com}
      MAIL_PORT: ${MAIL_PORT:-587}
      MAIL_USERNAME: ${MAIL_USERNAME}
      MAIL_PASSWORD: ${MAIL_PASSWORD}
      MAIL_FROM: ${MAIL_FROM:-noreply@metruyenchu.com}
      VAPID_PUBLIC_KEY: ${VAPID_PUBLIC_KEY}
      VAPID_PRIVATE_KEY: ${VAPID_PRIVATE_KEY}
    depends_on:
      postgres-auth:
        condition: service_healthy
      redis:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8081/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 60s
    logging: *default-logging

  # ============================================================
  # Story Service
  # ============================================================
  story-service:
    build:
      context: ../../services/story-service
      dockerfile: Dockerfile
    container_name: metruyenchu-story
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "8082:8082"
    env_file: .env
    environment:
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-dev}
      SERVER_PORT: 8082
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres-story:5432/story_db
      SPRING_DATASOURCE_USERNAME: mtc_story
      SPRING_DATASOURCE_PASSWORD: ${STORY_DB_PASSWORD:-mtc_story_dev}
      SPRING_REDIS_HOST: redis
      SPRING_REDIS_PORT: 6379
      SPRING_RABBITMQ_HOST: rabbitmq
      SPRING_RABBITMQ_PORT: 5672
      SPRING_RABBITMQ_USERNAME: ${RABBITMQ_USER:-mtc_rabbit}
      SPRING_RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD:-mtc_rabbit_dev}
      AUTH_SERVICE_URL: http://auth-service:8081
      SOCIAL_SERVICE_URL: http://social-service:8083
      AUDIO_SERVICE_URL: http://audio-service:8084
    depends_on:
      postgres-story:
        condition: service_healthy
      redis:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8082/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 60s
    logging: *default-logging

  # ============================================================
  # Social Service
  # ============================================================
  social-service:
    build:
      context: ../../services/social-service
      dockerfile: Dockerfile
    container_name: metruyenchu-social
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "8083:8083"
    env_file: .env
    environment:
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-dev}
      SERVER_PORT: 8083
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres-social:5432/social_db
      SPRING_DATASOURCE_USERNAME: mtc_social
      SPRING_DATASOURCE_PASSWORD: ${SOCIAL_DB_PASSWORD:-mtc_social_dev}
      SPRING_REDIS_HOST: redis
      SPRING_REDIS_PORT: 6379
      SPRING_RABBITMQ_HOST: rabbitmq
      SPRING_RABBITMQ_PORT: 5672
      SPRING_RABBITMQ_USERNAME: ${RABBITMQ_USER:-mtc_rabbit}
      SPRING_RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD:-mtc_rabbit_dev}
      AUTH_SERVICE_URL: http://auth-service:8081
      STORY_SERVICE_URL: http://story-service:8082
    depends_on:
      postgres-social:
        condition: service_healthy
      redis:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8083/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 60s
    logging: *default-logging

  # ============================================================
  # Audio Service
  # ============================================================
  audio-service:
    build:
      context: ../../services/audio-service
      dockerfile: Dockerfile
    container_name: metruyenchu-audio
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "8084:8084"
    env_file: .env
    environment:
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-dev}
      SERVER_PORT: 8084
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres-audio:5432/audio_db
      SPRING_DATASOURCE_USERNAME: mtc_audio
      SPRING_DATASOURCE_PASSWORD: ${AUDIO_DB_PASSWORD:-mtc_audio_dev}
      SPRING_REDIS_HOST: redis
      SPRING_REDIS_PORT: 6379
      SPRING_RABBITMQ_HOST: rabbitmq
      SPRING_RABBITMQ_PORT: 5672
      SPRING_RABBITMQ_USERNAME: ${RABBITMQ_USER:-mtc_rabbit}
      SPRING_RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD:-mtc_rabbit_dev}
      MINIO_ENDPOINT: http://minio:9000
      MINIO_ACCESS_KEY: ${MINIO_ROOT_USER:-mtc_minio}
      MINIO_SECRET_KEY: ${MINIO_ROOT_PASSWORD:-mtc_minio_dev}
      MINIO_AUDIO_BUCKET: audio-files
      MINIO_CACHE_BUCKET: audio-cache
      LOCALAI_URL: http://local-ai:8080
    depends_on:
      postgres-audio:
        condition: service_healthy
      redis:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
      minio:
        condition: service_healthy
      local-ai:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8084/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 60s
    logging: *default-logging

  # ============================================================
  # Notification Service
  # ============================================================
  notification-service:
    build:
      context: ../../services/notification-service
      dockerfile: Dockerfile
    container_name: metruyenchu-notification
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "8085:8085"
    env_file: .env
    environment:
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-dev}
      SERVER_PORT: 8085
      SPRING_REDIS_HOST: redis
      SPRING_REDIS_PORT: 6379
      SPRING_RABBITMQ_HOST: rabbitmq
      SPRING_RABBITMQ_PORT: 5672
      SPRING_RABBITMQ_USERNAME: ${RABBITMQ_USER:-mtc_rabbit}
      SPRING_RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD:-mtc_rabbit_dev}
      MAIL_HOST: ${MAIL_HOST:-smtp.gmail.com}
      MAIL_PORT: ${MAIL_PORT:-587}
      MAIL_USERNAME: ${MAIL_USERNAME}
      MAIL_PASSWORD: ${MAIL_PASSWORD}
      MAIL_FROM: ${MAIL_FROM:-noreply@metruyenchu.com}
      VAPID_PUBLIC_KEY: ${VAPID_PUBLIC_KEY}
      VAPID_PRIVATE_KEY: ${VAPID_PRIVATE_KEY}
    depends_on:
      redis:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8085/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 60s
    logging: *default-logging

  # ============================================================
  # Analytics Service
  # ============================================================
  analytics-service:
    build:
      context: ../../services/analytics-service
      dockerfile: Dockerfile
    container_name: metruyenchu-analytics
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "8086:8086"
    env_file: .env
    environment:
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-dev}
      SERVER_PORT: 8086
      SPRING_REDIS_HOST: redis
      SPRING_REDIS_PORT: 6379
      SPRING_RABBITMQ_HOST: rabbitmq
      SPRING_RABBITMQ_PORT: 5672
      SPRING_RABBITMQ_USERNAME: ${RABBITMQ_USER:-mtc_rabbit}
      SPRING_RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD:-mtc_rabbit_dev}
    depends_on:
      redis:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8086/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 60s
    logging: *default-logging

  # ============================================================
  # Frontend (Next.js)
  # ============================================================
  frontend:
    build:
      context: ../../frontend
      dockerfile: Dockerfile
      args:
        NEXT_PUBLIC_API_URL: ${NEXT_PUBLIC_API_URL:-http://localhost:8080}
        NEXT_PUBLIC_WS_URL: ${NEXT_PUBLIC_WS_URL:-ws://localhost:8080}
    container_name: metruyenchu-frontend
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "3000:3000"
    env_file: .env
    environment:
      NODE_ENV: ${NODE_ENV:-development}
      NEXT_PUBLIC_API_URL: ${NEXT_PUBLIC_API_URL:-http://localhost:8080}
      NEXT_PUBLIC_WS_URL: ${NEXT_PUBLIC_WS_URL:-ws://localhost:8080}
      NEXT_PUBLIC_VAPID_PUBLIC_KEY: ${VAPID_PUBLIC_KEY}
    depends_on:
      gateway-service:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:3000/api/health"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 30s
    logging: *default-logging

  # ============================================================
  # Nginx Reverse Proxy (Optional — for local HTTPS testing)
  # ============================================================
  nginx:
    image: nginx:1.25-alpine
    container_name: metruyenchu-nginx
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./nginx/sites:/etc/nginx/sites:ro
      - ./nginx/ssl:/etc/nginx/ssl:ro
    depends_on:
      frontend:
        condition: service_healthy
    logging: *default-logging

  # ============================================================
  # Prometheus
  # ============================================================
  prometheus:
    image: prom/prometheus:v2.52.0
    container_name: metruyenchu-prometheus
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "9090:9090"
    volumes:
      - prometheus-data:/prometheus
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./prometheus/rules:/etc/prometheus/rules:ro
    command:
      - "--config.file=/etc/prometheus/prometheus.yml"
      - "--storage.tsdb.path=/prometheus"
      - "--storage.tsdb.retention.time=30d"
      - "--web.console.libraries=/usr/share/prometheus/console_libraries"
      - "--web.console.templates=/usr/share/prometheus/consoles"
      - "--web.enable-lifecycle"
    logging: *default-logging

  # ============================================================
  # Grafana
  # ============================================================
  grafana:
    image: grafana/grafana:11.0.0
    container_name: metruyenchu-grafana
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "3001:3000"
    environment:
      GF_SECURITY_ADMIN_USER: ${GRAFANA_USER:-admin}
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_PASSWORD:-mtc_grafana_dev}
      GF_INSTALL_PLUGINS: grafana-piechart-panel
    volumes:
      - grafana-data:/var/lib/grafana
      - ./grafana/dashboards:/etc/grafana/provisioning/dashboards:ro
      - ./grafana/datasources:/etc/grafana/provisioning/datasources:ro
    depends_on:
      - prometheus
      - loki
      - tempo
    logging: *default-logging

  # ============================================================
  # Loki — Log Aggregation
  # ============================================================
  loki:
    image: grafana/loki:3.0.0
    container_name: metruyenchu-loki
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "3100:3100"
    volumes:
      - loki-data:/loki
      - ./loki/loki-config.yml:/etc/loki/loki-config.yml:ro
    command: -config.file=/etc/loki/loki-config.yml
    logging: *default-logging

  # ============================================================
  # Promtail — Log Shipper
  # ============================================================
  promtail:
    image: grafana/promtail:3.0.0
    container_name: metruyenchu-promtail
    restart: unless-stopped
    networks:
      - metruyenchu-net
    volumes:
      - /var/log:/var/log:ro
      - /var/lib/docker/containers:/var/lib/docker/containers:ro
      - ./promtail/promtail-config.yml:/etc/promtail/promtail-config.yml:ro
    command: -config.file=/etc/promtail/promtail-config.yml
    logging: *default-logging

  # ============================================================
  # Tempo — Distributed Tracing
  # ============================================================
  tempo:
    image: grafana/tempo:2.4.0
    container_name: metruyenchu-tempo
    restart: unless-stopped
    networks:
      - metruyenchu-net
    ports:
      - "3200:3200"   # Tempo HTTP
      - "4317:4317"   # OTLP gRPC
      - "4318:4318"   # OTLP HTTP
    volumes:
      - tempo-data:/tmp/tempo
      - ./tempo/tempo-config.yml:/etc/tempo/tempo-config.yml:ro
    command: -config.file=/etc/tempo/tempo-config.yml
    logging: *default-logging
```

### 1.2 File `deployment/docker-compose/.env`

```bash
# -----------------------------------------------------------
# Spring Profiles
# -----------------------------------------------------------
SPRING_PROFILES_ACTIVE=dev
NODE_ENV=development

# -----------------------------------------------------------
# Database Credentials
# -----------------------------------------------------------
AUTH_DB_PASSWORD=mtc_auth_dev
STORY_DB_PASSWORD=mtc_story_dev
SOCIAL_DB_PASSWORD=mtc_social_dev
AUDIO_DB_PASSWORD=mtc_audio_dev

# -----------------------------------------------------------
# JWT
# -----------------------------------------------------------
JWT_SECRET=dev-jwt-secret-at-least-256-bits-long-change-in-production
JWT_EXPIRATION_MS=900000
JWT_REFRESH_EXPIRATION_MS=604800000

# -----------------------------------------------------------
# RabbitMQ
# -----------------------------------------------------------
RABBITMQ_USER=mtc_rabbit
RABBITMQ_PASSWORD=mtc_rabbit_dev

# -----------------------------------------------------------
# MinIO
# -----------------------------------------------------------
MINIO_ROOT_USER=mtc_minio
MINIO_ROOT_PASSWORD=mtc_minio_dev

# -----------------------------------------------------------
# LocalAI
# -----------------------------------------------------------
LOCALAI_THREADS=4
LOCALAI_DEBUG=false

# -----------------------------------------------------------
# OAuth2 — Google
# -----------------------------------------------------------
OAUTH2_GOOGLE_CLIENT_ID=
OAUTH2_GOOGLE_CLIENT_SECRET=

# -----------------------------------------------------------
# OAuth2 — Facebook
# -----------------------------------------------------------
OAUTH2_FACEBOOK_CLIENT_ID=
OAUTH2_FACEBOOK_CLIENT_SECRET=

# -----------------------------------------------------------
# OAuth2 — Zalo
# -----------------------------------------------------------
OAUTH2_ZALO_CLIENT_ID=
OAUTH2_ZALO_CLIENT_SECRET=

# -----------------------------------------------------------
# Mail (SMTP)
# -----------------------------------------------------------
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_FROM=noreply@metruyenchu.com

# -----------------------------------------------------------
# VAPID Keys (Web Push)
# -----------------------------------------------------------
VAPID_PUBLIC_KEY=
VAPID_PRIVATE_KEY=

# -----------------------------------------------------------
# CORS
# -----------------------------------------------------------
CORS_ALLOWED_ORIGINS=http://localhost:3000

# -----------------------------------------------------------
# Frontend Public Variables
# -----------------------------------------------------------
NEXT_PUBLIC_API_URL=http://localhost:8080
NEXT_PUBLIC_WS_URL=ws://localhost:8080

# -----------------------------------------------------------
# Grafana
# -----------------------------------------------------------
GRAFANA_USER=admin
GRAFANA_PASSWORD=mtc_grafana_dev
```

### 1.3 Script khởi động

File `deployment/docker-compose/start-dev.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
ENV_FILE="${SCRIPT_DIR}/.env"

echo "==> Starting MeTruyenChu development environment..."

export $(grep -v '^#' "$ENV_FILE" | xargs)

# Step 1: Infrastructure
echo "==> [1/3] Starting infrastructure (databases, cache, queue, storage)..."
docker compose -f "$COMPOSE_FILE" up -d \
  postgres-auth postgres-story postgres-social postgres-audio \
  redis rabbitmq minio minio-init

echo "==> Waiting for databases to be healthy..."
sleep 15

# Step 2: AI + Observability
echo "==> [2/3] Starting AI engine and observability stack..."
docker compose -f "$COMPOSE_FILE" up -d \
  local-ai local-ai-init \
  prometheus grafana loki promtail tempo

echo "==> Waiting for LocalAI to download model..."
sleep 30

# Step 3: Application services
echo "==> [3/3] Building and starting application services..."
docker compose -f "$COMPOSE_FILE" up -d --build \
  gateway-service auth-service story-service social-service \
  audio-service notification-service analytics-service frontend

echo ""
echo "==> All services started. URLs:"
echo "    Frontend:       http://localhost:3000"
echo "    API Gateway:    http://localhost:8080"
echo "    RabbitMQ UI:    http://localhost:15672 (mtc_rabbit / mtc_rabbit_dev)"
echo "    MinIO Console:  http://localhost:9001 (mtc_minio / mtc_minio_dev)"
echo "    Prometheus:     http://localhost:9090"
echo "    Grafana:        http://localhost:3001 (admin / mtc_grafana_dev)"
echo "    Loki:           http://localhost:3100"
echo "    Tempo:          http://localhost:3200"
```

### 1.4 File `deployment/docker-compose/localai/models.yaml`

```yaml
name: omni-voice
backend: whisper
parameters:
  model: omni-voice-q8_0.gguf
  language: vi
threads: 4
```

---

## 2. Environment Variables

### 2.1 Danh sách đầy đủ biến môi trường

| Nhóm | Biến | Mặc định (dev) | Bắt buộc | Mô tả |
|------|------|---------------|----------|-------|
| **Spring** | `SPRING_PROFILES_ACTIVE` | `dev` | Có | Hồ sơ Spring Boot |
| | `SERVER_PORT` | `8081`-`8086` | Có | Cổng dịch vụ |
| **Database** | `SPRING_DATASOURCE_URL` | - | Có | JDBC URL (mỗi service một DB riêng) |
| | `SPRING_DATASOURCE_USERNAME` | `mtc_{service}` | Có | User DB |
| | `SPRING_DATASOURCE_PASSWORD` | - | Có | Password DB |
| **JWT** | `JWT_SECRET` | - | Có | Khóa bí mật RS256/HMAC (≥256 bits) |
| | `JWT_EXPIRATION_MS` | `900000` | Không | Thời hạn access token (15 phút) |
| | `JWT_REFRESH_EXPIRATION_MS` | `604800000` | Không | Thời hạn refresh token (7 ngày) |
| **Redis** | `SPRING_REDIS_HOST` | `redis` | Có | Host Redis |
| | `SPRING_REDIS_PORT` | `6379` | Có | Port Redis |
| **RabbitMQ** | `SPRING_RABBITMQ_HOST` | `rabbitmq` | Có | Host RabbitMQ |
| | `SPRING_RABBITMQ_PORT` | `5672` | Có | Port RabbitMQ |
| | `SPRING_RABBITMQ_USERNAME` | `mtc_rabbit` | Có | User RabbitMQ |
| | `SPRING_RABBITMQ_PASSWORD` | - | Có | Password RabbitMQ |
| **MinIO** | `MINIO_ENDPOINT` | `http://minio:9000` | Có | Endpoint MinIO |
| | `MINIO_ACCESS_KEY` | - | Có | Access Key |
| | `MINIO_SECRET_KEY` | - | Có | Secret Key |
| | `MINIO_AUDIO_BUCKET` | `audio-files` | Có | Bucket lưu audio |
| **LocalAI** | `LOCALAI_URL` | `http://local-ai:8080` | Có | URL LocalAI |
| | `LOCALAI_THREADS` | `4` | Không | Số thread cho TTS |
| **OAuth2** | `OAUTH2_GOOGLE_CLIENT_ID` | - | Có (prod) | Google OAuth Client ID |
| | `OAUTH2_GOOGLE_CLIENT_SECRET` | - | Có (prod) | Google OAuth Secret |
| | `OAUTH2_FACEBOOK_CLIENT_ID` | - | Có (prod) | Facebook App ID |
| | `OAUTH2_FACEBOOK_CLIENT_SECRET` | - | Có (prod) | Facebook Secret |
| | `OAUTH2_ZALO_CLIENT_ID` | - | Có (prod) | Zalo App ID |
| | `OAUTH2_ZALO_CLIENT_SECRET` | - | Có (prod) | Zalo Secret |
| **Mail** | `MAIL_HOST` | `smtp.gmail.com` | Có | SMTP host |
| | `MAIL_PORT` | `587` | Có | SMTP port |
| | `MAIL_USERNAME` | - | Có (prod) | SMTP username |
| | `MAIL_PASSWORD` | - | Có (prod) | SMTP password |
| | `MAIL_FROM` | `noreply@metruyenchu.com` | Có | Địa chỉ gửi mail |
| **Web Push** | `VAPID_PUBLIC_KEY` | - | Có | VAPID public key |
| | `VAPID_PRIVATE_KEY` | - | Có | VAPID private key |
| **CORS** | `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Có | Nguồn được phép CORS |

### 2.2 Sinh VAPID Keys

```bash
# Cài đặt web-push CLI
npm install -g web-push

# Sinh cặp key
web-push generate-vapid-keys --json

# Output:
# {
#   "publicKey": "BAdf9J...",
#   "privateKey": "qB8e3R..."
# }
```

---

## 3. Kubernetes Deployment (Production)

### 3.1 Cấu trúc thư mục Helm

```
deployment/k8s/
├── charts/
│   ├── postgres-auth/
│   │   ├── Chart.yaml
│   │   ├── templates/
│   │   │   ├── deployment.yaml
│   │   │   ├── service.yaml
│   │   │   ├── pvc.yaml
│   │   │   ├── networkpolicy.yaml
│   │   │   └── pdb.yaml
│   │   └── values.yaml
│   ├── postgres-story/
│   ├── postgres-social/
│   ├── postgres-audio/
│   ├── redis/
│   ├── rabbitmq/
│   ├── minio/
│   ├── local-ai/
│   ├── gateway-service/
│   ├── auth-service/
│   ├── story-service/
│   ├── social-service/
│   ├── audio-service/
│   ├── notification-service/
│   ├── analytics-service/
│   ├── frontend/
│   ├── prometheus/
│   ├── grafana/
│   ├── loki/
│   └── tempo/
├── env/
│   ├── staging/
│   │   ├── values.yaml
│   │   └── secrets.yaml
│   └── prod/
│       ├── values.yaml
│       └── secrets.yaml
├── Chart.yaml              # Parent umbrella chart
└── values.yaml             # Global defaults
```

### 3.2 Umbrella Chart — `Chart.yaml`

```yaml
apiVersion: v2
name: metruyenchu
description: MeTruyenChu platform Helm chart
type: application
version: 1.0.0
appVersion: "1.0.0"

dependencies:
  - name: postgres-auth
    version: "1.0.0"
    repository: "file://charts/postgres-auth"
  - name: postgres-story
    version: "1.0.0"
    repository: "file://charts/postgres-story"
  - name: postgres-social
    version: "1.0.0"
    repository: "file://charts/postgres-social"
  - name: postgres-audio
    version: "1.0.0"
    repository: "file://charts/postgres-audio"
  - name: redis
    version: "1.0.0"
    repository: "file://charts/redis"
  - name: rabbitmq
    version: "1.0.0"
    repository: "file://charts/rabbitmq"
  - name: minio
    version: "1.0.0"
    repository: "file://charts/minio"
  - name: local-ai
    version: "1.0.0"
    repository: "file://charts/local-ai"
  - name: gateway-service
    version: "1.0.0"
    repository: "file://charts/gateway-service"
  - name: auth-service
    version: "1.0.0"
    repository: "file://charts/auth-service"
  - name: story-service
    version: "1.0.0"
    repository: "file://charts/story-service"
  - name: social-service
    version: "1.0.0"
    repository: "file://charts/social-service"
  - name: audio-service
    version: "1.0.0"
    repository: "file://charts/audio-service"
  - name: notification-service
    version: "1.0.0"
    repository: "file://charts/notification-service"
  - name: analytics-service
    version: "1.0.0"
    repository: "file://charts/analytics-service"
  - name: frontend
    version: "1.0.0"
    repository: "file://charts/frontend"
  - name: prometheus
    version: "1.0.0"
    repository: "file://charts/prometheus"
  - name: grafana
    version: "1.0.0"
    repository: "file://charts/grafana"
  - name: loki
    version: "1.0.0"
    repository: "file://charts/loki"
  - name: tempo
    version: "1.0.0"
    repository: "file://charts/tempo"
```

### 3.3 Namespace

```yaml
# deployment/k8s/namespace.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: metruyenchu-staging
---
apiVersion: v1
kind: Namespace
metadata:
  name: metruyenchu-prod
```

### 3.4 ConfigMap — Non-sensitive Config

```yaml
# deployment/k8s/env/prod/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: metruyenchu-config
  namespace: metruyenchu-prod
data:
  SPRING_PROFILES_ACTIVE: "prod"
  JWT_EXPIRATION_MS: "900000"
  JWT_REFRESH_EXPIRATION_MS: "604800000"
  CORS_ALLOWED_ORIGINS: "https://metruyenchu.com,https://admin.metruyenchu.com"
  MAIL_HOST: "smtp.sendgrid.net"
  MAIL_PORT: "587"
  MAIL_FROM: "noreply@metruyenchu.com"
  MINIO_ENDPOINT: "http://minio.metruyenchu-prod.svc.cluster.local:9000"
  MINIO_AUDIO_BUCKET: "audio-files"
  MINIO_CACHE_BUCKET: "audio-cache"
  LOCALAI_URL: "http://local-ai.metruyenchu-prod.svc.cluster.local:8080"
  SPRING_REDIS_HOST: "redis.metruyenchu-prod.svc.cluster.local"
  SPRING_REDIS_PORT: "6379"
  SPRING_RABBITMQ_HOST: "rabbitmq.metruyenchu-prod.svc.cluster.local"
  SPRING_RABBITMQ_PORT: "5672"
  NEXT_PUBLIC_API_URL: "https://api.metruyenchu.com"
  NEXT_PUBLIC_WS_URL: "wss://api.metruyenchu.com"
  # Database JDBC URLs
  AUTH_DB_URL: "jdbc:postgresql://postgres-auth.metruyenchu-prod.svc:5432/auth_db"
  STORY_DB_URL: "jdbc:postgresql://postgres-story.metruyenchu-prod.svc:5432/story_db"
  SOCIAL_DB_URL: "jdbc:postgresql://postgres-social.metruyenchu-prod.svc:5432/social_db"
  AUDIO_DB_URL: "jdbc:postgresql://postgres-audio.metruyenchu-prod.svc:5432/audio_db"
  # Service URLs for Feign clients
  AUTH_SERVICE_URL: "http://auth-service.metruyenchu-prod.svc:8081"
  STORY_SERVICE_URL: "http://story-service.metruyenchu-prod.svc:8082"
  SOCIAL_SERVICE_URL: "http://social-service.metruyenchu-prod.svc:8083"
  AUDIO_SERVICE_URL: "http://audio-service.metruyenchu-prod.svc:8084"
  NOTIFICATION_SERVICE_URL: "http://notification-service.metruyenchu-prod.svc:8085"
  ANALYTICS_SERVICE_URL: "http://analytics-service.metruyenchu-prod.svc:8086"
```

### 3.5 Secret — Sensitive Config (Vault)

```yaml
# deployment/k8s/env/prod/secrets.yaml
# KHÔNG commit file này vào Git. Dùng Vault hoặc Sealed Secrets.
# File này là template để tham khảo cấu trúc.

apiVersion: v1
kind: Secret
metadata:
  name: metruyenchu-secrets
  namespace: metruyenchu-prod
type: Opaque
stringData:
  JWT_SECRET: "<base64-encoded-256-bit-key>"
  AUTH_DB_PASSWORD: "<random-32-chars>"
  STORY_DB_PASSWORD: "<random-32-chars>"
  SOCIAL_DB_PASSWORD: "<random-32-chars>"
  AUDIO_DB_PASSWORD: "<random-32-chars>"
  RABBITMQ_USER: "mtc_rabbit"
  RABBITMQ_PASSWORD: "<random-32-chars>"
  MINIO_ROOT_USER: "mtc_minio"
  MINIO_ROOT_PASSWORD: "<random-32-chars>"
  OAUTH2_GOOGLE_CLIENT_ID: "<google-client-id>"
  OAUTH2_GOOGLE_CLIENT_SECRET: "<google-client-secret>"
  OAUTH2_FACEBOOK_CLIENT_ID: "<facebook-app-id>"
  OAUTH2_FACEBOOK_CLIENT_SECRET: "<facebook-secret>"
  OAUTH2_ZALO_CLIENT_ID: "<zalo-app-id>"
  OAUTH2_ZALO_CLIENT_SECRET: "<zalo-secret>"
  MAIL_USERNAME: "apikey"
  MAIL_PASSWORD: "<sendgrid-api-key>"
  VAPID_PUBLIC_KEY: "<vapid-public-key>"
  VAPID_PRIVATE_KEY: "<vapid-private-key>"
```

**Ghi chú:** Sử dụng [external-secrets-operator](https://external-secrets.io/) hoặc [sealed-secrets](https://github.com/bitnami-labs/sealed-secrets) để quản lý secrets an toàn trong GitOps.

### 3.6 Deployment Template — Microservice (ví dụ: Story Service)

```yaml
# deployment/k8s/charts/story-service/templates/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Values.name }}
  namespace: {{ .Values.namespace }}
  labels:
    app: {{ .Values.name }}
    service: {{ .Values.name }}
spec:
  replicas: {{ .Values.replicaCount }}
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  selector:
    matchLabels:
      app: {{ .Values.name }}
  template:
    metadata:
      labels:
        app: {{ .Values.name }}
        service: {{ .Values.name }}
    spec:
      serviceAccountName: {{ .Values.serviceAccountName }}
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        fsGroup: 1000
      terminationGracePeriodSeconds: 60
      containers:
        - name: {{ .Values.name }}
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: ["ALL"]
          ports:
            - containerPort: {{ .Values.service.targetPort }}
              protocol: TCP
          envFrom:
            - configMapRef:
                name: metruyenchu-config
            - secretRef:
                name: metruyenchu-secrets
          env:
            - name: SPRING_DATASOURCE_URL
              value: {{ .Values.db.jdbcUrl }}
            - name: SPRING_DATASOURCE_USERNAME
              value: {{ .Values.db.username }}
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: metruyenchu-secrets
                  key: {{ .Values.db.passwordSecretKey }}
            - name: JAVA_TOOL_OPTIONS
              value: "-XX:+UseContainerSupport -XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"
          resources:
            requests:
              memory: {{ .Values.resources.requests.memory }}
              cpu: {{ .Values.resources.requests.cpu }}
            limits:
              memory: {{ .Values.resources.limits.memory }}
              cpu: {{ .Values.resources.limits.cpu }}
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: {{ .Values.service.targetPort }}
            initialDelaySeconds: 60
            periodSeconds: 30
            timeoutSeconds: 5
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: {{ .Values.service.targetPort }}
            initialDelaySeconds: 30
            periodSeconds: 15
            timeoutSeconds: 5
            failureThreshold: 3
          startupProbe:
            httpGet:
              path: /actuator/health
              port: {{ .Values.service.targetPort }}
            initialDelaySeconds: 10
            periodSeconds: 5
            failureThreshold: 30
          volumeMounts:
            - name: tmp
              mountPath: /tmp
            - name: cache
              mountPath: /cache
      volumes:
        - name: tmp
          emptyDir: {}
        - name: cache
          emptyDir:
            medium: Memory
            sizeLimit: 128Mi
---
# deployment/k8s/charts/story-service/templates/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ .Values.name }}
  namespace: {{ .Values.namespace }}
  labels:
    app: {{ .Values.name }}
spec:
  type: {{ .Values.service.type }}
  ports:
    - port: {{ .Values.service.port }}
      targetPort: {{ .Values.service.targetPort }}
      protocol: TCP
      name: http
  selector:
    app: {{ .Values.name }}
---
# deployment/k8s/charts/story-service/templates/hpa.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ .Values.name }}-hpa
  namespace: {{ .Values.namespace }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ .Values.name }}
  minReplicas: {{ .Values.hpa.minReplicas }}
  maxReplicas: {{ .Values.hpa.maxReplicas }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ .Values.hpa.targetCPUUtilization }}
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: {{ .Values.hpa.targetMemoryUtilization }}
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
        - type: Percent
          value: 10
          periodSeconds: 60
    scaleUp:
      stabilizationWindowSeconds: 0
      policies:
        - type: Percent
          value: 100
          periodSeconds: 60
        - type: Pods
          value: 4
          periodSeconds: 60
      selectPolicy: Max
---
# deployment/k8s/charts/story-service/templates/pdb.yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ .Values.name }}-pdb
  namespace: {{ .Values.namespace }}
spec:
  minAvailable: {{ .Values.pdb.minAvailable }}
  selector:
    matchLabels:
      app: {{ .Values.name }}
```

### 3.7 Values — Story Service

```yaml
# deployment/k8s/charts/story-service/values.yaml
name: story-service
namespace: metruyenchu-prod
replicaCount: 3
serviceAccountName: story-service

image:
  repository: ghcr.io/metruyenchu/story-service
  tag: latest
  pullPolicy: Always

service:
  type: ClusterIP
  port: 8082
  targetPort: 8082

db:
  jdbcUrl: jdbc:postgresql://postgres-story.metruyenchu-prod.svc:5432/story_db
  username: mtc_story
  passwordSecretKey: STORY_DB_PASSWORD

resources:
  requests:
    cpu: "500m"
    memory: "768Mi"
  limits:
    cpu: "1000m"
    memory: "1Gi"

hpa:
  minReplicas: 3
  maxReplicas: 10
  targetCPUUtilization: 70
  targetMemoryUtilization: 80

pdb:
  minAvailable: 2
```

### 3.8 Resource requests/limits cho tất cả services

| Service | Request CPU | Request Memory | Limit CPU | Limit Memory | Min Replicas | Max Replicas |
|---------|------------|---------------|-----------|-------------|-------------|-------------|
| gateway-service | 500m | 512Mi | 1000m | 1Gi | 2 | 6 |
| auth-service | 500m | 768Mi | 1000m | 1Gi | 2 | 8 |
| story-service | 500m | 768Mi | 1000m | 1Gi | 3 | 10 |
| social-service | 500m | 768Mi | 1000m | 1Gi | 2 | 8 |
| audio-service | 500m | 1Gi | 1000m | 2Gi | 2 | 6 |
| notification-service | 300m | 512Mi | 500m | 768Mi | 2 | 6 |
| analytics-service | 300m | 512Mi | 500m | 768Mi | 1 | 4 |
| frontend | 200m | 256Mi | 500m | 512Mi | 2 | 10 |
| local-ai | 2000m | 4Gi | 4000m | 8Gi | 1 | 1 |

### 3.9 NetworkPolicy

```yaml
# deployment/k8s/charts/story-service/templates/networkpolicy.yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ .Values.name }}-network-policy
  namespace: {{ .Values.namespace }}
spec:
  podSelector:
    matchLabels:
      app: {{ .Values.name }}
  policyTypes:
    - Ingress
    - Egress
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app: gateway-service
        - podSelector:
            matchLabels:
              app: social-service
      ports:
        - protocol: TCP
          port: {{ .Values.service.targetPort }}
  egress:
    - to:
        - podSelector:
            matchLabels:
              app: postgres-story
        - podSelector:
            matchLabels:
              app: redis
        - podSelector:
            matchLabels:
              app: rabbitmq
      ports:
        - protocol: TCP
          port: 5432
        - protocol: TCP
          port: 6379
        - protocol: TCP
          port: 5672
```

### 3.10 Ingress Controller

```yaml
# deployment/k8s/ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: metruyenchu-ingress
  namespace: metruyenchu-prod
  annotations:
    kubernetes.io/ingress.class: "nginx"
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    nginx.ingress.kubernetes.io/proxy-body-size: "50m"
    nginx.ingress.kubernetes.io/proxy-read-timeout: "120"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "120"
    nginx.ingress.kubernetes.io/enable-cors: "true"
    nginx.ingress.kubernetes.io/cors-allow-origin: "https://metruyenchu.com,https://admin.metruyenchu.com"
    nginx.ingress.kubernetes.io/cors-allow-credentials: "true"
    nginx.ingress.kubernetes.io/limit-rps: "100"
    nginx.ingress.kubernetes.io/limit-rpm: "3000"
spec:
  tls:
    - hosts:
        - metruyenchu.com
        - api.metruyenchu.com
        - admin.metruyenchu.com
      secretName: metruyenchu-tls
  rules:
    - host: metruyenchu.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: frontend
                port:
                  number: 3000
    - host: api.metruyenchu.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: gateway-service
                port:
                  number: 8080
    - host: admin.metruyenchu.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: frontend
                port:
                  number: 3000
```

### 3.11 PersistentVolumeClaims

```yaml
# deployment/k8s/charts/postgres-auth/templates/pvc.yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: postgres-auth-pvc
  namespace: {{ .Values.namespace }}
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: {{ .Values.storage.size }}
  storageClassName: {{ .Values.storage.storageClassName }}
```

| Service | Storage Size | Storage Class |
|---------|-------------|---------------|
| postgres-auth | 20Gi | ssd |
| postgres-story | 50Gi | ssd |
| postgres-social | 50Gi | ssd |
| postgres-audio | 10Gi | ssd |
| redis | 5Gi | ssd |
| rabbitmq | 5Gi | standard |
| minio | 200Gi | standard |
| local-ai | 50Gi | standard (models) |

### 3.12 Lệnh triển khai

```bash
# Cài đặt cert-manager
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.15.0/cert-manager.yaml

# Cài đặt nginx-ingress
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm upgrade --install ingress-nginx ingress-nginx/ingress-nginx \
  --namespace ingress-nginx --create-namespace

# Cài đặt Prometheus Stack
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  --namespace monitoring --create-namespace

# Tạo namespace
kubectl create namespace metruyenchu-staging
kubectl create namespace metruyenchu-prod

# Triển khai staging
helm upgrade --install metruyenchu ./deployment/k8s \
  --namespace metruyenchu-staging \
  -f deployment/k8s/env/staging/values.yaml \
  -f deployment/k8s/env/staging/secrets.yaml \
  --set global.environment=staging

# Triển khai production
helm upgrade --install metruyenchu ./deployment/k8s \
  --namespace metruyenchu-prod \
  -f deployment/k8s/env/prod/values.yaml \
  -f deployment/k8s/env/prod/secrets.yaml \
  --set global.environment=prod
```

---

## 4. CI/CD Pipeline (GitHub Actions)

### 4.1 File `.github/workflows/ci.yml`

```yaml
name: CI - Build & Test

on:
  pull_request:
    branches: [main, develop]
  push:
    branches: [develop]

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  # ============================================================
  # Backend: Compile, Test, Lint
  # ============================================================
  backend:
    name: Backend Build & Test
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service:
          - gateway-service
          - auth-service
          - story-service
          - social-service
          - audio-service
          - notification-service
          - analytics-service

    services:
      postgres:
        image: postgres:16-alpine
        env:
          POSTGRES_DB: test_db
          POSTGRES_USER: test
          POSTGRES_PASSWORD: test
        ports:
          - 5432:5432
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
      redis:
        image: redis:7-alpine
        ports:
          - 6379:6379
        options: >-
          --health-cmd "redis-cli ping"
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
      rabbitmq:
        image: rabbitmq:4-alpine
        env:
          RABBITMQ_DEFAULT_USER: test
          RABBITMQ_DEFAULT_PASS: test
        ports:
          - 5672:5672
        options: >-
          --health-cmd "rabbitmq-diagnostics check_port_connectivity"
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: "21"
          distribution: "temurin"
          cache: "gradle"

      - name: Compile
        run: |
          ./gradlew :services/${{ matrix.service }}:compileJava
          ./gradlew :services/${{ matrix.service }}:compileTestJava

      - name: Unit Tests
        run: ./gradlew :services/${{ matrix.service }}:test --tests "*Test" --tests "*UnitTest"

      - name: Integration Tests
        run: ./gradlew :services/${{ matrix.service }}:test --tests "*IntegrationTest" --tests "*IT"

      - name: Contract Tests
        if: matrix.service == 'auth-service' || matrix.service == 'story-service'
        run: ./gradlew :services/${{ matrix.service }}:contractTest

      - name: Lint (Checkstyle)
        run: ./gradlew :services/${{ matrix.service }}:checkstyleMain

      - name: Publish Test Report
        if: always()
        uses: dorny/test-reporter@v1
        with:
          name: "Test Report - ${{ matrix.service }}"
          path: "services/${{ matrix.service }}/build/reports/tests/test/*.xml"
          reporter: java-junit
          fail-on-error: false

  # ============================================================
  # Frontend: TypeScript, Lint, Test, Build
  # ============================================================
  frontend:
    name: Frontend Build & Test
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: frontend

    steps:
      - uses: actions/checkout@v4

      - name: Set up Node.js 20
        uses: actions/setup-node@v4
        with:
          node-version: "20"
          cache: "npm"
          cache-dependency-path: frontend/package-lock.json

      - name: Install dependencies
        run: npm ci

      - name: TypeScript type check
        run: npm run typecheck

      - name: Lint
        run: npm run lint

      - name: Unit tests
        run: npm run test -- --coverage

      - name: Build
        run: npm run build

      - name: Upload coverage
        uses: codecov/codecov-action@v4
        with:
          directory: frontend/coverage
          flags: frontend

  # ============================================================
  # Docker Build (triggered after tests pass)
  # ============================================================
  docker-build:
    name: Docker Build
    needs: [backend, frontend]
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service:
          - gateway-service
          - auth-service
          - story-service
          - social-service
          - audio-service
          - notification-service
          - analytics-service
          - frontend

    steps:
      - uses: actions/checkout@v4

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Log in to GHCR
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Extract metadata
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ghcr.io/${{ github.repository }}/${{ matrix.service }}
          tags: |
            type=ref,event=branch
            type=sha,prefix=,suffix=,format=short
            type=semver,pattern={{version}}

      - name: Build & Push
        uses: docker/build-push-action@v5
        with:
          context: services/${{ matrix.service }}
          push: ${{ github.event_name == 'push' }}
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          cache-from: type=gha
          cache-to: type=gha,mode=max

      - name: Scan image (Trivy)
        uses: aquasecurity/trivy-action@master
        with:
          image-ref: ghcr.io/${{ github.repository }}/${{ matrix.service }}:${{ steps.meta.outputs.version }}
          format: table
          exit-code: "0"
          severity: HIGH,CRITICAL
```

### 4.2 File `.github/workflows/cd.yml`

```yaml
name: CD - Deploy

on:
  push:
    branches: [main]

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: false

jobs:
  # ============================================================
  # Deploy to Staging
  # ============================================================
  deploy-staging:
    name: Deploy to Staging
    runs-on: ubuntu-latest
    environment: staging

    steps:
      - uses: actions/checkout@v4

      - name: Configure kubectl
        uses: azure/setup-kubectl@v4
        with:
          version: "v1.30.0"

      - name: Set up kubeconfig
        run: |
          mkdir -p $HOME/.kube
          echo "${{ secrets.KUBE_CONFIG_STAGING }}" | base64 --decode > $HOME/.kube/config

      - name: Helm upgrade staging
        run: |
          helm upgrade --install metruyenchu ./deployment/k8s \
            --namespace metruyenchu-staging \
            -f deployment/k8s/env/staging/values.yaml \
            -f deployment/k8s/env/staging/secrets.yaml \
            --set global.environment=staging \
            --set global.image.tag=${{ github.sha }} \
            --wait --timeout 10m

      - name: Smoke tests
        run: |
          echo "==> Running smoke tests..."
          # Health checks
          SERVICES=(
            "gateway-service:8080"
            "auth-service:8081"
            "story-service:8082"
            "social-service:8083"
            "audio-service:8084"
            "notification-service:8085"
            "analytics-service:8086"
          )
          for SERVICE in "${SERVICES[@]}"; do
            NAME="${SERVICE%%:*}"
            PORT="${SERVICE##*:}"
            echo "Checking $NAME..."
            kubectl run smoke-test-$NAME --image=curlimages/curl --restart=Never \
              --namespace metruyenchu-staging \
              --command -- curl -sf http://$NAME:${PORT}/actuator/health
            kubectl delete pod smoke-test-$NAME --namespace metruyenchu-staging
          done

          # Basic E2E: register user, login, access protected endpoint
          echo "==> Running basic E2E..."
          kubectl run smoke-test-e2e --image=curlimages/curl --restart=Never \
            --namespace metruyenchu-staging \
            --command -- sh -c "
              # Register
              curl -sf -X POST http://gateway-service:8080/api/v1/auth/register \
                -H 'Content-Type: application/json' \
                -d '{\"email\":\"test@smoke.com\",\"password\":\"SmokeTest123!\",\"displayName\":\"Smoke Test\"}' &&

              # Login
              LOGIN_RESP=\$(curl -sf -X POST http://gateway-service:8080/api/v1/auth/login \
                -H 'Content-Type: application/json' \
                -d '{\"email\":\"test@smoke.com\",\"password\":\"SmokeTest123!\"}') &&

              # Extract token
              TOKEN=\$(echo \$LOGIN_RESP | grep -o '\"accessToken\":\"[^\"]*\"' | cut -d'\"' -f4) &&

              # Access profile
              curl -sf http://gateway-service:8080/api/v1/auth/me \
                -H 'Authorization: Bearer \$TOKEN' &&

              echo 'SUCCESS'
            "
          kubectl logs smoke-test-e2e --namespace metruyenchu-staging
          kubectl delete pod smoke-test-e2e --namespace metruyenchu-staging

      - name: Notify Slack — Staging deployed
        uses: slackapi/slack-github-action@v1.26.0
        with:
          payload: |
            {
              "text": "✅ Staging deployed successfully — ${{ github.sha }}"
            }
        env:
          SLACK_WEBHOOK_URL: ${{ secrets.SLACK_WEBHOOK_URL }}

  # ============================================================
  # Deploy to Production (manual approval)
  # ============================================================
  deploy-production:
    name: Deploy to Production
    runs-on: ubuntu-latest
    needs: [deploy-staging]
    environment: production
    concurrency: production-deploy

    steps:
      - uses: actions/checkout@v4

      - name: Configure kubectl
        uses: azure/setup-kubectl@v4
        with:
          version: "v1.30.0"

      - name: Set up kubeconfig
        run: |
          mkdir -p $HOME/.kube
          echo "${{ secrets.KUBE_CONFIG_PRODUCTION }}" | base64 --decode > $HOME/.kube/config

      - name: Create Helm backup tag
        run: |
          kubectl label pods -n metruyenchu-prod -l app=gateway-service \
            "backup-timestamp=$(date +%s)" --overwrite
          helm get values metruyenchu --namespace metruyenchu-prod > /tmp/pre-deploy-values.yaml

      - name: Helm upgrade production
        run: |
          helm upgrade --install metruyenchu ./deployment/k8s \
            --namespace metruyenchu-prod \
            -f deployment/k8s/env/prod/values.yaml \
            -f deployment/k8s/env/prod/secrets.yaml \
            --set global.environment=prod \
            --set global.image.tag=${{ github.sha }} \
            --atomic --wait --timeout 15m

      - name: Health check after deploy
        run: |
          echo "Waiting for all pods to be ready..."
          kubectl wait --for=condition=Ready pods --all \
            -n metruyenchu-prod --timeout=300s

          echo "Running health checks..."
          kubectl run post-deploy-check --image=curlimages/curl \
            --namespace metruyenchu-prod \
            --restart=Never --command -- sh -c "
              curl -sf http://gateway-service:8080/actuator/health &&
              curl -sf http://auth-service:8081/actuator/health &&
              curl -sf http://story-service:8082/actuator/health &&
              curl -sf http://frontend:3000/api/health
            "
          kubectl logs post-deploy-check --namespace metruyenchu-prod
          kubectl delete pod post-deploy-check --namespace metruyenchu-prod

      - name: Notify Slack — Production deployed
        uses: slackapi/slack-github-action@v1.26.0
        with:
          payload: |
            {
              "text": "🚀 Production deployed — ${{ github.sha }}"
            }
        env:
          SLACK_WEBHOOK_URL: ${{ secrets.SLACK_WEBHOOK_URL }}
```

### 4.3 Rollback Strategy

```yaml
# rollback.sh
#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${1:-metruyenchu-prod}"
REVISION="${2:-}"

echo "==> Rolling back $NAMESPACE to revision $REVISION..."

if [ -n "$REVISION" ]; then
  helm rollback metruyenchu "$REVISION" --namespace "$NAMESPACE" --wait --timeout 10m
else
  # Rollback to previous revision
  helm rollback metruyenchu --namespace "$NAMESPACE" --wait --timeout 10m
fi

echo "==> Verifying rollback..."
kubectl rollout status deployment -n "$NAMESPACE" --timeout=300s

echo "==> Rollback complete."
```

**Rollback trigger:**

| Tình huống | Hành động |
|-----------|-----------|
| Pod không khởi động được sau 5 phút | Tự động rollback (Helm `--atomic`) |
| Liveness probe fail 3 lần liên tiếp | Kubernetes restart pod |
| Error rate > 5% trong 5 phút | Manual rollback qua Slack command |
| Canary release phát hiện lỗi | Tự động rollback canary |

---

## 5. Monitoring & Alerting

### 5.1 Prometheus — `prometheus.yml`

```yaml
# deployment/docker-compose/prometheus/prometheus.yml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

alerting:
  alertmanagers:
    - static_configs:
        - targets:
          - alertmanager:9093

rule_files:
  - /etc/prometheus/rules/*.yml

scrape_configs:
  - job_name: "kubernetes-pods"
    kubernetes_sd_configs:
      - role: pod
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        action: keep
        regex: true
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
        action: replace
        target_label: __metrics_path__
        regex: (.+)
      - source_labels: [__address__, __meta_kubernetes_pod_annotation_prometheus_io_port]
        action: replace
        regex: ([^:]+)(?::\d+)?;(\d+)
        replacement: $1:$2
        target_label: __address__
      - action: labelmap
        regex: __meta_kubernetes_pod_label_(.+)

  - job_name: "spring-boot"
    metrics_path: /actuator/prometheus
    scheme: http
    kubernetes_sd_configs:
      - role: pod
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_label_app]
        regex: "gateway-service|auth-service|story-service|social-service|audio-service|notification-service|analytics-service"
        action: keep
      - source_labels: [__address__]
        action: replace
        regex: ([^:]+)(?::\d+)?
        replacement: $1:8080
        target_label: __address__

  - job_name: "node-exporter"
    kubernetes_sd_configs:
      - role: node
    relabel_configs:
      - source_labels: [__address__]
        regex: "(.*):10250"
        replacement: "${1}:9100"
        target_label: __address__

  - job_name: "postgres"
    static_configs:
      - targets:
          - "postgres-exporter:9187"
```

### 5.2 Alertmanager Rules

```yaml
# deployment/docker-compose/prometheus/rules/service-alerts.yml
groups:
  - name: metruyenchu-service
    rules:
      # ============================================================
      # Service Down
      # ============================================================
      - alert: ServiceDown
        expr: up{job="spring-boot"} == 0
        for: 1m
        annotations:
          summary: "Service {{ $labels.app }} is down"
          description: "{{ $labels.app }} has been unreachable for more than 1 minute."
        labels:
          severity: critical

      # ============================================================
      # High Error Rate
      # ============================================================
      - alert: HighErrorRate
        expr: |
          rate(http_server_requests_seconds_count{status=~"5.."}[5m]) /
          rate(http_server_requests_seconds_count[5m]) > 0.05
        for: 3m
        annotations:
          summary: "High error rate on {{ $labels.app }} ({{ $labels.instance }})"
          description: "Error rate is {{ $value | humanizePercentage }} (threshold: 5%)"
        labels:
          severity: critical

      # ============================================================
      # High Latency (P99)
      # ============================================================
      - alert: HighLatency
        expr: |
          histogram_quantile(0.99,
            rate(http_server_requests_seconds_bucket{uri!~".*/actuator/.*"}[5m])
          ) > 2
        for: 5m
        annotations:
          summary: "High P99 latency on {{ $labels.app }}"
          description: "P99 latency is {{ $value }}s (threshold: 2s)"
        labels:
          severity: warning

      # ============================================================
      # High JVM Memory
      # ============================================================
      - alert: HighJVMMemory
        expr: |
          jvm_memory_used_bytes{area="heap"} /
          jvm_memory_max_bytes{area="heap"} > 0.85
        for: 5m
        annotations:
          summary: "High JVM memory on {{ $labels.app }}"
          description: "JVM heap is {{ $value | humanizePercentage }} used (threshold: 85%)"
        labels:
          severity: warning

      # ============================================================
      # Database Connection Pool Exhaustion
      # ============================================================
      - alert: DatabaseConnectionPoolExhaustion
        expr: |
          hikaricp_connections_active / hikaricp_connections_max > 0.8
        for: 5m
        annotations:
          summary: "Database connection pool nearly exhausted on {{ $labels.app }}"
          description: "Active connections: {{ $value | humanizePercentage }} of max"
        labels:
          severity: warning

  - name: metruyenchu-infra
    rules:
      # ============================================================
      # Disk Space (PostgreSQL, MinIO)
      # ============================================================
      - alert: LowDiskSpace
        expr: |
          node_filesystem_avail_bytes{mountpoint="/data"} /
          node_filesystem_size_bytes{mountpoint="/data"} < 0.1
        for: 5m
        annotations:
          summary: "Low disk space on {{ $labels.instance }}"
          description: "Available: {{ $value | humanizePercentage }} (threshold: 10%)"
        labels:
          severity: critical

      # ============================================================
      # RabbitMQ Queue Depth
      # ============================================================
      - alert: RabbitMQQueueDepth
        expr: rabbitmq_queue_messages_ready > 10000
        for: 5m
        annotations:
          summary: "RabbitMQ queue depth high"
          description: "Queue {{ $labels.queue }} has {{ $value }} ready messages"
        labels:
          severity: warning

      # ============================================================
      # Redis Memory
      # ============================================================
      - alert: RedisMemoryHigh
        expr: redis_memory_used_bytes / redis_memory_max_bytes > 0.8
        for: 5m
        annotations:
          summary: "Redis memory high"
          description: "Used: {{ $value | humanizePercentage }} (threshold: 80%)"
        labels:
          severity: warning
```

### 5.3 Grafana Dashboards

| Dashboard | Mô tả | Panels chính |
|-----------|-------|-------------|
| **JVM Overview** | JVM metrics cho mỗi Spring Boot service | Heap/Non-heap, GC pause, Thread count, Class loading |
| **HTTP Metrics** | Request/response metrics | RPS, Error rate, P50/P95/P99 latency, Status codes |
| **Business Metrics** | Nghiệp vụ | Active users, Stories created, Audio generated, Comments |
| **Database** | PostgreSQL metrics | Connections, Slow queries, Table size, Cache hit ratio |
| **Infrastructure** | Cluster-wide | CPU/Memory/Network per node, Pod status |
| **RabbitMQ** | Queue metrics | Queue depth, Publish/Consume rate, Unacked messages |
| **Tracing** | Distributed traces (Tempo) | Trace explorer, Service graph, Span duration |

### 5.4 Notification Channels

| Channel | Khi nào | Nội dung |
|---------|---------|----------|
| **PagerDuty** | `critical` alerts | Call + SMS + Push |
| **Slack #alerts** | `warning` + `critical` | Alert details + Grafana link |
| **Email** | `info` (deploy success) | Daily digest, deploy notification |

---

## 6. Backup Strategy

### 6.1 PostgreSQL — pg_dump

Script backup: `deployment/scripts/backup-postgres.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/backups/postgres}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"
DATE=$(date +%Y%m%d_%H%M%S)
DB_NAMES=("auth_db" "story_db" "social_db" "audio_db")
NAMESPACE="${NAMESPACE:-metruyenchu-prod}"

mkdir -p "$BACKUP_DIR"

for DB in "${DB_NAMES[@]}"; do
    echo "==> Backing up $DB..."

    # Map DB name to pod
    case "$DB" in
        auth_db)   POD="postgres-auth" ;;
        story_db)  POD="postgres-story" ;;
        social_db) POD="postgres-social" ;;
        audio_db)  POD="postgres-audio" ;;
    esac

    # Dump
    kubectl exec -n "$NAMESPACE" "deployment/$POD" -- \
        pg_dump -U mtc_${DB%_db} -d "$DB" \
        --no-owner --no-acl \
        --format=custom \
        > "$BACKUP_DIR/${DB}_${DATE}.dump"

    # Compress
    gzip "$BACKUP_DIR/${DB}_${DATE}.dump"

    echo "   -> Saved: ${BACKUP_DIR}/${DB}_${DATE}.dump.gz ($(du -h "${BACKUP_DIR}/${DB}_${DATE}.dump.gz" | cut -f1))"
done

# Cleanup old backups
find "$BACKUP_DIR" -name "*.dump.gz" -mtime +$RETENTION_DAYS -delete
echo "==> Cleaned up backups older than $RETENTION_DAYS days."
```

Script restore: `deployment/scripts/restore-postgres.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

BACKUP_FILE="${1:?Usage: $0 <backup_file>}"
DB_NAME="${2:?Usage: $0 <backup_file> <db_name>}"
NAMESPACE="${NAMESPACE:-metruyenchu-prod}"

if [ ! -f "$BACKUP_FILE" ]; then
    echo "Error: Backup file not found: $BACKUP_FILE"
    exit 1
fi

echo "==> Restoring $DB_NAME from $BACKUP_FILE..."

# Determine pod
case "$DB_NAME" in
    auth_db)   POD="postgres-auth" ;;
    story_db)  POD="postgres-story" ;;
    social_db) POD="postgres-social" ;;
    audio_db)  POD="postgres-audio" ;;
    *) echo "Unknown database: $DB_NAME"; exit 1 ;;
esac

# Decompress if needed
if [[ "$BACKUP_FILE" == *.gz ]]; then
    gunzip -c "$BACKUP_FILE" > /tmp/restore_${DB_NAME}.dump
    BACKUP_FILE="/tmp/restore_${DB_NAME}.dump"
fi

# Restore
cat "$BACKUP_FILE" | kubectl exec -i -n "$NAMESPACE" "deployment/$POD" -- \
    pg_restore -U mtc_${DB_NAME%_db} -d "$DB_NAME" \
    --clean --if-exists \
    --no-owner --no-acl

echo "==> Restore complete."
rm -f /tmp/restore_${DB_NAME}.dump
```

**Crontab (daily backup)**

```cron
0 3 * * * /opt/scripts/backup-postgres.sh >> /var/log/backup.log 2>&1
0 4 * * * /opt/scripts/backup-minio.sh >> /var/log/backup.log 2>&1
```

### 6.2 MinIO — Sync to DR Site

```bash
#!/usr/bin/env bash
# backup-minio.sh
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/backups/minio}"
mkdir -p "$BACKUP_DIR"

# Sync to S3-compatible DR storage
mc alias set dr-storage "https://s3-dr-region.amazonaws.com" \
  "${DR_ACCESS_KEY}" "${DR_SECRET_KEY}"

mc mirror --watch /data/audio-files \
  dr-storage/metruyenchu-backups/audio-files/

# Or use rclone for more options
rclone sync minio:audio-files \
  s3:metruyenchu-backups/audio-files/ \
  --progress --checksum
```

### 6.3 Redis — RDB Snapshots

Redis được cấu hình AOF + RDB trong docker-compose. Backup snapshot:

```bash
#!/usr/bin/env bash
# backup-redis.sh
BACKUP_DIR="${BACKUP_DIR:-/backups/redis}"
NAMESPACE="${NAMESPACE:-metruyenchu-prod}"

mkdir -p "$BACKUP_DIR"

# Trigger SAVE
kubectl exec -n "$NAMESPACE" deployment/redis -- redis-cli SAVE

# Copy dump
kubectl cp "$NAMESPACE/$(kubectl get pod -n $NAMESPACE -l app=redis -o jsonpath='{.items[0].metadata.name}'):/data/dump.rdb" \
  "${BACKUP_DIR}/redis_$(date +%Y%m%d_%H%M%S).rdb"
```

### 6.4 RabbitMQ — Definitions Export

```bash
#!/usr/bin/env bash
# backup-rabbitmq.sh
BACKUP_DIR="${BACKUP_DIR:-/backups/rabbitmq}"
NAMESPACE="${NAMESPACE:-metruyenchu-prod}"

mkdir -p "$BACKUP_DIR"

# Export definitions (includes exchanges, queues, bindings, users, vhosts)
kubectl exec -n "$NAMESPACE" deployment/rabbitmq -- \
  rabbitmqadmin export /tmp/definitions.json

kubectl cp "$NAMESPACE/$(kubectl get pod -n $NAMESPACE -l app=rabbitmq -o jsonpath='{.items[0].metadata.name}'):/tmp/definitions.json" \
  "${BACKUP_DIR}/definitions_$(date +%Y%m%d_%H%M%S).json"
```

### 6.5 Phục hồi thảm họa (Disaster Recovery)

| Kịch bản | RPO | RTO | Cách phục hồi |
|----------|-----|-----|---------------|
| Mất 1 pod | 0 | <1 phút | Kubernetes tự restart (ReplicaSet) |
| Mất 1 node | 0 | <5 phút | Pod được schedule sang node khác |
| Hỏng toàn bộ cluster | 24h | 4h | Restore từ backup, apply Helm chart |
| Hỏng database | 1 ngày | 2h | Restore pg_dump, verify data integrity |
| Lỗi ứng dụng (bug) | 0 | <10 phút | Rollback Helm release |
| Ransomware | 1 ngày | 4h | Restore từ off-site backup (DR site) |
| Region outage | 1 ngày | 8h | Deploy sang DR region, restore DB + MinIO |

---

## 7. Security

### 7.1 SSL/TLS — cert-manager + Let's Encrypt

```yaml
# deployment/k8s/cert-manager/cluster-issuer.yaml
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: ops@metruyenchu.com
    privateKeySecretRef:
      name: letsencrypt-prod-account-key
    solvers:
      - http01:
          ingress:
            class: nginx
```

### 7.2 Secrets Management — Vault

```hcl
# deployment/k8s/vault/policy.hcl
path "secret/data/metruyenchu/prod/*" {
  capabilities = ["read", "list"]
}

path "secret/data/metruyenchu/staging/*" {
  capabilities = ["read", "list"]
}
```

```yaml
# deployment/k8s/vault/external-secret.yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: metruyenchu-secrets
  namespace: metruyenchu-prod
spec:
  refreshInterval: "1h"
  secretStoreRef:
    name: vault-backend
    kind: ClusterSecretStore
  target:
    name: metruyenchu-secrets
    creationPolicy: Owner
  data:
    - secretKey: JWT_SECRET
      remoteRef:
        key: secret/data/metruyenchu/prod/jwt
        property: secret
    - secretKey: AUTH_DB_PASSWORD
      remoteRef:
        key: secret/data/metruyenchu/prod/database
        property: auth_db_password
    - secretKey: STORY_DB_PASSWORD
      remoteRef:
        key: secret/data/metruyenchu/prod/database
        property: story_db_password
    - secretKey: SOCIAL_DB_PASSWORD
      remoteRef:
        key: secret/data/metruyenchu/prod/database
        property: social_db_password
    - secretKey: AUDIO_DB_PASSWORD
      remoteRef:
        key: secret/data/metruyenchu/prod/database
        property: audio_db_password
    - secretKey: OAUTH2_GOOGLE_CLIENT_ID
      remoteRef:
        key: secret/data/metruyenchu/prod/oauth2
        property: google_client_id
    - secretKey: OAUTH2_GOOGLE_CLIENT_SECRET
      remoteRef:
        key: secret/data/metruyenchu/prod/oauth2
        property: google_client_secret
    - secretKey: VAPID_PRIVATE_KEY
      remoteRef:
        key: secret/data/metruyenchu/prod/vapid
        property: private_key
```

### 7.3 Container Security

```yaml
# Pod Security Context (áp dụng cho tất cả microservices)
securityContext:
  runAsNonRoot: true
  runAsUser: 1000
  runAsGroup: 1000
  fsGroup: 1000
  seccompProfile:
    type: RuntimeDefault

# Container Security Context
securityContext:
  allowPrivilegeEscalation: false
  privileged: false
  readOnlyRootFilesystem: true
  capabilities:
    drop: ["ALL"]
    add: ["NET_BIND_SERVICE"]  # Chỉ nếu cần bind port <1024

# Pod Security Admission (namespace-level)
apiVersion: v1
kind: Namespace
metadata:
  name: metruyenchu-prod
  labels:
    pod-security.kubernetes.io/enforce: restricted
    pod-security.kubernetes.io/audit: restricted
    pod-security.kubernetes.io/warn: restricted
```

### 7.4 Image Scanning — Trivy

```bash
# Quét tất cả images trong CI
trivy image --severity HIGH,CRITICAL ghcr.io/metruyenchu/story-service:latest

# Quét toàn bộ cluster
kubectl apply -f https://raw.githubusercontent.com/aquasecurity/trivy-operator/main/deploy/static/trivy-operator.yaml

# Hoặc dùng Starboard
kubectl starboard scan vulnerabilitydeployments -n metruyenchu-prod
```

### 7.5 Network Policies — Tổng quan

```
┌─────────────────────────┐     ┌──────────────────────────┐
│   gateway-service       │────▶│   auth-service           │
│   (allow: all external) │     │   (allow: gateway, self)  │
└─────────────────────────┘     └──────────────────────────┘
         │                              │
         │                              │
         ▼                              ▼
┌─────────────────────────┐     ┌──────────────────────────┐
│   story-service         │     │   postgres-auth           │
│   (allow: gateway,      │     │   (allow: auth-service)   │
│    social, audio)        │     └──────────────────────────┘
└─────────────────────────┘
         │
         ▼
┌─────────────────────────┐
│   postgres-story        │
│   (allow: story-service)│
└─────────────────────────┘
```

**Nguyên tắc:**
- Mỗi service chỉ cho phép ingress từ các service cần gọi nó
- Database chỉ cho phép kết nối từ service sở hữu
- Redis và RabbitMQ chỉ cho phép từ services trong cùng namespace
- Tất cả egress ra ngoài cluster bị chặn trừ DNS (port 53)

### 7.6 API Rate Limiting (Gateway)

```yaml
# gateway-service/src/main/resources/application.yml
spring:
  cloud:
    gateway:
      routes:
        - id: auth-rate-limit
          uri: lb://auth-service
          predicates:
            - Path=/api/v1/auth/**
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter:
                  replenishRate: 20    # tokens/sec
                  burstCapacity: 40    # max burst
                  requestedTokens: 1
                key-resolver: "#{@principalNameKeyResolver}"

        - id: api-rate-limit
          uri: lb://story-service
          predicates:
            - Path=/api/v1/stories/**
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter:
                  replenishRate: 100
                  burstCapacity: 200
                  requestedTokens: 1
                key-resolver: "#{@remoteAddressKeyResolver}"

        - id: audio-rate-limit
          uri: lb://audio-service
          predicates:
            - Path=/api/v1/audio/**
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter:
                  replenishRate: 10
                  burstCapacity: 20
                  requestedTokens: 1
                key-resolver: "#{@remoteAddressKeyResolver}"
```

---

## End of Deployment Spec
