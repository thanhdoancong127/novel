# API Gateway — MeTruyenChu

> **File:** 01-api-gateway.md
> **Phần của:** metruyenchu rebuild spec series
> **Công nghệ:** Spring Cloud Gateway 2024.x, Spring Boot 3.4+, Netty (reactive), Java 21

---

## 1. Tổng Quan

API Gateway là điểm vào duy nhất cho tất cả request từ client (Web/Mobile). Đảm nhận:

- Routing request đến các downstream service
- JWT validation (RS256) — trích xuất claims, inject headers
- Rate limiting (Bucket4j) — login 5/min, API 100/min per IP
- CORS — chỉ cho phép frontend domain
- Request/response logging — cấu trúc JSON, traceId
- Swagger UI aggregator — gộp OpenAPI từ tất cả service
- Circuit breaker per route — Resilience4j
- Request size limits — giới hạn kích thước body

**Tech stack:**

| Thành phần | Công nghệ | Ghi chú |
|-----------|-----------|---------|
| Framework | Spring Cloud Gateway 2024.x | Reactive, non-blocking, Netty |
| Java | 21 LTS | Virtual threads (không dùng cho reactive) |
| JWT | java-jwt / Nimbus JOSE + JWT | RS256 verification |
| Rate Limiting | Bucket4j | Redis-backed |
| Circuit Breaker | Spring Cloud Circuit Breaker + Resilience4j | Per route |
| API Docs | SpringDoc OpenAPI Starter Webflux | Aggregator |
| Tracing | Micrometer Tracing → OpenTelemetry | W3C traceparent |
| Metrics | Micrometer → Prometheus | |

---

## 2. Cấu Hình Gradle (`build.gradle.kts`)

```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.4.x"
    id("io.spring.dependency-management") version "1.1.x"
    id("org.springdoc.openapi-gradle-plugin") version "1.9.x"
}

dependencies {
    // Spring Cloud Gateway (reactive Netty)
    implementation("org.springframework.cloud:spring-cloud-starter-gateway")

    // JWT — RS256 verify
    implementation("com.nimbusds:nimbus-jose-jwt:9.40")

    // Rate limiting — Bucket4j + Redis
    implementation("com.bucket4j:bucket4j-core:8.10.1")
    implementation("com.bucket4j:bucket4j-redis:8.10.1")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")

    // Circuit breaker
    implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j")

    // API docs aggregator
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:2.6.x")

    // Tracing
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-zipkin")

    // Metrics
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Logging — JSON structured
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:2024.0.x")
    }
}
```

---

## 3. Cấu Hình Application (`application.yml`)

```yaml
server:
  port: 8080

spring:
  application:
    name: gateway-service
  main:
    web-application-type: reactive
  cloud:
    gateway:
      default-filters:
        - DedupeResponseHeader=Access-Control-Allow-Origin
        - name: RequestSize
          args:
            maxSize: 10MB

      routes:
        # ─── Auth Service ─────────────────────────────────────────
        - id: auth-service
          uri: lb://auth-service
          predicates:
            - Path=/api/v1/auth/**, /api/v1/users/**, /oauth2/**, /login/oauth2/**
          filters:
            - name: CircuitBreaker
              args:
                name: authServiceCB
                fallbackUri: forward:/fallback/auth
            - name: RequestRateLimiter
              args:
                redis-rate-limiter:
                  replenishRate: 100
                  burstCapacity: 200
                  keyResolver: "#{@ipKeyResolver}"

        # ─── Story Service ────────────────────────────────────────
        - id: story-service
          uri: lb://story-service
          predicates:
            - Path=/api/v1/stories/**, /api/v1/search/**
          filters:
            - name: CircuitBreaker
              args:
                name: storyServiceCB
                fallbackUri: forward:/fallback/story
            - name: JwtValidation
            - name: RequestRateLimiter
              args:
                redis-rate-limiter:
                  replenishRate: 100
                  burstCapacity: 200
                  keyResolver: "#{@userKeyResolver}"

        # ─── Social Service ───────────────────────────────────────
        - id: social-service
          uri: lb://social-service
          predicates:
            - Path=/api/v1/comments/**, /api/v1/bookmarks/**, /api/v1/follows/**,
              /api/v1/ratings/**, /api/v1/reviews/**, /api/v1/booklists/**,
              /api/v1/reports/**, /api/v1/badges/**
          filters:
            - name: CircuitBreaker
              args:
                name: socialServiceCB
                fallbackUri: forward:/fallback/social
            - name: JwtValidation
            - name: RequestRateLimiter
              args:
                redis-rate-limiter:
                  replenishRate: 100
                  burstCapacity: 200
                  keyResolver: "#{@userKeyResolver}"

        # ─── Audio Service ────────────────────────────────────────
        - id: audio-service
          uri: lb://audio-service
          predicates:
            - Path=/api/v1/audio/**
          filters:
            - name: CircuitBreaker
              args:
                name: audioServiceCB
                fallbackUri: forward:/fallback/audio
            - name: JwtValidation
            - name: RequestRateLimiter
              args:
                redis-rate-limiter:
                  replenishRate: 50
                  burstCapacity: 100
                  keyResolver: "#{@userKeyResolver}"

        # ─── Notification Service ─────────────────────────────────
        - id: notification-service
          uri: lb://notification-service
          predicates:
            - Path=/api/v1/notifications/**
          filters:
            - name: CircuitBreaker
              args:
                name: notifServiceCB
                fallbackUri: forward:/fallback/notification
            - name: JwtValidation
            - name: RequestRateLimiter
              args:
                redis-rate-limiter:
                  replenishRate: 100
                  burstCapacity: 200
                  keyResolver: "#{@userKeyResolver}"

        # ─── Analytics Service ────────────────────────────────────
        - id: analytics-service
          uri: lb://analytics-service
          predicates:
            - Path=/api/v1/analytics/**
          filters:
            - name: CircuitBreaker
              args:
                name: analyticsServiceCB
                fallbackUri: forward:/fallback/analytics
            - name: JwtValidation
            - name: RequestRateLimiter
              args:
                redis-rate-limiter:
                  replenishRate: 50
                  burstCapacity: 100
                  keyResolver: "#{@userKeyResolver}"

        # ─── Swagger routes — không qua JWT ──────────────────────
        - id: openapi
          uri: lb://gateway-service
          predicates:
            - Path=/v3/api-docs/**
          filters:
            - StripPrefix=0

      # ─── CORS ──────────────────────────────────────────────────
      globalcors:
        cors-configurations:
          '[/**]':
            allowed-origins:
              - "https://metruyenchu.com"
              - "http://localhost:3000"
            allowed-methods:
              - GET
              - POST
              - PUT
              - DELETE
              - PATCH
              - OPTIONS
            allowed-headers:
              - Authorization
              - Content-Type
              - X-Requested-With
              - Accept
              - Origin
              - X-User-Id
              - X-User-Roles
            exposed-headers:
              - X-Trace-Id
              - X-RateLimit-Remaining
              - X-RateLimit-Reset
            allow-credentials: true
            max-age: 3600

# ─── Redis ──────────────────────────────────────────────────────
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379
      timeout: 2000ms
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 4

# ─── Resilience4j Circuit Breaker ──────────────────────────────
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowType: COUNT_BASED
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
        minimumNumberOfCalls: 5
        automaticTransitionFromOpenToHalfOpenEnabled: true
        recordExceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException
          - org.springframework.cloud.gateway.support.NotFoundException
    instances:
      authServiceCB:
        baseConfig: default
      storyServiceCB:
        baseConfig: default
      socialServiceCB:
        baseConfig: default
      audioServiceCB:
        baseConfig: default
      notifServiceCB:
        baseConfig: default
      analyticsServiceCB:
        baseConfig: default

# ─── Actuator ────────────────────────────────────────────────────
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus,circuitbreakers,ratelimiters
  endpoint:
    health:
      probes:
        enabled: true
      show-details: always
  tracing:
    sampling:
      probability: 1.0
    propagation:
      type: w3c
  metrics:
    tags:
      application: ${spring.application.name}
    export:
      prometheus:
        enabled: true

# ─── Logging ─────────────────────────────────────────────────────
logging:
  level:
    com.metruyenchu.gateway: DEBUG
    org.springframework.cloud.gateway: INFO
    reactor.netty.http.client: INFO
  pattern:
    level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]"

springdoc:
  swagger-ui:
    path: /swagger-ui.html
    urls:
      - name: auth-service
        url: /v3/api-docs/auth-service
      - name: story-service
        url: /v3/api-docs/story-service
      - name: social-service
        url: /v3/api-docs/social-service
      - name: audio-service
        url: /v3/api-docs/audio-service
      - name: notification-service
        url: /v3/api-docs/notification-service
      - name: analytics-service
        url: /v3/api-docs/analytics-service
  cache:
    disabled: true
```

---

## 4. JWT Validation Filter

### 4.1 GatewayFilter — `JwtValidationGatewayFilterFactory`

Đây là custom filter được áp dụng cho các route cần xác thực JWT. Filter thực hiện:

1. Trích xuất `Authorization: Bearer <token>` từ header
2. Verify chữ ký RS256 bằng public key (lấy từ Auth Service — cached)
3. Parse claims: `sub` (userId), `roles`
4. Inject headers `X-User-Id`, `X-User-Roles` vào request trước khi forward
5. Nếu JWT hết hạn hoặc không hợp lệ → trả về 401

```java
@Component
public class JwtValidationGatewayFilterFactory
        extends AbstractGatewayFilterFactory<JwtValidationGatewayFilterFactory.Config> {

    private final JwtValidator jwtValidator;

    public JwtValidationGatewayFilterFactory(JwtValidator jwtValidator) {
        super(Config.class);
        this.jwtValidator = jwtValidator;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String authHeader = exchange.getRequest().getHeaders()
                    .getFirst(HttpHeaders.AUTHORIZATION);

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return onError(exchange, "Missing or invalid Authorization header", 401);
            }

            String token = authHeader.substring(7);

            return jwtValidator.validate(token)
                    .flatMap(claims -> {
                        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                                .header("X-User-Id", claims.getSubject())
                                .header("X-User-Roles",
                                        String.join(",", claims.getRoles()))
                                .build();
                        ServerWebExchange mutatedExchange = exchange.mutate()
                                .request(mutatedRequest)
                                .build();
                        return chain.filter(mutatedExchange);
                    })
                    .onErrorResume(e -> onError(exchange, e.getMessage(), 401));
        };
    }

    private Mono<Void> onError(ServerWebExchange exchange, String msg, int status) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":" + status + ",\"message\":\"" + msg + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    @Data
    public static class Config {
        private boolean required = true;
    }
}
```

### 4.2 JWT Validator — `JwtValidator`

```java
@Component
public class JwtValidator {

    private final RedisTemplate<String, String> redis;
    private final RSAPublicKey publicKey;

    // Public key được lấy từ Auth Service qua API, cached trong Redis (TTL: 1h)
    private static final String PUBLIC_KEY_CACHE_KEY = "gateway:jwt:public-key";

    public JwtValidator(RedisTemplate<String, String> redis) {
        this.redis = redis;
        this.publicKey = loadPublicKey();
    }

    public Mono<JwtClaims> validate(String token) {
        return Mono.fromCallable(() -> {
            SignedJWT signedJWT = SignedJWT.parse(token);
            JWSVerifier verifier = new RSASSAVerifier(publicKey);

            if (!signedJWT.verify(verifier)) {
                throw new SecurityException("Invalid JWT signature");
            }

            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

            if (new Date().after(claims.getExpirationTime())) {
                throw new SecurityException("JWT expired");
            }

            return new JwtClaims(
                    claims.getSubject(),
                    claims.getStringListClaim("roles"),
                    claims.getStringClaim("displayName")
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private RSAPublicKey loadPublicKey() {
        // 1. Check Redis cache
        // 2. Nếu miss → gọi Auth Service GET /api/v1/auth/jwt-public-key
        // 3. Parse PEM → RSAPublicKey
        // 4. Cache trong Redis 1h
        String pem = redis.opsForValue().get(PUBLIC_KEY_CACHE_KEY);
        if (pem == null) {
            pem = fetchPublicKeyFromAuthService();
            redis.opsForValue().set(PUBLIC_KEY_CACHE_KEY, pem, 1, TimeUnit.HOURS);
        }
        return PemUtils.parsePublicKey(pem);
    }
}

@Value
public class JwtClaims {
    String userId;
    List<String> roles;
    String displayName;
}
```

### 4.3 Public Key Refresh Schedule

```java
@Component
public class PublicKeyRefresher {

    private final JwtValidator jwtValidator;

    @Scheduled(fixedDelay = 30_000) // 30 giây check 1 lần
    public void refreshPublicKey() {
        jwtValidator.refreshPublicKey();
    }
}
```

### 4.4 Public Key Endpoint (Auth Service cung cấp)

**Auth Service** expose endpoint để Gateway lấy public key:

```http
GET /api/v1/auth/jwt-public-key
```

**Response:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "publicKey": "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQ...\n-----END PUBLIC KEY-----",
    "algorithm": "RS256",
    "keyId": "2024-06-v1"
  }
}
```

---

## 5. Rate Limiting — Bucket4j + Redis

Sử dụng Bucket4j với Redis backend để rate limiting phân tán.

### 5.1 Key Resolvers

```java
// Rate limit theo IP — áp dụng cho login endpoint
@Component("ipKeyResolver")
public class IpKeyResolver implements KeyResolver {
    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        String ip = exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
        return Mono.just("ip:" + ip);
    }
}

// Rate limit theo userId — áp dụng cho API chung
@Component("userKeyResolver")
public class UserKeyResolver implements KeyResolver {
    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        String userId = exchange.getRequest().getHeaders()
                .getFirst("X-User-Id");
        if (userId == null) {
            // Guest users rate limit theo IP
            String ip = exchange.getRequest().getRemoteAddress() != null
                    ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                    : "unknown";
            return Mono.just("guest:" + ip);
        }
        return Mono.just("user:" + userId);
    }
}
```

### 5.2 Bucket4j Configuration

```java
@Configuration
public class RateLimitingConfig {

    @Bean
    public RedisPoolConfig redisPoolConfig() {
        return RedisPoolConfig.builder()
                .connectionPoolSize(10)
                .build();
    }

    @Bean
    public ProxyManager<String> proxyManager(RedisPoolConfig poolConfig) {
        RedisClient client = RedisClient.create("redis://localhost:6379");
        StatefulRedisConnection<String, String> connection = client.connect();
        return new RedisBasedProxyManager<>(connection, poolConfig);
    }
}
```

### 5.3 Rate Limit Tiers

| Endpoint | Rate | Burst | Key | Áp dụng |
|----------|------|-------|-----|---------|
| `POST /api/v1/auth/login` | 5 requests/min | 10 | IP | Chống brute force |
| `POST /api/v1/auth/register` | 3 requests/min | 5 | IP | Chống spam register |
| `POST /api/v1/auth/forgot-password` | 3 requests/min | 5 | IP | Chống spam email |
| `POST /api/v1/auth/refresh` | 10 requests/min | 20 | IP | Refresh token |
| `GET /api/v1/auth/**` | 30 requests/min | 50 | IP | Auth endpoints chung |
| `GET /api/v1/stories` | 100 requests/min | 200 | User/IP | Browse stories |
| `POST/PUT/DELETE /api/v1/**` | 30 requests/min | 60 | User/IP | Write operations |
| Public routes | 60 requests/min | 120 | IP | Guest browsing |

### 5.4 Rate Limit Headers

Mỗi response bao gồm rate limit headers:

```
X-RateLimit-Limit: 5
X-RateLimit-Remaining: 3
X-RateLimit-Reset: 1696000000
```

Khi vượt quá → **429 Too Many Requests**:

```json
{
  "code": 429,
  "message": "Too many requests. Please try again later.",
  "retryAfter": 45
}
```

---

## 6. CORS Configuration

```java
@Configuration
public class CorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(
                "https://metruyenchu.com",
                "https://www.metruyenchu.com",
                "http://localhost:3000"
        ));
        config.setAllowedMethods(Arrays.asList(
                "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"
        ));
        config.setAllowedHeaders(Arrays.asList(
                "Authorization", "Content-Type", "X-Requested-With",
                "Accept", "Origin"
        ));
        config.setExposedHeaders(Arrays.asList(
                "X-Trace-Id", "X-RateLimit-Remaining", "X-RateLimit-Reset"
        ));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsWebFilter(source);
    }
}
```

**Nguyên tắc:**
- `allowed-origins` = danh sách trắng, không dùng `*`
- `allow-credentials: true` → cho phép gửi cookie (refresh_token httpOnly)
- `max-age: 3600` → preflight cache 1 giờ
- Header `Authorization` luôn được allow

---

## 7. Request/Response Logging Filter

```java
@Component
@Slf4j
public class LoggingFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String traceId = exchange.getAttribute("traceId");
        if (traceId == null) traceId = UUID.randomUUID().toString();

        String path = request.getURI().getRawPath();
        String method = request.getMethod().name();
        String query = request.getURI().getRawQuery();
        String ip = request.getRemoteAddress() != null
                ? request.getRemoteAddress().getAddress().getHostAddress() : "unknown";
        String userAgent = request.getHeaders().getFirst("User-Agent");
        String userId = request.getHeaders().getFirst("X-User-Id");

        log.info("[IN] traceId={} method={} path={} query={} ip={} userId={} ua={}",
                traceId, method, path, query, ip, userId, userAgent);

        long start = System.currentTimeMillis();

        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            ServerHttpResponse response = exchange.getResponse();
            long duration = System.currentTimeMillis() - start;
            int status = response.getStatusCode() != null
                    ? response.getStatusCode().value() : 0;

            // Thêm X-Trace-Id vào response header
            response.getHeaders().add("X-Trace-Id", traceId);

            log.info("[OUT] traceId={} status={} duration={}ms path={} {} {}",
                    traceId, status, duration, method, path, query);
        }));
    }

    @Override
    public int getOrder() {
        return -1; // Chạy đầu tiên
    }
}
```

### 7.1 Structured Logging — `logback-spring.xml`

```xml
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeContext>false</includeContext>
            <fieldNames>
                <timestamp>@timestamp</timestamp>
                <message>message</message>
                <level>level</level>
                <logger>logger</logger>
                <thread>thread</thread>
            </fieldNames>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="JSON"/>
    </root>
</configuration>
```

---

## 8. Swagger UI Aggregator

### 8.1 Cấu hình SpringDoc

```java
@Configuration
public class OpenApiConfig {

    @Bean
    public GroupedOpenApi authApi() {
        return GroupedOpenApi.builder()
                .group("auth-service")
                .pathsToMatch("/api/v1/auth/**", "/api/v1/users/**")
                .build();
    }

    @Bean
    public GroupedOpenApi storyApi() {
        return GroupedOpenApi.builder()
                .group("story-service")
                .pathsToMatch("/api/v1/stories/**", "/api/v1/search/**")
                .build();
    }

    @Bean
    public GroupedOpenApi socialApi() {
        return GroupedOpenApi.builder()
                .group("social-service")
                .pathsToMatch("/api/v1/comments/**", "/api/v1/bookmarks/**",
                        "/api/v1/follows/**", "/api/v1/ratings/**",
                        "/api/v1/reviews/**", "/api/v1/booklists/**",
                        "/api/v1/reports/**", "/api/v1/badges/**")
                .build();
    }

    @Bean
    public GroupedOpenApi audioApi() {
        return GroupedOpenApi.builder()
                .group("audio-service")
                .pathsToMatch("/api/v1/audio/**")
                .build();
    }

    @Bean
    public GroupedOpenApi notifApi() {
        return GroupedOpenApi.builder()
                .group("notification-service")
                .pathsToMatch("/api/v1/notifications/**")
                .build();
    }

    @Bean
    public GroupedOpenApi analyticsApi() {
        return GroupedOpenApi.builder()
                .group("analytics-service")
                .pathsToMatch("/api/v1/analytics/**")
                .build();
    }

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("MeTruyenChu API")
                        .description("API Gateway aggregator cho tất cả services")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("MeTruyenChu Team")
                                .email("dev@metruyenchu.com")))
                .addSecurityItem(new SecurityRequirement().addList("Bearer"))
                .components(new Components()
                        .addSecuritySchemes("Bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
```

### 8.2 Swagger UI URL

```
https://api.metruyenchu.com/swagger-ui.html
```

Giao diện Swagger UI hiển thị dropdown để chọn service. Mỗi service hiển thị endpoints riêng với đầy đủ request/response schema.

---

## 9. Circuit Breaker Per Route

### 9.1 Configuration

```yaml
resilience4j:
  circuitbreaker:
    instances:
      authServiceCB:
        slidingWindowType: COUNT_BASED
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
        minimumNumberOfCalls: 5
        automaticTransitionFromOpenToHalfOpenEnabled: true
        recordExceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException
          - org.springframework.web.reactive.function.client.WebClientRequestException
      storyServiceCB:
        baseConfig: default
        waitDurationInOpenState: 15s
      socialServiceCB:
        baseConfig: default
      audioServiceCB:
        baseConfig: default
        waitDurationInOpenState: 60s  # Audio gen lâu hơn
      notifServiceCB:
        baseConfig: default
      analyticsServiceCB:
        baseConfig: default
```

### 9.2 Fallback Responses

Khi circuit breaker mở (OPEN), Gateway trả về fallback response:

```java
@Component
public class FallbackController {

    @GetMapping("/fallback/auth")
    public Mono<Map<String, Object>> authFallback() {
        return Mono.just(Map.of(
                "code", 503,
                "message", "Auth service temporarily unavailable. Please try again later.",
                "errorCode", "SERVICE_UNAVAILABLE"
        ));
    }

    @GetMapping("/fallback/story")
    public Mono<Map<String, Object>> storyFallback() {
        return Mono.just(Map.of(
                "code", 503,
                "message", "Story service temporarily unavailable.",
                "errorCode", "SERVICE_UNAVAILABLE"
        ));
    }

    @GetMapping("/fallback/social")
    public Mono<Map<String, Object>> socialFallback() { /* ... */ }

    @GetMapping("/fallback/audio")
    public Mono<Map<String, Object>> audioFallback() { /* ... */ }

    @GetMapping("/fallback/notification")
    public Mono<Map<String, Object>> notifFallback() { /* ... */ }

    @GetMapping("/fallback/analytics")
    public Mono<Map<String, Object>> analyticsFallback() { /* ... */ }
}
```

---

## 10. Request Size Limits

### 10.1 Global Limit

```yaml
spring:
  cloud:
    gateway:
      default-filters:
        - name: RequestSize
          args:
            maxSize: 10MB
```

### 10.2 Per-Route Limits

| Route | Max Body | Ghi chú |
|-------|----------|---------|
| Auth Service | 1MB | Login, register payload nhỏ |
| Story Service | 10MB | Import chapter (EPUB, DOCX) |
| Social Service | 5MB | Comment có thể dài, kèm images |
| Audio Service | 50MB | Upload audio files |
| Notification | 1MB | Payload nhỏ |
| Analytics | 1MB | Query params |

### 10.3 Cấu hình WebFlux

```java
@Bean
public WebFluxConfigurer webFluxConfigurer() {
    return new WebFluxConfigurer() {
        @Override
        public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
            configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024); // 16MB
        }
    };
}
```

---

## 11. Security Headers

```java
@Component
public class SecurityHeadersFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().add("X-Content-Type-Options", "nosniff");
        response.getHeaders().add("X-Frame-Options", "DENY");
        response.getHeaders().add("X-XSS-Protection", "0");
        response.getHeaders().add("Referrer-Policy", "strict-origin-when-cross-origin");
        response.getHeaders().add("Permissions-Policy",
                "camera=(), microphone=(), geolocation=()");
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -2;
    }
}
```

---

## 12. Danh Sách Route Hoàn Chỉnh

| # | Route ID | Path Prefix | Upstream | Auth Required | Rate Limit | CB Name |
|---|----------|-------------|----------|---------------|------------|---------|
| 1 | auth-service | `/api/v1/auth/**` | auth-service | ❌ (login) | IP: 5/min | authServiceCB |
| 2 | auth-service | `/oauth2/**` | auth-service | ❌ | IP: 10/min | authServiceCB |
| 3 | auth-service | `/login/oauth2/**` | auth-service | ❌ | IP: 10/min | authServiceCB |
| 4 | auth-service | `/api/v1/users/**` | auth-service | ✅ JWT | User: 30/min | authServiceCB |
| 5 | story-service | `/api/v1/stories/**` | story-service | ✅ JWT | User: 100/min | storyServiceCB |
| 6 | story-service | `/api/v1/search/**` | story-service | ❌ | IP: 60/min | storyServiceCB |
| 7 | social-service | `/api/v1/comments/**` | social-service | ✅ JWT | User: 30/min | socialServiceCB |
| 8 | social-service | `/api/v1/bookmarks/**` | social-service | ✅ JWT | User: 30/min | socialServiceCB |
| 9 | social-service | `/api/v1/follows/**` | social-service | ✅ JWT | User: 30/min | socialServiceCB |
| 10 | social-service | `/api/v1/ratings/**` | social-service | ✅ JWT | User: 30/min | socialServiceCB |
| 11 | social-service | `/api/v1/reviews/**` | social-service | ✅ JWT | User: 30/min | socialServiceCB |
| 12 | social-service | `/api/v1/booklists/**` | social-service | ✅ JWT | User: 30/min | socialServiceCB |
| 13 | social-service | `/api/v1/reports/**` | social-service | ✅ JWT | User: 10/min | socialServiceCB |
| 14 | audio-service | `/api/v1/audio/**` | audio-service | ✅ JWT | User: 50/min | audioServiceCB |
| 15 | notification-service | `/api/v1/notifications/**` | notif-service | ✅ JWT | User: 60/min | notifServiceCB |
| 16 | analytics-service | `/api/v1/analytics/**` | analytics-service | ✅ JWT | User: 30/min | analyticsServiceCB |

---

## 13. Xử Lý Lỗi Tập Trung

```java
@Component
@Order(-1)
public class GlobalErrorWebExceptionHandler
        extends AbstractErrorWebExceptionHandler {

    public GlobalErrorWebExceptionHandler(
            ErrorAttributes errorAttributes,
            WebApplicationContext webApplicationContext,
            ServerCodecConfigurer serverCodecConfigurer) {
        super(errorAttributes, webApplicationContext);
        setMessageWriters(serverCodecConfigurer.getWriters());
    }

    @Override
    protected RouterFunction<ServerResponse> getRoutingFunction(
            ErrorAttributes errorAttributes) {
        return RouterFunctions.route(
                RequestPredicates.all(), this::renderErrorResponse);
    }

    private Mono<ServerResponse> renderErrorResponse(ServerRequest request) {
        Throwable error = getErrorAttributes(request, ErrorAttributeOptions.defaults())
                .get("error");
        // Map exception → HTTP status + JSON body
        int status = 500;
        String message = "Internal server error";

        if (error instanceof ResponseStatusException ex) {
            status = ex.getStatusCode().value();
            message = ex.getReason();
        } else if (error instanceof TimeoutException) {
            status = 504;
            message = "Upstream service timeout";
        }

        return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(Map.of(
                        "code", status,
                        "message", message,
                        "traceId", request.exchange().getAttribute("traceId")
                )));
    }
}
```

---

## 14. Cấu Trúc Project

```
services/gateway-service/
├── build.gradle.kts
├── src/
│   └── main/
│       ├── java/com/metruyenchu/gateway/
│       │   ├── GatewayApplication.java
│       │   ├── config/
│       │   │   ├── CorsConfig.java
│       │   │   ├── RateLimitingConfig.java
│       │   │   ├── OpenApiConfig.java
│       │   │   └── RedisConfig.java
│       │   ├── filter/
│       │   │   ├── JwtValidationGatewayFilterFactory.java
│       │   │   ├── LoggingFilter.java
│       │   │   └── SecurityHeadersFilter.java
│       │   ├── security/
│       │   │   ├── JwtValidator.java
│       │   │   ├── JwtClaims.java
│       │   │   └── PemUtils.java
│       │   ├── ratelimit/
│       │   │   ├── IpKeyResolver.java
│       │   │   └── UserKeyResolver.java
│       │   ├── controller/
│       │   │   └── FallbackController.java
│       │   └── handler/
│       │       └── GlobalErrorWebExceptionHandler.java
│       └── resources/
│           ├── application.yml
│           ├── application-dev.yml
│           └── logback-spring.xml
```

---

## 15. Biến Môi Trường

| Variable | Default | Mô tả |
|----------|---------|-------|
| `SERVER_PORT` | 8080 | Cổng Gateway |
| `REDIS_HOST` | localhost | Redis host |
| `REDIS_PORT` | 6379 | Redis port |
| `AUTH_SERVICE_URL` | http://auth-service:8081 | Auth service URI |
| `STORY_SERVICE_URL` | http://story-service:8082 | Story service URI |
| `SOCIAL_SERVICE_URL` | http://social-service:8083 | Social service URI |
| `AUDIO_SERVICE_URL` | http://audio-service:8084 | Audio service URI |
| `NOTIF_SERVICE_URL` | http://notification-service:8085 | Notification service URI |
| `ANALYTICS_SERVICE_URL` | http://analytics-service:8086 | Analytics service URI |
| `FRONTEND_URL` | http://localhost:3000 | Frontend URL (CORS) |
| `JWT_PUBLIC_KEY` | — | RSA public key PEM |
| `RATE_LIMIT_LOGIN` | 5 | Login rate limit per min |
| `RATE_LIMIT_API` | 100 | API rate limit per min |
| `LOG_LEVEL_GATEWAY` | INFO | Log level |
| `TRACING_SAMPLING` | 1.0 | Tracing sampling rate |

---

## End of API Gateway Spec
