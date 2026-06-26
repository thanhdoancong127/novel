# novel-backend

Backend microservices for **MeTruyenChu** platform.

## Architecture

```
services/
├── gateway-service/         # Spring Cloud Gateway (port 8080)
├── auth-service/            # Authentication & Authorization (port 8081)
├── story-service/           # Stories & Chapters CRUD (port 8082)
├── social-service/          # Comments, Bookmarks, Follows (port 8083)
├── audio-service/           # TTS Audio generation (port 8084)
├── notification-service/    # Push, Email notifications (port 8085)
└── analytics-service/       # Dashboard & Reports (port 8086)
platform-libs/
├── common-domain/           # Shared DTOs, enums, interfaces
├── common-security/         # JWT utils, security filter config
└── common-messaging/        # Event classes, topic names, schemas
```

## Tech Stack

- Java 21 (LTS)
- Spring Boot 3.4+
- Spring Cloud Gateway 2024.x
- PostgreSQL 16 (per service)
- Redis 7 (cache + rate limiting)
- RabbitMQ 4.x (async events)
- MinIO (S3-compatible storage)

## Getting Started

```bash
# Start infrastructure
docker compose -f ../infra/docker-compose/infra.yml up -d

# Start a service
cd services/auth-service
./gradlew bootRun
```
