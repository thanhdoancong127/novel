# Architecture Overview — MeTruyenChu Rebuild

> **File:** 00-architecture-overview.md
> **Part of:** metruyenchu rebuild spec series

---

## 1. System Architecture

### 1.1 High-Level Diagram

```
                    ┌──────────────┐
                    │   Client     │
                    │ (Web/Mobile) │
                    └──────┬───────┘
                           │ HTTPS + JWT
                    ┌──────▼───────┐
                    │ API Gateway  │ ← Spring Cloud Gateway
                    │ verify JWT   │    rate limiting, routing, CORS
                    │ route req    │
                    └──┬───┬───┬───┘
                       │   │   │
              ┌────────┼───┼───┼────────────┐
              │        │   │   │            │
         ┌────▼───┐ ┌──▼───▼───▼──┐  ┌─────▼──────┐
         │  Auth  │ │   Story    │  │  Notification│
         │Service │ │  Service   │  │   Service   │
         │(users, │ │(stories,   │  │ (in-app,    │
         │ roles, │ │ chapters,  │  │  push,      │
         │ OAuth, │ │ categories,│  │  email)     │
         │  2FA)  │ │ tags,      │  └──────┬──────┘
         └───┬────┘ │ search,    │         │
             │      │ import,    │         │
         ┌───▼────┐ │ export)    │         │
         │Social  │ └──────┬─────┘         │
         │Service │        │               │
         │(comments│  ┌────▼──────┐  ┌─────▼──────┐
         │ bookmark│  │  Audio   │  │ Analytics  │
         │ follow  │  │ Service  │  │  Service   │
         │ rating  │  │ (TTS     │  │ (dashboard,│
         │ history │  │  jobs,   │  │  reports,  │
         │ booklist│  │  worker, │  │  retention)│
         │ badges, │  │  LocalAI,│  └────────────┘
         │ reports)│  │  MinIO)  │
         └─────────┘  └────┬─────┘
                           │
                     ┌─────▼─────┐
                     │  LocalAI  │
                     │+OmniVoice │
                     └───────────┘
```

### 1.2 Service Map

| Service | Port | Responsibility | Owns DB? | Language |
|---------|------|----------------|----------|----------|
| API Gateway | 8080 | Routing, JWT validation, rate limiting, CORS, API aggregation | No | Java + Spring Cloud Gateway |
| Auth Service | 8081 | User registration, login, OAuth2, JWT, roles, 2FA, sessions | Yes (`auth_db`) | Java + Spring Boot |
| Story Service | 8082 | Story/Chapter CRUD, categories, tags, search, import/export, scheduling | Yes (`story_db`) | Java + Spring Boot |
| Social Service | 8083 | Comments, bookmarks, follows, ratings, review, reading history, booklists, badges, reports | Yes (`social_db`) | Java + Spring Boot |
| Audio Service | 8084 | TTS job queue, worker, LocalAI integration, MinIO storage, podcast RSS | Yes (`audio_db`) | Java + Spring Boot |
| Notification Service | 8085 | In-app notifications, push (Web Push), email (SMTP), templates, preferences | Yes (`notif_db`) | Java + Spring Boot |
| Analytics Service | 8086 | Dashboard stats, charts, user retention, A/B testing, report export | Read replicas | Java + Spring Boot |
| Frontend | 3000 | Next.js SSR/ISR/CSR, PWA, audio player, admin UI | No | TypeScript + Next.js |

### 1.3 Database per Service

```
postgres-auth:   users, roles, user_roles, user_sessions, oauth_accounts
postgres-story:  stories, chapters, chapter_versions, categories, story_categories, tags, story_tags, story_series, series_stories
postgres-social: comments, comment_reactions, bookmarks, bookmark_folders, story_follows, user_follows, ratings, reviews, reading_histories, chapters_read, booklists, booklist_stories, booklist_follows, badges, user_badges, reports
postgres-audio:  audio_jobs, audio_files
postgres-notif:  notifications, notification_prefs
```

**Cross-service queries** handled via API composition (Feign calls) for reads + RabbitMQ events for writes.

---

## 2. Communication Patterns

### 2.1 Synchronous (OpenFeign + Resilience4j)

```
Flow: Client → Gateway → Service A → Feign → Service B
```

| Caller | Callee | Reason |
|--------|--------|--------|
| Gateway | All | Route + auth |
| Story Service | Auth Service | Get author info for story detail |
| Story Service | Social Service | Get rating summary, follow count |
| Story Service | Audio Service | Get audio status for chapters |
| Social Service | Story Service | Validate story/chapter exists |
| Social Service | Auth Service | Validate user exists |

**Circuit breaker config:**
```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
  retry:
    configs:
      default:
        maxRetryAttempts: 3
        waitDuration: 500ms
```

### 2.2 Asynchronous (RabbitMQ)

**Events:**

| Event | Publisher | Consumer(s) | Payload |
|-------|-----------|-------------|---------|
| `story.chapter.published` | Story Service | Notification Service, Analytics Service | `{ storyId, chapterId, chapterNumber, title }` |
| `audio.job.completed` | Audio Service | Notification Service | `{ chapterId, storyId, duration, jobId }` |
| `social.comment.created` | Social Service | Notification Service, Analytics Service | `{ commentId, chapterId, storyId, authorId }` |
| `social.follow.created` | Social Service | Notification Service | `{ followerId, targetId, targetType }` |
| `social.rating.updated` | Social Service | Story Service | `{ storyId, avgRating, totalRatings }` |
| `auth.user.registered` | Auth Service | Analytics Service | `{ userId, registeredAt }` |
| `payment.completed` | Monetization Service | Auth Service, Analytics Service | `{ userId, amount, type }` |

**Outbox pattern:**
```java
// Each service writes events to its own outbox table in same DB transaction
@Transactional
public void publishChapter(Chapter chapter) {
    chapterRepository.save(chapter);
    outboxRepository.save(new OutboxEvent(
        "story.chapter.published",
        Map.of("chapterId", chapter.getId(), "storyId", chapter.getStoryId())
    ));
}

// Scheduled publisher flushes outbox → RabbitMQ
@Scheduled(fixedDelay = 2000)
public void publishOutbox() {
    List<OutboxEvent> pending = outboxRepository.findTop100ByPublishedFalseOrderByCreatedAt();
    for (OutboxEvent event : pending) {
        rabbitTemplate.convertAndSend(event.getTopic(), event.getPayload());
        event.markPublished();
    }
}
```

---

## 3. Authentication Flow

### 3.1 JWT-Based Auth

```
1. Client → POST /api/auth/login → Auth Service
2. Auth Service validates credentials → creates JWT pair:
   - access_token (RS256 signed, 15 min, contains: sub, roles, displayName)
   - refresh_token (opaque, 7 days, stored in DB)
3. Client stores access_token in memory, refresh_token in httpOnly cookie
4. Client → Gateway with Authorization: Bearer {access_token}
5. Gateway extracts JWT → verifies signature → extracts claims
6. Gateway injects X-User-Id, X-User-Roles headers → forwards to downstream services
7. Downstream services trust headers from Gateway (no re-verify)
8. When access_token expires → Client → POST /api/auth/refresh → new pair
```

### 3.2 OAuth2 Login

```
1. Client → "Login with Google" → redirect to /oauth2/authorization/google
2. Gateway → Auth Service OAuth2 flow → Google login page
3. User consents → Google sends code → Auth Service exchanges for token
4. Auth Service creates local user (if new) or links (if existing)
5. Creates JWT pair → redirect to frontend with tokens
```

---

## 4. Technology Stack

| Layer | Technology | Version | Notes |
|-------|-----------|---------|-------|
| Runtime | Java | 21 LTS | Virtual threads |
| Core Framework | Spring Boot | 3.4+ | Latest stable |
| API Gateway | Spring Cloud Gateway | 2024.x | Reactive, non-blocking |
| Auth | Spring Security | 6.x | JWT + OAuth2 |
| Database | PostgreSQL | 16 | Per service |
| Cache | Redis | 7 | Caching + rate limiting |
| Queue | RabbitMQ | 4.x | Event-driven communication |
| ORM | Spring Data JPA + Hibernate | 6.x | Flyway for migrations |
| Service Communication | OpenFeign | 13.x | With Resilience4j |
| TTS Engine | LocalAI | latest | OmniVoice model Q8_0 |
| Audio Storage | MinIO | latest | S3-compatible |
| Frontend | Next.js | 14 | App Router, TypeScript |
| Frontend State | TanStack Query + Zustand | v5 | Server + client state |
| PWA | @ducanh2912/next-pwa | latest | Service Worker + Workbox |
| Audio Player | howler.js + wavesurfer.js | latest | Basic + read-along |
| Observability | Micrometer + OpenTelemetry | latest | Tempo (traces), Loki (logs), Prometheus (metrics) |
| Container | Docker | latest | Docker Compose for dev |
| Orchestration | Kubernetes | 1.28+ | For production |

---

## 5. Project Structure

```
metruyenchu-platform/
├── gradle/
│   └── libs.versions.toml          # Version catalog
├── platform-libs/
│   ├── common-domain/               # Shared DTOs, enums, interfaces (ZERO Spring)
│   ├── common-security/             # JWT utils, security filter config
│   └── common-messaging/            # Event classes, topic names, schemas
├── services/
│   ├── gateway-service/
│   ├── auth-service/
│   ├── story-service/
│   ├── social-service/
│   ├── audio-service/
│   ├── notification-service/
│   └── analytics-service/
├── frontend/
│   ├── app/                         # Next.js App Router pages
│   ├── components/                  # Shared React components
│   ├── lib/                         # API client, utilities
│   ├── store/                       # Zustand stores
│   ├── hooks/                       # Custom hooks
│   └── public/                      # Static assets, Service Worker
├── deployment/
│   ├── docker-compose/              # Local dev compose files
│   ├── k8s/                         # Helm charts
│   └── scripts/                     # Migration, backup scripts
└── docs/
    ├── specs/                       # Spec documents
    └── api/                         # API reference
```

**Module naming convention:** `{domain}-service` (e.g., `auth-service`), artifact: `com.metruyenchu.{domain}.service`

---

## 6. Observability

### 6.1 Distributed Tracing

```
Service → Micrometer Tracing → OpenTelemetry → OTel Collector → Grafana Tempo
                                                            → Loki (logs correlation)
```

```yaml
# Common config for all services
management:
  tracing:
    sampling:
      probability: 0.1    # 10% in production, 100% in dev
    propagation:
      type: w3c           # W3C traceparent standard
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
```

### 6.2 Health Checks

```yaml
# Per service
management:
  endpoint:
    health:
      probes:
        enabled: true
      show-details: always
  health:
    readiness-state:
      enabled: true
    liveness-state:
      enabled: true
```

### 6.3 Logging

```yaml
# JSON structured logging to stdout
logging:
  pattern:
    level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]"
```

Logs shipped via Promtail → Loki → Grafana.

---

## 7. Error Handling Convention

```json
// Success
{
  "code": 200,
  "message": "success",
  "data": {},
  "pagination": { "page": 1, "size": 20, "totalElements": 100, "totalPages": 5 }
}

// Validation error
{
  "code": 400,
  "message": "Validation failed",
  "errors": [{ "field": "title", "message": "Title is required" }]
}

// Business error
{
  "code": 409,
  "message": "Slug already exists",
  "errorCode": "SLUG_CONFLICT"
}

// Server error
{
  "code": 500,
  "message": "Internal server error",
  "traceId": "abc123"
}
```

**HTTP Status Codes used:**
- `200` — Success (GET, PUT, PATCH)
- `201` — Created (POST)
- `204` — No Content (DELETE)
- `400` — Bad Request (validation)
- `401` — Unauthorized (missing/invalid JWT)
- `403` — Forbidden (insufficient role)
- `404` — Not Found
- `409` — Conflict (duplicate slug, already exists)
- `422` — Unprocessable Entity (business logic violation)
- `429` — Too Many Requests (rate limit)
- `500` — Internal Server Error

---

## 8. Development Workflow

### Prerequisites

```bash
# Install
- JDK 21 (Eclipse Temurin / OpenJDK)
- Docker Desktop with WSL2
- IntelliJ IDEA Ultimate
- Node.js 20+
- Gradle 8.7+ (or use wrapper)
```

### Local Development

```bash
# Start infrastructure
cd deployment/docker-compose
docker compose up -d postgres-auth postgres-story redis rabbitmq minio

# Start services (each in its own terminal)
cd services/auth-service && ../gradlew bootRun
cd services/story-service && ../gradlew bootRun
# ... or run all via compose

# Start frontend
cd frontend && npm run dev
```

### Testing Strategy

```yaml
# Test pyramid (by percentage)
Integration tests: 50-60%  (Testcontainers: PostgreSQL, Redis, RabbitMQ)
Unit tests: 25-35%         (JUnit 5 + Mockito)
Contract tests: 10-15%     (Spring Cloud Contract)
E2E tests: <5%             (Testcontainers full stack)
```

---

## 9. API Versioning & Naming

- **URL prefix:** `/api/v1/{resource}`
- **Naming convention:** `POST /api/v1/{resource}`, `GET /api/v1/{resource}/{id}`
- **Pagination:** `?page=1&size=20` (1-indexed, max 100)
- **Sorting:** `?sort=createdAt,desc`
- **Filtering:** `?category=ngon-tinh&status=ONGOING`
- **Partial response:** `?fields=id,title,slug`
- **ETag:** For caching, return `ETag` header, accept `If-None-Match`

---

## 10. Security Principles

1. **Defense in depth:** JWT at Gateway level, role check at service level
2. **Least privilege:** Each service has minimal DB access
3. **No shared secrets between services:** JWT passthrough with header injection
4. **All external traffic goes through Gateway:** No direct service exposure
5. **HTTPS enforced:** HSTS, secure cookies, CSP headers
6. **Input validation at boundary:** Validate at controller, sanitize at service
7. **Sensitive data never logged:** Password, tokens, payment info masked

---

## End of Architecture Overview
