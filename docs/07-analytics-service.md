# Analytics Service — MeTruyenChu

> **File:** 07-analytics-service.md
> **Part of:** metruyenchu rebuild spec series
> **Service port:** 8086

---

## 1. Tổng quan

**Analytics Service** đọc dữ liệu từ read replicas của tất cả database (auth, story, social, audio) để phục vụ dashboard, báo cáo và A/B testing mà không gây ảnh hưởng đến primary DB.

### 1.1 Công nghệ

| Thành phần | Công nghệ | Ghi chú |
|-----------|-----------|---------|
| Framework | Spring Boot 3.4+ | Java 21, virtual threads |
| Database | PostgreSQL 16 read replicas | Kết nối read-only tới 5 DB |
| Queue | RabbitMQ 4.x | Consumer các event để aggregate |
| Metrics | Micrometer + Prometheus | /actuator/prometheus |
| Caching | Redis 7 | Cache query results, TTL theo loại |
| Report Export | Apache POI + iText | CSV qua POI, PDF qua iText |
| Scheduling | Spring @Scheduled | Aggregate jobs, retention calc |

### 1.2 Kiến trúc kết nối DB

```
analytics-service
├── AuthReadDatasource  → postgres-auth-replica (read-only user)
├── StoryReadDatasource → postgres-story-replica
├── SocialReadDatasource → postgres-social-replica
├── AudioReadDatasource → postgres-audio-replica
└── AnalyticsDatasource → postgres-analytics (local: aggregates, cached results)
```

Cấu hình multiple datasource:
```yaml
spring:
  datasource:
    auth:
      jdbc-url: jdbc:postgresql://auth-replica:5432/auth_db
      username: analytics_read
      password: ${ANALYTICS_DB_PASSWORD}
      hikari:
        read-only: true
    story:
      jdbc-url: jdbc:postgresql://story-replica:5432/story_db
      ...
    social:
      jdbc-url: jdbc:postgresql://social-replica:5432/social_db
      ...
    audio:
      jdbc-url: jdbc:postgresql://audio-replica:5432/audio_db
      ...
    analytics:
      jdbc-url: jdbc:postgresql://analytics-primary:5432/analytics_db
      read-only: false
```

### 1.3 Event Consumers (RabbitMQ)

| Event | Handler | Mục đích |
|-------|---------|----------|
| `auth.user.registered` | UserRegistrationHandler | Tăng total_users, ghi vào user_registrations |
| `story.chapter.published` | ChapterPublishedHandler | Tăng total_chapters, ghi vào chapter_views |
| `social.comment.created` | CommentCreatedHandler | Ghi vào daily_comments |
| `social.rating.updated` | RatingUpdatedHandler | Cập nhật avg rating trong story_stats |
| `audio.job.completed` | AudioJobHandler | Cập nhật audio_jobs stats |
| `reading.chapter.completed` | ChapterReadHandler | Ghi vào chapters_read, update reading_time |
| `page.viewed` | PageViewHandler | Ghi vào page_views (traffic, geo, device) |

Event được nhận → aggregate vào bảng tổng hợp (`daily_stats`, `story_stats`, `user_retention`).

---

## 2. Lược đồ Database (Analytics Service)

### 2.1 Bảng aggregate

```sql
-- Thống kê theo ngày
CREATE TABLE daily_stats (
    id BIGSERIAL PRIMARY KEY,
    stat_date DATE NOT NULL,
    total_users BIGINT DEFAULT 0,
    new_users BIGINT DEFAULT 0,
    active_users BIGINT DEFAULT 0,
    total_stories BIGINT DEFAULT 0,
    new_stories BIGINT DEFAULT 0,
    total_chapters BIGINT DEFAULT 0,
    new_chapters BIGINT DEFAULT 0,
    chapters_read BIGINT DEFAULT 0,
    total_audio_jobs BIGINT DEFAULT 0,
    completed_audio_jobs BIGINT DEFAULT 0,
    total_page_views BIGINT DEFAULT 0,
    unique_visitors BIGINT DEFAULT 0,
    avg_session_duration_seconds DOUBLE PRECISION DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(stat_date)
);

-- Thống kê theo từng story
CREATE TABLE story_stats (
    id BIGSERIAL PRIMARY KEY,
    story_id BIGINT NOT NULL,
    stat_date DATE NOT NULL,
    total_views BIGINT DEFAULT 0,
    unique_readers BIGINT DEFAULT 0,
    total_read_time_seconds BIGINT DEFAULT 0,
    avg_read_time_per_chapter DOUBLE PRECISION DEFAULT 0,
    completion_count BIGINT DEFAULT 0,
    completion_rate DOUBLE PRECISION DEFAULT 0,
    follower_count BIGINT DEFAULT 0,
    avg_rating DOUBLE PRECISION DEFAULT 0,
    total_ratings INTEGER DEFAULT 0,
    total_comments INTEGER DEFAULT 0,
    UNIQUE(story_id, stat_date)
);

-- Thống kê audio
CREATE TABLE audio_stats (
    id BIGSERIAL PRIMARY KEY,
    chapter_id BIGINT NOT NULL,
    story_id BIGINT NOT NULL,
    stat_date DATE NOT NULL,
    total_plays BIGINT DEFAULT 0,
    unique_listeners BIGINT DEFAULT 0,
    total_listen_duration_seconds BIGINT DEFAULT 0,
    avg_listen_duration_seconds DOUBLE PRECISION DEFAULT 0,
    completion_count BIGINT DEFAULT 0,
    completion_rate DOUBLE PRECISION DEFAULT 0,
    drop_off_25 DOUBLE PRECISION DEFAULT 0,
    drop_off_50 DOUBLE PRECISION DEFAULT 0,
    drop_off_75 DOUBLE PRECISION DEFAULT 0,
    drop_off_90 DOUBLE PRECISION DEFAULT 0,
    UNIQUE(chapter_id, stat_date)
);

-- Retention
CREATE TABLE user_retention (
    id BIGSERIAL PRIMARY KEY,
    cohort_date DATE NOT NULL,
    cohort_size INTEGER NOT NULL,
    day_1 INTEGER DEFAULT 0,
    day_1_rate DOUBLE PRECISION DEFAULT 0,
    day_7 INTEGER DEFAULT 0,
    day_7_rate DOUBLE PRECISION DEFAULT 0,
    day_30 INTEGER DEFAULT 0,
    day_30_rate DOUBLE PRECISION DEFAULT 0,
    calculated_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(cohort_date)
);

-- Nguồn traffic
CREATE TABLE traffic_sources (
    id BIGSERIAL PRIMARY KEY,
    stat_date DATE NOT NULL,
    source VARCHAR(20) NOT NULL, -- 'direct', 'search', 'social', 'referral'
    visits BIGINT DEFAULT 0,
    unique_visitors BIGINT DEFAULT 0,
    bounce_rate DOUBLE PRECISION DEFAULT 0,
    UNIQUE(stat_date, source)
);

-- Phân bố địa lý
CREATE TABLE geo_distribution (
    id BIGSERIAL PRIMARY KEY,
    stat_date DATE NOT NULL,
    country_code VARCHAR(10) NOT NULL,
    country_name VARCHAR(100) NOT NULL,
    visitors BIGINT DEFAULT 0,
    percentage DOUBLE PRECISION DEFAULT 0,
    UNIQUE(stat_date, country_code)
);

-- Phân bố thiết bị / trình duyệt
CREATE TABLE device_stats (
    id BIGSERIAL PRIMARY KEY,
    stat_date DATE NOT NULL,
    device_type VARCHAR(20) NOT NULL, -- 'mobile', 'tablet', 'desktop'
    browser VARCHAR(50) NOT NULL,      -- 'chrome', 'firefox', 'safari', 'edge'
    os VARCHAR(50) NOT NULL,           -- 'windows', 'macos', 'ios', 'android'
    visitors BIGINT DEFAULT 0,
    UNIQUE(stat_date, device_type, browser, os)
);

-- A/B test experiments
CREATE TABLE experiments (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    status VARCHAR(20) DEFAULT 'DRAFT', -- DRAFT, RUNNING, PAUSED, COMPLETED
    target_page VARCHAR(100),
    target_element VARCHAR(100),
    traffic_percentage INTEGER DEFAULT 100,
    created_by BIGINT,
    started_at TIMESTAMP,
    ended_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE experiment_variants (
    id BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT REFERENCES experiments(id),
    name VARCHAR(100) NOT NULL,
    config JSONB NOT NULL,       -- variant configuration
    traffic_split INTEGER NOT NULL, -- percentage of experiment traffic
    is_control BOOLEAN DEFAULT FALSE
);

CREATE TABLE experiment_assignments (
    id BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL,
    variant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    assigned_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(experiment_id, user_id)
);

CREATE TABLE experiment_events (
    id BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL,
    variant_id BIGINT NOT NULL,
    user_id BIGINT,
    event_type VARCHAR(50) NOT NULL, -- 'impression', 'click', 'conversion'
    event_data JSONB,
    created_at TIMESTAMP DEFAULT NOW()
);

-- Bảng lưu kết quả aggregate cho report
CREATE TABLE report_cache (
    id BIGSERIAL PRIMARY KEY,
    report_type VARCHAR(50) NOT NULL,
    parameters JSONB NOT NULL,
    result JSONB NOT NULL,
    generated_at TIMESTAMP DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL,
    UNIQUE(report_type, parameters)
);
```

---

## 3. Scheduled Jobs

| Job | Cron | Mô tả |
|-----|------|-------|
| `aggregateDailyStats` | `0 5 0 * * *` | Tổng hợp daily_stats từ raw events hôm qua |
| `calculateRetention` | `0 30 0 * * *` | Tính retention D1/D7/D30 cho cohorts |
| `aggregateStoryStats` | `0 0 * * *` | Tổng hợp story_stats hàng giờ |
| `aggregateAudioStats` | `0 15 * * *` | Tổng hợp audio_stats hàng giờ |
| `calculateTrafficSources` | `0 0 * * *` | Tính traffic sources hàng giờ |
| `populateGeoDistribution` | `0 30 * * *` | Cập nhật geo distribution (dựa vào IP → GeoIP) |
| `populateDeviceStats` | `0 45 * * *` | Cập nhật device/browser stats |
| `refreshReportCache` | `0 */5 * * *` | Làm mới report cache hết hạn |
| `cleanupOldData` | `0 0 1 * *` | Xoá raw events > 90 ngày |

---

## 4. API Endpoints

### 4.1 Dashboard Stats

```
GET /api/v1/analytics/dashboard/summary
  ?from=2025-01-01&to=2025-01-31
Authorization: Bearer {token} (ADMIN)

Response:
{
  "code": 200,
  "data": {
    "totalUsers": 150000,
    "totalStories": 8500,
    "totalChapters": 125000,
    "totalAudioJobs": 32000,
    "activeUsersToday": 12450,
    "activeUsersThisWeek": 45200,
    "activeUsersThisMonth": 89000,
    "chaptersReadToday": 285000,
    "chaptersReadThisWeek": 1.2e6,
    "chaptersReadThisMonth": 4.5e6,
    "newUsersToday": 320,
    "newStoriesToday": 15,
    "newChaptersToday": 180,
    "avgSessionDurationSeconds": 420,
    "bounceRate": 32.5
  }
}
```

### 4.2 Charts

```
GET /api/v1/analytics/charts/views-over-time
  ?from=2025-01-01&to=2025-01-31
  &granularity=day|week|month
  &storyId=123 (optional, filter by story)

Response:
{
  "code": 200,
  "data": {
    "granularity": "day",
    "points": [
      { "date": "2025-01-01", "views": 12500, "uniqueVisitors": 3200 },
      { "date": "2025-01-02", "views": 13200, "uniqueVisitors": 3400 },
      ...
    ]
  }
}

GET /api/v1/analytics/charts/top-stories
  ?from=2025-01-01&to=2025-01-31
  &metric=views|readers|rating|completionRate
  &limit=10
  &genreId= (optional)

Response:
{
  "code": 200,
  "data": [
    { "rank": 1, "storyId": 456, "title": "Tên truyện", "slug": "ten-truyen",
      "authorName": "Tác giả", "metric": 125000, "change": 12.5 },
    ...
  ]
}

GET /api/v1/analytics/charts/genre-distribution
  ?from=2025-01-01&to=2025-01-31
  &metric=views|stories|readers
  &parentGenreId= (optional)

Response:
{
  "code": 200,
  "data": [
    { "genreId": 1, "name": "Ngôn tình", "slug": "ngon-tinh",
      "value": 450000, "percentage": 35.2, "color": "#FF6384" },
    { "genreId": 2, "name": "Kiếm hiệp", "slug": "kiem-hiep",
      "value": 280000, "percentage": 21.9, "color": "#36A2EB" },
    ...
  ]
}
```

### 4.3 User Retention

```
GET /api/v1/analytics/retention
  ?from=2025-01-01&to=2025-01-31
  &period=day|week

Response:
{
  "code": 200,
  "data": {
    "retentionType": "D1_D7_D30",
    "cohorts": [
      {
        "cohortDate": "2025-01-01",
        "cohortSize": 1200,
        "day1": { "users": 350, "rate": 29.17 },
        "day7": { "users": 180, "rate": 15.00 },
        "day30": { "users": 75, "rate": 6.25 }
      },
      ...
    ],
    "averages": {
      "day1": 28.5,
      "day7": 14.2,
      "day30": 5.8
    }
  }
}
```

Cách tính retention:
- **Cohort**: Ngày user đăng ký (cohort_date)
- **D1**: User quay lại đọc ít nhất 1 chapter trong khoảng 24h-48h sau đăng ký
- **D7**: User quay lại đọc ít nhất 1 chapter trong khoảng ngày 7-8 sau đăng ký
- **D30**: User quay lại đọc ít nhất 1 chapter trong khoảng ngày 30-31 sau đăng ký
- Công thức: `rate = (users_returned_in_window / cohort_size) * 100`

### 4.4 Traffic Sources

```
GET /api/v1/analytics/traffic-sources
  ?from=2025-01-01&to=2025-01-31

Response:
{
  "code": 200,
  "data": {
    "sources": [
      { "source": "direct", "visits": 520000, "percentage": 40.0, "bounceRate": 28.5 },
      { "source": "search", "visits": 390000, "percentage": 30.0, "bounceRate": 35.2 },
      { "source": "social", "visits": 260000, "percentage": 20.0, "bounceRate": 42.1 },
      { "source": "referral", "visits": 130000, "percentage": 10.0, "bounceRate": 31.8 }
    ],
    "trend": [
      { "date": "2025-01-01", "direct": 45.0, "search": 30.0, "social": 15.0, "referral": 10.0 },
      ...
    ]
  }
}
```

Phân loại nguồn:
- **direct**: URL gõ trực tiếp, bookmark, không có referrer
- **search**: referrer từ Google, Bing, Coccoc, Yahoo
- **social**: referrer từ Facebook, Zalo, TikTok, YouTube, Twitter
- **referral**: referrer từ các trang khác không thuộc search/social

### 4.5 Geographic Distribution

```
GET /api/v1/analytics/geo
  ?from=2025-01-01&to=2025-01-31
  &limit=20

Response:
{
  "code": 200,
  "data": [
    { "countryCode": "VN", "countryName": "Việt Nam",
      "visitors": 95000, "percentage": 76.0, "flag": "🇻🇳" },
    { "countryCode": "US", "countryName": "Hoa Kỳ",
      "visitors": 8500, "percentage": 6.8, "flag": "🇺🇸" },
    { "countryCode": "JP", "countryName": "Nhật Bản",
      "visitors": 3200, "percentage": 2.6, "flag": "🇯🇵" },
    ...
  ]
}
```

Dữ liệu geo lấy từ IP → GeoIP database (MaxMind GeoLite2), cập nhật 2 lần/tháng.

### 4.6 Device / Browser Breakdown

```
GET /api/v1/analytics/devices
  ?from=2025-01-01&to=2025-01-31

Response:
{
  "code": 200,
  "data": {
    "devices": [
      { "type": "mobile", "visitors": 82000, "percentage": 63.1 },
      { "type": "desktop", "visitors": 38000, "percentage": 29.2 },
      { "type": "tablet", "visitors": 10000, "percentage": 7.7 }
    ],
    "browsers": [
      { "name": "Chrome", "visitors": 60000, "percentage": 46.2 },
      { "name": "Safari", "visitors": 35000, "percentage": 26.9 },
      { "name": "Facebook", "visitors": 15000, "percentage": 11.5 },
      { "name": "Firefox", "visitors": 8000, "percentage": 6.2 },
      { "name": "Edge", "visitors": 5000, "percentage": 3.8 },
      { "name": "Other", "visitors": 7000, "percentage": 5.4 }
    ],
    "operatingSystems": [
      { "name": "Android", "visitors": 55000, "percentage": 42.3 },
      { "name": "iOS", "visitors": 35000, "percentage": 26.9 },
      { "name": "Windows", "visitors": 28000, "percentage": 21.5 },
      { "name": "macOS", "visitors": 8000, "percentage": 6.2 },
      { "name": "Linux", "visitors": 4000, "percentage": 3.1 }
    ]
  }
}
```

### 4.7 Story Performance

```
GET /api/v1/analytics/stories/{storyId}/performance
  ?from=2025-01-01&to=2025-01-31

Response:
{
  "code": 200,
  "data": {
    "storyId": 123,
    "overview": {
      "totalViews": 250000,
      "uniqueReaders": 45000,
      "totalReadTimeHours": 12500,
      "completionRate": 68.5,
      "avgReadTimePerChapter": 185,
      "avgChaptersPerSession": 4.2,
      "followerCount": 8500,
      "avgRating": 4.5,
      "totalRatings": 3200,
      "totalComments": 1500
    },
    "perChapter": [
      {
        "chapterNumber": 1,
        "title": "Hồi 1",
        "views": 45000,
        "uniqueReaders": 42000,
        "avgReadTimeSeconds": 240,
        "completionRate": 92.0,
        "audioPlays": 12000,
        "audioCompletionRate": 75.0
      },
      ...
    ],
    "trend": [
      { "date": "2025-01-01", "views": 8500, "uniqueReaders": 2100 },
      ...
    ],
    "readerLifetime": {
      "newReaders": 1200,
      "returningReaders": 3800,
      "churnRate": 15.2
    }
  }
}
```

### 4.8 Audio Performance

```
GET /api/v1/analytics/audio/chapters/{chapterId}/performance
  ?from=2025-01-01&to=2025-01-31

Response:
{
  "code": 200,
  "data": {
    "chapterId": 456,
    "storyId": 123,
    "overview": {
      "totalPlays": 15000,
      "uniqueListeners": 5200,
      "totalListenDurationHours": 1250,
      "avgListenDurationSeconds": 300,
      "completionRate": 62.3,
      "replayRate": 8.5
    },
    "dropOffPoints": [
      { "segment": "0-25%", "listeners": 5200, "dropOff": 0 },
      { "segment": "25-50%", "listeners": 4200, "dropOff": 19.2 },
      { "segment": "50-75%", "listeners": 3500, "dropOff": 32.7 },
      { "segment": "75-90%", "listeners": 3100, "dropOff": 40.4 },
      { "segment": "90-100%", "listeners": 2800, "dropOff": 46.2 }
    ],
    "hourlyDistribution": [
      { "hour": 0, "plays": 320 },
      { "hour": 1, "plays": 280 },
      ...
    ],
    "speedDistribution": [
      { "speed": 0.5, "users": 120 },
      { "speed": 1.0, "users": 3200 },
      { "speed": 1.25, "users": 1100 },
      { "speed": 1.5, "users": 650 },
      { "speed": 2.0, "users": 130 }
    ]
  }
}
```

### 4.9 Report Export

```
POST /api/v1/analytics/reports/export
Authorization: Bearer {token} (ADMIN)

Request:
{
  "type": "story_performance",          // dashboard, story_performance, audio_performance,
                                        // user_retention, traffic, geo, device, custom
  "format": "csv",                      // csv, pdf
  "from": "2025-01-01",
  "to": "2025-01-31",
  "filters": {
    "storyIds": [123, 456],
    "genreIds": [1, 2],
    "metrics": ["views", "readers", "completionRate"]
  },
  "schedule": null                      // null = now, hoặc cron expression
}

Response:
{
  "code": 200,
  "data": {
    "reportId": "rpt_abc123",
    "status": "completed",
    "downloadUrl": "/api/v1/analytics/reports/download/rpt_abc123",
    "expiresAt": "2025-02-07T00:00:00Z"
  }
}
```

```
GET /api/v1/analytics/reports/download/{reportId}
  → File stream (Content-Disposition: attachment)

GET /api/v1/analytics/reports
  ?page=1&size=20
  &status=completed|pending|failed

GET /api/v1/analytics/reports/{reportId}
DELETE /api/v1/analytics/reports/{reportId}
```

File CSV:
```csv
Date,Views,UniqueReaders,AvgReadTime,CompletionRate
2025-01-01,8500,2100,185,68.5
...
```

File PDF: generated với iText, có header/footer logo MeTruyenChu, bảng styled, biểu đồ (JFreeChart embedded).

### 4.10 A/B Testing Framework

#### Flag Management

```
GET /api/v1/analytics/experiments
  ?status=RUNNING&page=1&size=20

POST /api/v1/analytics/experiments
Request:
{
  "name": "Test button màu CTA",
  "description": "So sánh màu xanh vs đỏ cho nút Đọc truyện",
  "targetPage": "/truyen/{slug}",
  "targetElement": ".read-story-btn",
  "trafficPercentage": 50,
  "variants": [
    {
      "name": "control",
      "config": { "backgroundColor": "#2563EB", "text": "Đọc truyện" },
      "trafficSplit": 50,
      "isControl": true
    },
    {
      "name": "variant_a",
      "config": { "backgroundColor": "#DC2626", "text": "Đọc ngay" },
      "trafficSplit": 50,
      "isControl": false
    }
  ]
}

GET /api/v1/analytics/experiments/{id}
PUT /api/v1/analytics/experiments/{id}
DELETE /api/v1/analytics/experiments/{id}

PATCH /api/v1/analytics/experiments/{id}/status
Request:
{
  "status": "RUNNING" // DRAFT → RUNNING → PAUSED → COMPLETED
}
```

#### Experiment Assignment

```
GET /api/v1/analytics/experiments/{experimentId}/assign
  ?userId=123
  &forceVariant=variant_a  (optional, for QA)

Response:
{
  "code": 200,
  "data": {
    "experimentId": 1,
    "experimentName": "Test button màu CTA",
    "variantId": 2,
    "variantName": "variant_a",
    "config": { "backgroundColor": "#DC2626", "text": "Đọc ngay" }
  }
}
```

Thuật toán assignment: User ID hash → modulo traffic split → consistent assignment (luôn cùng variant cho cùng user).

#### Track Experiment Event

```
POST /api/v1/analytics/experiments/{experimentId}/events
Request:
{
  "variantId": 2,
  "userId": 123,
  "eventType": "click",
  "eventData": {
    "element": ".read-story-btn",
    "page": "/truyen/ten-truyen",
    "timestamp": "2025-01-15T10:30:00Z"
  }
}
```

#### Experiment Results

```
GET /api/v1/analytics/experiments/{id}/results
  ?confidenceLevel=0.95

Response:
{
  "code": 200,
  "data": {
    "experimentId": 1,
    "status": "RUNNING",
    "duration": { "days": 7, "hours": 3 },
    "samplesPerVariant": {
      "control": { "impressions": 25000, "clicks": 1250, "conversions": 380 },
      "variant_a": { "impressions": 24800, "clicks": 1488, "conversions": 520 }
    },
    "metrics": [
      {
        "metric": "click_through_rate",
        "control": { "value": 5.0, "baseline": true },
        "variant_a": { "value": 6.0, "lift": 20.0, "significant": true, "pValue": 0.003 },
        "winner": "variant_a"
      },
      {
        "metric": "conversion_rate",
        "control": { "value": 1.52, "baseline": true },
        "variant_a": { "value": 2.10, "lift": 38.2, "significant": true, "pValue": 0.001 },
        "winner": "variant_a"
      }
    ],
    "recommendation": "Variant A vượt trội với 95% confidence. Khuyên dùng cho production."
  }
}
```

Phân tích thống kê dùng **two-tailed z-test** với significance level configurable (default 0.05).

### 4.11 Custom Date Range & Generic Query

```
GET /api/v1/analytics/query
  ?from=2025-01-01&to=2025-01-31
  &granularity=day
  &metrics[]=total_views
  &metrics[]=unique_visitors
  &metrics[]=avg_session_duration
  &groupBy=story_id
  &filter=story_id IN (123,456)
  &sort=total_views DESC
  &limit=100

Response:
{
  "code": 200,
  "data": {
    "queryId": "q_abc",
    "result": [
      { "date": "2025-01-01", "storyId": 123, "totalViews": 4500, "uniqueVisitors": 1200, "avgSessionDuration": 320 },
      ...
    ],
    "total": 500,
    "cached": true,
    "executionTimeMs": 45
  }
}
```

---

## 5. Kiến trúc chi tiết

### 5.1 Package structure

```
analytics-service/
├── src/main/java/com/metruyenchu/analytics/
│   ├── AnalyticsApplication.java
│   ├── config/
│   │   ├── MultipleDatasourceConfig.java   # 5 datasource beans
│   │   ├── RabbitMQConfig.java
│   │   ├── CacheConfig.java
│   │   └── SchedulingConfig.java
│   ├── domain/
│   │   ├── model/
│   │   │   ├── DailyStats.java
│   │   │   ├── StoryStats.java
│   │   │   ├── AudioStats.java
│   │   │   ├── UserRetention.java
│   │   │   ├── TrafficSource.java
│   │   │   ├── GeoDistribution.java
│   │   │   ├── DeviceStats.java
│   │   │   ├── Experiment.java
│   │   │   ├── ExperimentVariant.java
│   │   │   ├── ExperimentAssignment.java
│   │   │   ├── ExperimentEvent.java
│   │   │   └── ReportCache.java
│   │   └── repository/
│   │       ├── DailyStatsRepository.java
│   │       ├── StoryStatsRepository.java
│   │       ├── AudioStatsRepository.java
│   │       ├── UserRetentionRepository.java
│   │       ├── TrafficSourceRepository.java
│   │       ├── GeoDistributionRepository.java
│   │       ├── DeviceStatsRepository.java
│   │       └── experiment/
│   ├── event/
│   │   ├── handler/
│   │   │   ├── UserRegistrationHandler.java
│   │   │   ├── ChapterPublishedHandler.java
│   │   │   ├── ChapterReadHandler.java
│   │   │   ├── PageViewHandler.java
│   │   │   ├── CommentCreatedHandler.java
│   │   │   └── AudioJobHandler.java
│   │   └── consumer/
│   │       └── RabbitMQConsumer.java
│   ├── job/
│   │   ├── DailyStatsAggregator.java
│   │   ├── RetentionCalculator.java
│   │   ├── StoryStatsAggregator.java
│   │   ├── AudioStatsAggregator.java
│   │   ├── TrafficAnalyzer.java
│   │   ├── GeoPopulator.java
│   │   └── DataCleanupJob.java
│   ├── service/
│   │   ├── DashboardService.java
│   │   ├── ChartService.java
│   │   ├── RetentionService.java
│   │   ├── TrafficService.java
│   │   ├── GeoService.java
│   │   ├── DeviceService.java
│   │   ├── StoryPerformanceService.java
│   │   ├── AudioPerformanceService.java
│   │   ├── ReportService.java
│   │   ├── ExperimentService.java
│   │   └── QueryService.java
│   ├── dto/
│   │   ├── request/
│   │   │   ├── DateRangeRequest.java
│   │   │   ├── ReportExportRequest.java
│   │   │   ├── ExperimentRequest.java
│   │   │   └── ExperimentEventRequest.java
│   │   └── response/
│   │       ├── DashboardSummaryResponse.java
│   │       ├── ViewsOverTimeResponse.java
│   │       ├── TopStoriesResponse.java
│   │       ├── GenreDistributionResponse.java
│   │       ├── RetentionResponse.java
│   │       ├── TrafficSourceResponse.java
│   │       ├── GeoDistributionResponse.java
│   │       ├── DeviceStatsResponse.java
│   │       ├── StoryPerformanceResponse.java
│   │       ├── AudioPerformanceResponse.java
│   │       ├── ReportExportResponse.java
│   │       ├── ExperimentResponse.java
│   │       └── ExperimentResultResponse.java
│   ├── controller/
│   │   ├── DashboardController.java
│   │   ├── ChartController.java
│   │   ├── RetentionController.java
│   │   ├── TrafficController.java
│   │   ├── GeoController.java
│   │   ├── DeviceController.java
│   │   ├── StoryPerformanceController.java
│   │   ├── AudioPerformanceController.java
│   │   ├── ReportController.java
│   │   ├── ExperimentController.java
│   │   └── QueryController.java
│   └── exporter/
│       ├── CsvExporter.java
│       └── PdfExporter.java
└── src/main/resources/
    ├── application.yml
    ├── application-dev.yml
    └── application-prod.yml
```

### 5.2 Flow xử lý event

```
1. RabbitMQ nhận event (VD: reading.chapter.completed)
2. RabbitMQConsumer.deserialize() → Event object
3. Event dispatcher → ChapterReadHandler
4. Handler:
   a. UPDATE story_stats SET total_views = total_views + 1, unique_readers = ...
      WHERE story_id = ? AND stat_date = CURRENT_DATE
   b. UPDATE daily_stats SET chapters_read = chapters_read + 1
      WHERE stat_date = CURRENT_DATE
5. Nếu có lỗi → ghi vào dead letter queue (DLQ)
6. DLQ processor retry tối đa 3 lần → alert nếu vẫn fail
```

### 5.3 Prometheus Metrics

```java
@Configuration
public class MetricsConfig {
    @Bean
    public MeterRegistry meterRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }
}
```

Các metric custom:

| Metric name | Type | Tags | Mô tả |
|-------------|------|------|-------|
| `analytics_events_received_total` | Counter | type, source | Số event nhận được |
| `analytics_events_processed_total` | Counter | type, status | Số event đã xử lý |
| `analytics_events_processing_duration_seconds` | Timer | type | Thời gian xử lý event |
| `analytics_aggregation_duration_seconds` | Timer | job | Thời gian chạy scheduled job |
| `analytics_query_execution_duration_seconds` | Timer | endpoint | Thời gian query |
| `analytics_reports_generated_total` | Counter | format | Số report đã generate |
| `analytics_experiment_events_total` | Counter | experimentId, eventType | Số experiment event |
| `analytics_cache_hit_ratio` | Gauge | - | Tỷ lệ cache hit |

---

## 6. Security & Rate Limiting

### 6.1 Phân quyền

| Endpoint | Role yêu cầu | Ghi chú |
|----------|-------------|---------|
| `/api/v1/analytics/dashboard/**` | ADMIN, MODERATOR | Dashboard tổng quan |
| `/api/v1/analytics/charts/**` | ADMIN, MODERATOR | Biểu đồ |
| `/api/v1/analytics/retention/**` | ADMIN | Retention chỉ admin |
| `/api/v1/analytics/traffic-sources` | ADMIN, MODERATOR | Traffic |
| `/api/v1/analytics/geo` | ADMIN, MODERATOR | Geo |
| `/api/v1/analytics/devices` | ADMIN, MODERATOR | Device |
| `/api/v1/analytics/stories/{id}/performance` | ADMIN, MODERATOR | Per-story |
| `/api/v1/analytics/audio/**` | ADMIN, MODERATOR | Audio stats |
| `/api/v1/analytics/reports/**` | ADMIN | Export reports |
| `/api/v1/analytics/experiments/**` | ADMIN | A/B testing |
| `/api/v1/analytics/query` | ADMIN | Generic query |

### 6.2 Rate Limiting

```yaml
analytics:
  rate-limiting:
    dashboard: 10/minute
    charts: 30/minute
    reports: 5/minute
    experiments: 60/minute
    query: 20/minute
```

---

## 7. Caching Strategy

| Cache key | TTL | Loại | Dữ liệu |
|-----------|-----|------|---------|
| `dashboard:summary:{from}:{to}` | 5 phút | Redis String | JSON dashboard summary |
| `charts:views:{from}:{to}:{granularity}` | 10 phút | Redis String | Views over time |
| `charts:top:{metric}:{from}:{to}` | 10 phút | Redis String | Top stories |
| `charts:genre:{from}:{to}` | 1 giờ | Redis String | Genre distribution |
| `retention:{from}:{to}` | 1 giờ | Redis String | Retention data |
| `traffic:{from}:{to}` | 10 phút | Redis String | Traffic sources |
| `geo:{from}:{to}` | 1 giờ | Redis String | Geo distribution |
| `devices:{from}:{to}` | 1 giờ | Redis String | Device stats |
| `story:perf:{storyId}:{from}:{to}` | 5 phút | Redis String | Story performance |
| `audio:perf:{chapterId}:{from}:{to}` | 5 phút | Redis String | Audio performance |
| `experiment:assign:{userId}:{expId}` | 24 giờ | Redis String | Experiment assignment |

Cache được invalidate khi có event mới cho cùng date range.

---

## 8. Error Handling

| Mã lỗi | HTTP Status | Ý nghĩa |
|--------|-------------|---------|
| `INVALID_DATE_RANGE` | 400 | from > to hoặc range > 365 ngày |
| `UNSUPPORTED_GRANULARITY` | 400 | Granularity không hợp lệ |
| `REPORT_TOO_LARGE` | 422 | Dữ liệu > 100k rows |
| `EXPERIMENT_NOT_FOUND` | 404 | Experiment không tồn tại |
| `EXPERIMENT_ALREADY_RUNNING` | 409 | Không thể sửa experiment đang chạy |
| `EXPERIMENT_ALREADY_COMPLETED` | 409 | Không thể chạy lại experiment đã kết thúc |
| `INSUFFICIENT_SAMPLE` | 422 | Chưa đủ sample size để có kết luận thống kê |
| `EXPORT_FAILED` | 500 | Lỗi khi generate file |

---

## 9. Testing Strategy

```java
// Test aggregate query (Testcontainers)
@Testcontainers
class DashboardServiceTest {
    @Container
    static PostgreSQLContainer<?> authDb = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("auth_db");
    @Container
    static PostgreSQLContainer<?> analyticsDb = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("analytics_db");

    @Test
    void shouldAggregateDailyStats() {
        // Insert test data vào authDb và analyticsDb
        // assert dashboard summary trả về đúng
    }
}
```

```java
// Test experiment statistics
class ExperimentStatisticsTest {
    @Test
    void shouldCalculateStatisticalSignificance() {
        ExperimentStatistics stats = new ExperimentStatistics();
        Result result = stats.calculateZTest(
            /* control */ 25000, 1250,
            /* variant */ 24800, 1488
        );
        assertThat(result.getPValue()).isLessThan(0.05);
        assertThat(result.isSignificant()).isTrue();
    }
}
```

---

## End of Analytics Service Spec
