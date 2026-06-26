# Story Service — MeTruyenChu

> **File:** 03-story-service.md
> **Part of:** metruyenchu rebuild spec series
> **Version:** 1.0

---

## 1. Tổng quan

**Story Service** quản lý toàn bộ nội dung truyện trên nền tảng — từ danh sách truyện, chương, thể loại, tags, series cho đến tìm kiếm, import/export.

| Thuộc tính | Giá trị |
|-----------|---------|
| Service name | `story-service` |
| Port | 8082 |
| Database | PostgreSQL 16 — `story_db` |
| ORM | Spring Data JPA + Hibernate 6 |
| Migration | Flyway |
| Message Queue | RabbitMQ (publisher) |
| Service Comms | OpenFeign + Resilience4j |
| Cache | Redis 7 (story detail, category tree) |
| Search | PostgreSQL tsvector + pg_trgm |

---

## 2. Entities & SQL Schema

### 2.1 stories

```sql
CREATE TABLE stories (
    id              BIGSERIAL       PRIMARY KEY,
    author_id       BIGINT          NOT NULL,
    title           VARCHAR(255)    NOT NULL,
    slug            VARCHAR(300)    NOT NULL UNIQUE,
    description     TEXT,
    cover           VARCHAR(500),
    status          VARCHAR(20)     NOT NULL DEFAULT 'ONGOING'
                        CHECK (status IN ('ONGOING','COMPLETED','DROPPED','PAUSED')),
    content_warning VARCHAR(500),
    total_chapters  INT             NOT NULL DEFAULT 0,
    total_views     BIGINT          NOT NULL DEFAULT 0,
    total_words     BIGINT          NOT NULL DEFAULT 0,
    avg_rating      DECIMAL(2,1)    NOT NULL DEFAULT 0.0,
    rating_count    INT             NOT NULL DEFAULT 0,
    follow_count    INT             NOT NULL DEFAULT 0,
    is_premium      BOOLEAN         NOT NULL DEFAULT FALSE,
    is_verified     BOOLEAN         NOT NULL DEFAULT FALSE,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_stories_slug ON stories (slug);
CREATE INDEX idx_stories_author ON stories (author_id);
CREATE INDEX idx_stories_status ON stories (status);
CREATE INDEX idx_stories_published_at ON stories (published_at DESC);
CREATE INDEX idx_stories_avg_rating ON stories (avg_rating DESC);
CREATE INDEX idx_stories_total_views ON stories (total_views DESC);
CREATE INDEX idx_stories_created_at ON stories (created_at DESC);
```

### 2.2 chapters

```sql
CREATE TABLE chapters (
    id              BIGSERIAL       PRIMARY KEY,
    story_id        BIGINT          NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    title           VARCHAR(255)    NOT NULL,
    chapter_number  DECIMAL(6,1)    NOT NULL,
    content         TEXT            NOT NULL,
    word_count      INT             NOT NULL DEFAULT 0,
    status          VARCHAR(20)     NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN ('DRAFT','PUBLISHED','HIDDEN')),
    is_free         BOOLEAN         NOT NULL DEFAULT TRUE,
    scheduled_at    TIMESTAMPTZ,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_chapter_story_number UNIQUE (story_id, chapter_number)
);

CREATE INDEX idx_chapters_story ON chapters (story_id, chapter_number);
CREATE INDEX idx_chapters_status ON chapters (story_id, status);
CREATE INDEX idx_chapters_scheduled ON chapters (scheduled_at)
    WHERE scheduled_at IS NOT NULL AND status = 'DRAFT';
```

### 2.3 chapter_versions

```sql
CREATE TABLE chapter_versions (
    id              BIGSERIAL       PRIMARY KEY,
    chapter_id      BIGINT          NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
    version         INT             NOT NULL,
    title           VARCHAR(255)    NOT NULL,
    content         TEXT            NOT NULL,
    word_count      INT             NOT NULL DEFAULT 0,
    change_summary  VARCHAR(500),
    created_by      BIGINT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_chapter_version UNIQUE (chapter_id, version)
);

CREATE INDEX idx_chapter_versions_chapter ON chapter_versions (chapter_id, version DESC);
```

### 2.4 categories

```sql
CREATE TABLE categories (
    id              BIGSERIAL       PRIMARY KEY,
    name            VARCHAR(100)    NOT NULL,
    slug            VARCHAR(120)    NOT NULL UNIQUE,
    description     VARCHAR(500),
    parent_id       BIGINT          REFERENCES categories(id) ON DELETE SET NULL,
    sort_order      INT             NOT NULL DEFAULT 0,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_categories_parent ON categories (parent_id);
CREATE INDEX idx_categories_sort ON categories (sort_order);
```

### 2.5 story_categories

```sql
CREATE TABLE story_categories (
    story_id        BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    category_id     BIGINT NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    PRIMARY KEY (story_id, category_id)
);
```

### 2.6 tags

```sql
CREATE TABLE tags (
    id              BIGSERIAL       PRIMARY KEY,
    name            VARCHAR(50)     NOT NULL UNIQUE,
    slug            VARCHAR(60)     NOT NULL UNIQUE,
    usage_count     INT             NOT NULL DEFAULT 0,
    is_trending     BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tags_trending ON tags (usage_count DESC) WHERE is_trending = TRUE;
CREATE INDEX idx_tags_name_trgm ON tags USING GIN (name gin_trgm_ops);
```

### 2.7 story_tags

```sql
CREATE TABLE story_tags (
    story_id        BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    tag_id          BIGINT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (story_id, tag_id)
);
```

### 2.8 story_series

```sql
CREATE TABLE story_series (
    id              BIGSERIAL       PRIMARY KEY,
    name            VARCHAR(255)    NOT NULL,
    slug            VARCHAR(300)    NOT NULL UNIQUE,
    description     TEXT,
    cover           VARCHAR(500),
    author_id       BIGINT          NOT NULL,
    is_complete     BOOLEAN         NOT NULL DEFAULT FALSE,
    sort_order      INT             NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_story_series_author ON story_series (author_id);
```

### 2.9 series_stories

```sql
CREATE TABLE series_stories (
    series_id       BIGINT NOT NULL REFERENCES story_series(id) ON DELETE CASCADE,
    story_id        BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    story_order     INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (series_id, story_id)
);
```

### 2.10 outbox_events

```sql
CREATE TABLE outbox_events (
    id              BIGSERIAL       PRIMARY KEY,
    topic           VARCHAR(100)    NOT NULL,
    payload         JSONB           NOT NULL,
    headers         JSONB,
    published       BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at)
    WHERE published = FALSE;
```

---

## 3. Entities (JPA)

### 3.1 Story

```java
@Entity
@Table(name = "stories")
public class Story {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long authorId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, unique = true, length = 300)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 500)
    private String cover;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StoryStatus status;

    @Column(length = 500)
    private String contentWarning;

    @Column(nullable = false)
    private Integer totalChapters = 0;

    @Column(nullable = false)
    private Long totalViews = 0L;

    @Column(nullable = false)
    private Long totalWords = 0L;

    @Column(nullable = false, precision = 2, scale = 1)
    private BigDecimal avgRating = BigDecimal.ZERO;

    @Column(nullable = false)
    private Integer ratingCount = 0;

    @Column(nullable = false)
    private Integer followCount = 0;

    @Column(nullable = false)
    private Boolean isPremium = false;

    @Column(nullable = false)
    private Boolean isVerified = false;

    private Instant publishedAt;
    private Instant createdAt;
    private Instant updatedAt;

    @ManyToMany
    @JoinTable(name = "story_categories",
        joinColumns = @JoinColumn(name = "story_id"),
        inverseJoinColumns = @JoinColumn(name = "category_id"))
    private Set<Category> categories = new HashSet<>();

    @ManyToMany
    @JoinTable(name = "story_tags",
        joinColumns = @JoinColumn(name = "story_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new HashSet<>();

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}

public enum StoryStatus {
    ONGOING, COMPLETED, DROPPED, PAUSED
}
```

### 3.2 Chapter

```java
@Entity
@Table(name = "chapters")
public class Chapter {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storyId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, precision = 6, scale = 1)
    private BigDecimal chapterNumber;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private Integer wordCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChapterStatus status;

    @Column(nullable = false)
    private Boolean isFree = true;

    private Instant scheduledAt;
    private Instant publishedAt;
    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        wordCount = countWords(content);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
        wordCount = countWords(content);
    }

    private int countWords(String content) {
        if (content == null || content.isBlank()) return 0;
        String cleaned = content.replaceAll("<[^>]*>", "")
                                .replaceAll("\\s+", " ")
                                .trim();
        return cleaned.isEmpty() ? 0 : cleaned.split(" ").length;
    }
}

public enum ChapterStatus {
    DRAFT, PUBLISHED, HIDDEN
}
```

### 3.3 ChapterVersion

```java
@Entity
@Table(name = "chapter_versions")
public class ChapterVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long chapterId;

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private Integer wordCount;

    @Column(length = 500)
    private String changeSummary;

    private Long createdBy;
    private Instant createdAt;
}
```

### 3.4 Category

```java
@Entity
@Table(name = "categories")
public class Category {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 120)
    private String slug;

    @Column(length = 500)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL)
    @OrderBy("sortOrder ASC")
    private List<Category> children = new ArrayList<>();

    @Column(nullable = false)
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private Boolean isActive = true;

    private Instant createdAt;
    private Instant updatedAt;
}
```

### 3.5 Tag

```java
@Entity
@Table(name = "tags")
public class Tag {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(nullable = false, unique = true, length = 60)
    private String slug;

    @Column(nullable = false)
    private Integer usageCount = 0;

    @Column(nullable = false)
    private Boolean isTrending = false;

    private Instant createdAt;
}
```

### 3.6 StorySeries

```java
@Entity
@Table(name = "story_series")
public class StorySeries {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, unique = true, length = 300)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 500)
    private String cover;

    @Column(nullable = false)
    private Long authorId;

    @Column(nullable = false)
    private Boolean isComplete = false;

    @Column(nullable = false)
    private Integer sortOrder = 0;

    @ManyToMany
    @JoinTable(name = "series_stories",
        joinColumns = @JoinColumn(name = "series_id"),
        inverseJoinColumns = @JoinColumn(name = "story_id"))
    @OrderColumn(name = "story_order")
    private List<Story> stories = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;
}
```

---

## 4. DTOs

### 4.1 Story DTOs

```java
// ===== Request =====
public record StoryCreateRequest(
    @NotBlank String title,
    String description,
    String cover,
    StoryStatus status,
    Set<Long> categoryIds,
    Set<Long> tagIds,
    String contentWarning,
    Boolean isPremium
) {}

public record StoryUpdateRequest(
    String title,
    String description,
    String cover,
    StoryStatus status,
    Set<Long> categoryIds,
    Set<Long> tagIds,
    String contentWarning,
    Boolean isPremium,
    Boolean isVerified
) {}

// ===== Response =====
public record StoryResponse(
    Long id,
    Long authorId,
    String authorName,          // từ Auth Service (Feign)
    String authorAvatar,        // từ Auth Service (Feign)
    String title,
    String slug,
    String description,
    String cover,
    String status,
    String contentWarning,
    Integer totalChapters,
    Long totalViews,
    Long totalWords,
    BigDecimal avgRating,
    Integer ratingCount,
    Integer followCount,
    Boolean isPremium,
    Boolean isVerified,
    Instant publishedAt,
    Instant createdAt,
    Instant updatedAt,
    List<CategoryResponse> categories,
    List<TagResponse> tags,
    // Feign: từ Social Service
    BigDecimal socialRating,
    Integer socialRatingCount,
    Integer socialFollowCount
) {}

public record StorySummaryResponse(
    Long id,
    String title,
    String slug,
    String cover,
    String status,
    BigDecimal avgRating,
    Integer ratingCount,
    Integer followCount,
    Integer totalChapters,
    Instant publishedAt,
    Instant createdAt,
    List<String> categoryNames,
    String authorName
) {}

// ===== Listing =====
public record StoryListRequest(
    @DefaultValue("1") int page,
    @DefaultValue("20") int size,
    String sort,                // latest, hot, top, new
    String status,              // ONGOING, COMPLETED, ...
    Long categoryId,
    Long tagId,
    String search
) {}

public record PagedResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean first,
    boolean last
) {}
```

### 4.2 Chapter DTOs

```java
public record ChapterCreateRequest(
    @NotBlank String title,
    @NotNull BigDecimal chapterNumber,
    @NotBlank String content,
    Boolean isFree,
    ChapterStatus status,
    Instant scheduledAt
) {}

public record ChapterUpdateRequest(
    String title,
    String content,
    Boolean isFree,
    ChapterStatus status,
    Instant scheduledAt,
    String changeSummary
) {}

public record ChapterResponse(
    Long id,
    Long storyId,
    String title,
    BigDecimal chapterNumber,
    String content,
    Integer wordCount,
    String status,
    Boolean isFree,
    Instant scheduledAt,
    Instant publishedAt,
    Instant createdAt,
    Instant updatedAt,
    Integer currentVersion,
    // Feign: từ Audio Service
    Boolean hasAudio,
    String audioUrl,
    Integer audioDuration
) {}

public record ChapterSummaryResponse(
    Long id,
    String title,
    BigDecimal chapterNumber,
    Integer wordCount,
    String status,
    Boolean isFree,
    Instant publishedAt
) {}
```

### 4.3 Chapter Version DTOs

```java
public record ChapterVersionResponse(
    Long id,
    Long chapterId,
    Integer version,
    String title,
    String content,
    Integer wordCount,
    String changeSummary,
    Long createdBy,
    Instant createdAt
) {}

public record DiffResponse(
    int oldVersion,
    int newVersion,
    String diffHtml        // unified diff rendered as HTML
) {}

public record RevertRequest(
    Integer targetVersion,
    String changeSummary
) {}
```

### 4.4 Category DTOs

```java
public record CategoryCreateRequest(
    @NotBlank String name,
    String description,
    Long parentId,
    Integer sortOrder
) {}

public record CategoryUpdateRequest(
    String name,
    String description,
    Long parentId,
    Integer sortOrder,
    Boolean isActive
) {}

public record CategoryResponse(
    Long id,
    String name,
    String slug,
    String description,
    Long parentId,
    String parentName,
    Integer sortOrder,
    Boolean isActive,
    Integer storyCount,
    Instant createdAt,
    List<CategoryResponse> children
) {}
```

### 4.5 Tag DTOs

```java
public record TagCreateRequest(
    @NotBlank String name
) {}

public record TagResponse(
    Long id,
    String name,
    String slug,
    Integer usageCount,
    Boolean isTrending,
    Instant createdAt
) {}
```

### 4.6 Series DTOs

```java
public record SeriesCreateRequest(
    @NotBlank String name,
    String description,
    String cover,
    Boolean isComplete,
    Integer sortOrder,
    List<Long> storyIds
) {}

public record SeriesUpdateRequest(
    String name,
    String description,
    String cover,
    Boolean isComplete,
    Integer sortOrder,
    List<Long> storyIds
) {}

public record SeriesResponse(
    Long id,
    String name,
    String slug,
    String description,
    String cover,
    Long authorId,
    String authorName,
    Boolean isComplete,
    Integer sortOrder,
    Integer storyCount,
    Instant createdAt,
    Instant updatedAt,
    List<StorySummaryResponse> stories
) {}
```

### 4.7 Import/Export DTOs

```java
public record ImportRequest(
    MultipartFile file,
    String format,         // EPUB, PDF, TXT, DOCX
    Long storyId,
    BigDecimal startChapterNumber
) {}

public record ExportRequest(
    Long storyId,
    String format,         // EPUB, PDF, TXT, DOCX
    BigDecimal fromChapter,
    BigDecimal toChapter
) {}

public record ImportResult(
    int totalChapters,
    int importedChapters,
    int skippedChapters,
    List<String> errors
) {}
```

### 4.8 Bulk Operation DTOs

```java
public record BulkDeleteRangeRequest(
    BigDecimal fromNumber,
    BigDecimal toNumber
) {}

public record BulkRenumberRequest(
    BigDecimal startFrom,
    BigDecimal step     // default 1.0
) {}

public record BulkRepublishRequest(
    List<Long> chapterIds
) {}

public record BulkOperationResult(
    int affectedChapters,
    String message
) {}
```

---

## 5. API Endpoints

### 5.1 Stories

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `POST` | `/api/v1/stories` | Tạo truyện mới | USER, AUTHOR |
| `GET` | `/api/v1/stories` | Danh sách truyện (phân trang) | - |
| `GET` | `/api/v1/stories/{id}` | Chi tiết truyện | - |
| `GET` | `/api/v1/stories/slug/{slug}` | Chi tiết truyện theo slug | - |
| `PUT` | `/api/v1/stories/{id}` | Cập nhật truyện | USER (chủ sở hữu), ADMIN |
| `DELETE` | `/api/v1/stories/{id}` | Xoá truyện (soft delete) | ADMIN |
| `PATCH` | `/api/v1/stories/{id}/status` | Đổi trạng thái truyện | USER (chủ sở hữu) |
| `GET` | `/api/v1/stories/{id}/chapters` | Danh sách chương của truyện | - |
| `GET` | `/api/v1/users/{userId}/stories` | Danh sách truyện của tác giả | - |

**Ví dụ request/response:**

```http
POST /api/v1/stories
Content-Type: application/json
Authorization: Bearer <token>

{
  "title": "Đấu Phá Thương Khung",
  "description": "Một câu chuyện về tu luyện...",
  "cover": "https://cdn.metruyenchu.com/covers/dau-pha-thuong-khung.jpg",
  "status": "ONGOING",
  "categoryIds": [1, 5, 12],
  "tagIds": [3, 7, 15],
  "contentWarning": "Có cảnh bạo lực nhẹ",
  "isPremium": false
}
```

```http
HTTP/1.1 201 Created
Content-Type: application/json

{
  "code": 201,
  "message": "success",
  "data": {
    "id": 1,
    "authorId": 42,
    "authorName": "Mạc Mặc",
    "title": "Đấu Phá Thương Khung",
    "slug": "dau-pha-thuong-khung",
    "description": "Một câu chuyện về tu luyện...",
    "cover": "https://cdn.metruyenchu.com/covers/dau-pha-thuong-khung.jpg",
    "status": "ONGOING",
    "totalChapters": 0,
    "totalViews": 0,
    "totalWords": 0,
    "avgRating": 0.0,
    "ratingCount": 0,
    "followCount": 0,
    "isPremium": false,
    "isVerified": false,
    "publishedAt": null,
    "createdAt": "2025-01-15T10:30:00Z",
    "updatedAt": "2025-01-15T10:30:00Z",
    "categories": [
      { "id": 1, "name": "Tiên Hiệp", "slug": "tien-hiep" },
      { "id": 5, "name": "Huyền Huyễn", "slug": "huyen-huyen" }
    ],
    "tags": [
      { "id": 3, "name": "Dị Năng", "slug": "di-nang" }
    ]
  }
}
```

### 5.2 Chapters

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `POST` | `/api/v1/stories/{storyId}/chapters` | Tạo chương mới | USER (chủ sở hữu) |
| `GET` | `/api/v1/stories/{storyId}/chapters` | Danh sách chương (có filter) | - |
| `GET` | `/api/v1/chapters/{id}` | Chi tiết chương | - |
| `PUT` | `/api/v1/chapters/{id}` | Cập nhật chương | USER (chủ sở hữu) |
| `DELETE` | `/api/v1/chapters/{id}` | Xoá chương | USER (chủ sở hữu), ADMIN |
| `PATCH` | `/api/v1/chapters/{id}/status` | Đổi trạng thái chương | USER (chủ sở hữu) |

**Ví dụ request/response:**

```http
POST /api/v1/stories/1/chapters
Content-Type: application/json
Authorization: Bearer <token>

{
  "title": "Chương 1: Đại gia tộc",
  "chapterNumber": 1.0,
  "content": "<p>Trên đại lục Đấu Khí...</p><p>Ba tiếng trống vang lên...</p>",
  "isFree": true,
  "status": "PUBLISHED",
  "scheduledAt": null
}
```

```http
HTTP/1.1 201 Created
Content-Type: application/json

{
  "code": 201,
  "message": "success",
  "data": {
    "id": 101,
    "storyId": 1,
    "title": "Chương 1: Đại gia tộc",
    "chapterNumber": 1.0,
    "wordCount": 2450,
    "status": "PUBLISHED",
    "isFree": true,
    "publishedAt": "2025-01-15T11:00:00Z",
    "currentVersion": 1
  }
}
```

### 5.3 Chapter Scheduling

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `POST` | `/api/v1/chapters/{id}/schedule` | Lên lịch xuất bản | USER (chủ sở hữu) |
| `DELETE` | `/api/v1/chapters/{id}/schedule` | Huỷ lịch xuất bản | USER (chủ sở hữu) |
| `GET` | `/api/v1/stories/{storyId}/chapters/scheduled` | DS chương đã lên lịch | USER (chủ sở hữu) |

```http
POST /api/v1/chapters/102/schedule
Content-Type: application/json
Authorization: Bearer <token>

{
  "scheduledAt": "2025-01-20T08:00:00Z"
}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "code": 200,
  "message": "success",
  "data": {
    "id": 102,
    "scheduledAt": "2025-01-20T08:00:00Z",
    "status": "DRAFT"
  }
}
```

### 5.4 Chapter Version History

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `GET` | `/api/v1/chapters/{id}/versions` | DS phiên bản | USER (chủ sở hữu) |
| `GET` | `/api/v1/chapters/{id}/versions/{version}` | Chi tiết phiên bản | USER (chủ sở hữu) |
| `GET` | `/api/v1/chapters/{id}/diff?from={v1}&to={v2}` | So sánh 2 phiên bản | USER (chủ sở hữu) |
| `POST` | `/api/v1/chapters/{id}/revert` | Khôi phục phiên bản | USER (chủ sở hữu) |

```http
POST /api/v1/chapters/101/revert
Content-Type: application/json
Authorization: Bearer <token>

{
  "targetVersion": 1,
  "changeSummary": "Revert về version gốc do lỗi định dạng"
}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "code": 200,
  "message": "success",
  "data": {
    "chapterId": 101,
    "previousVersion": 3,
    "newVersion": 4,
    "revertedToVersion": 1,
    "changeSummary": "Revert về version gốc do lỗi định dạng"
  }
}
```

### 5.5 Bulk Operations

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `POST` | `/api/v1/stories/{storyId}/chapters/bulk/delete-range` | Xoá theo khoảng số | USER (chủ sở hữu) |
| `POST` | `/api/v1/stories/{storyId}/chapters/bulk/renumber` | Đánh lại số chương | USER (chủ sở hữu) |
| `POST` | `/api/v1/stories/{storyId}/chapters/bulk/republish` | Tái xuất bản hàng loạt | USER (chủ sở hữu) |

```http
POST /api/v1/stories/1/chapters/bulk/delete-range
Content-Type: application/json
Authorization: Bearer <token>

{
  "fromNumber": 1.5,
  "toNumber": 3.0
}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "code": 200,
  "message": "success",
  "data": {
    "affectedChapters": 3,
    "message": "Đã xoá 3 chương từ số 1.5 đến 3.0"
  }
}
```

### 5.6 Categories

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `POST` | `/api/v1/categories` | Tạo thể loại | ADMIN |
| `GET` | `/api/v1/categories` | DS thể loại (dạng cây) | - |
| `GET` | `/api/v1/categories/{id}` | Chi tiết thể loại | - |
| `PUT` | `/api/v1/categories/{id}` | Cập nhật thể loại | ADMIN |
| `DELETE` | `/api/v1/categories/{id}` | Xoá thể loại | ADMIN |
| `PATCH` | `/api/v1/categories/{id}/sort` | Đổi thứ tự | ADMIN |

```http
GET /api/v1/categories
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 1,
      "name": "Tiên Hiệp",
      "slug": "tien-hiep",
      "description": "Thể loại Tiên Hiệp",
      "parentId": null,
      "sortOrder": 1,
      "isActive": true,
      "storyCount": 1250,
      "children": [
        {
          "id": 10,
          "name": "Tu Chân",
          "slug": "tu-chan",
          "parentId": 1,
          "sortOrder": 1,
          "storyCount": 450,
          "children": []
        }
      ]
    }
  ]
}
```

### 5.7 Tags

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `POST` | `/api/v1/tags` | Tạo tag | USER (user-defined) |
| `GET` | `/api/v1/tags` | DS tags (phân trang) | - |
| `GET` | `/api/v1/tags/trending` | Tags thịnh hành | - |
| `GET` | `/api/v1/tags/autocomplete?q=` | Gợi ý tag (autocomplete) | - |
| `DELETE` | `/api/v1/tags/{id}` | Xoá tag | ADMIN |

```http
GET /api/v1/tags/autocomplete?q=di
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "code": 200,
  "message": "success",
  "data": [
    { "id": 3, "name": "Dị Năng", "slug": "di-nang", "usageCount": 1240 },
    { "id": 45, "name": "Dị Giới", "slug": "di-gioi", "usageCount": 890 },
    { "id": 78, "name": "Dị Thế Giới", "slug": "di-the-gioi", "usageCount": 340 }
  ]
}
```

```http
GET /api/v1/tags/trending
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "code": 200,
  "message": "success",
  "data": [
    { "id": 3, "name": "Dị Năng", "usageCount": 1240, "isTrending": true },
    { "id": 7, "name": "Xuyên Không", "usageCount": 980, "isTrending": true },
    { "id": 12, "name": "Hệ Thống", "usageCount": 870, "isTrending": true }
  ]
}
```

### 5.8 Story Series

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `POST` | `/api/v1/series` | Tạo series | USER (chủ sở hữu) |
| `GET` | `/api/v1/series` | DS series (phân trang) | - |
| `GET` | `/api/v1/series/{id}` | Chi tiết series | - |
| `PUT` | `/api/v1/series/{id}` | Cập nhật series | USER (chủ sở hữu) |
| `DELETE` | `/api/v1/series/{id}` | Xoá series | USER (chủ sở hữu), ADMIN |
| `POST` | `/api/v1/series/{id}/stories` | Thêm truyện vào series | USER (chủ sở hữu) |
| `DELETE` | `/api/v1/series/{id}/stories/{storyId}` | Xoá truyện khỏi series | USER (chủ sở hữu) |
| `PUT` | `/api/v1/series/{id}/reorder` | Sắp xếp lại thứ tự | USER (chủ sở hữu) |

### 5.9 Search

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `GET` | `/api/v1/search/stories?q=&page=&size=&sort=&category=` | Tìm kiếm truyện | - |
| `GET` | `/api/v1/search/autocomplete?q=` | Gợi ý tìm kiếm | - |
| `GET` | `/api/v1/search/trending` | Từ khoá thịnh hành | - |

```http
GET /api/v1/search/stories?q=đấu phá&sort=hot&page=1&size=20
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "code": 200,
  "message": "success",
  "data": {
    "content": [
      {
        "id": 1,
        "title": "Đấu Phá Thương Khung",
        "slug": "dau-pha-thuong-khung",
        "cover": "https://cdn.metruyenchu.com/covers/dau-pha-thuong-khung.jpg",
        "status": "ONGOING",
        "authorName": "Mạc Mặc",
        "avgRating": 4.5,
        "totalChapters": 1650,
        "totalViews": 25000000,
        "matchedTerms": ["đấu phá"]
      }
    ],
    "page": 1,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

```http
GET /api/v1/search/autocomplete?q=dau
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "code": 200,
  "message": "success",
  "data": [
    { "text": "Đấu Phá Thương Khung", "type": "story", "slug": "dau-pha-thuong-khung" },
    { "text": "Đấu La Đại Lục", "type": "story", "slug": "dau-la-dai-luc" },
    { "text": "Đấu Kỹ", "type": "tag", "slug": "dau-ky" }
  ]
}
```

### 5.10 Import/Export

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `POST` | `/api/v1/import` | Import truyện (multipart file) | USER (chủ sở hữu) |
| `GET` | `/api/v1/export/stories/{storyId}` | Export truyện | - |
| `GET` | `/api/v1/export/stories/{storyId}/chapters/{chapterId}` | Export 1 chương | - |

```http
POST /api/v1/import
Content-Type: multipart/form-data
Authorization: Bearer <token>

file: @truyen-moi.epub
format: EPUB
storyId: 1
startChapterNumber: 1.0
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "code": 200,
  "message": "success",
  "data": {
    "totalChapters": 10,
    "importedChapters": 10,
    "skippedChapters": 0,
    "errors": []
  }
}
```

### 5.11 Story Listing (Sort & Filter)

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| `GET` | `/api/v1/stories?sort=latest` | Mới nhất |
| `GET` | `/api/v1/stories?sort=hot` | Thịnh hành (7 ngày) |
| `GET` | `/api/v1/stories?sort=top` | Đánh giá cao nhất |
| `GET` | `/api/v1/stories?sort=new` | Mới đăng (tuần) |
| `GET` | `/api/v1/stories?categoryId=5` | Lọc theo thể loại |
| `GET` | `/api/v1/stories?status=ONGOING` | Lọc theo trạng thái |
| `GET` | `/api/v1/stories?tagId=3` | Lọc theo tag |
| `GET` | `/api/v1/stories?search=đấu phá` | Tìm kiếm |
| `GET` | `/api/v1/stories?isPremium=true` | Truyện Premium |

**Sort mapping:**

| Sort value | ORDER BY |
|-----------|----------|
| `latest` | `published_at DESC NULLS LAST` |
| `hot` | `(total_views / GREATEST(EXTRACT(DAY FROM NOW() - published_at), 1)) DESC` |
| `top` | `avg_rating DESC, rating_count DESC` |
| `new` | `created_at DESC` |

---

## 6. Search Implementation (PostgreSQL Full-Text)

### 6.1 Search Configuration

```sql
-- Tạo cột tsvector cho stories
ALTER TABLE stories ADD COLUMN search_vector TSVECTOR;

-- Tạo trigger cập nhật search_vector
CREATE OR REPLACE FUNCTION update_story_search_vector()
RETURNS TRIGGER AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('simple', COALESCE(NEW.title, '')), 'A') ||
        setweight(to_tsvector('simple', COALESCE(NEW.description, '')), 'B');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_story_search_vector
    BEFORE INSERT OR UPDATE OF title, description
    ON stories
    FOR EACH ROW
    EXECUTE FUNCTION update_story_search_vector();

-- Index GIN cho full-text search
CREATE INDEX idx_stories_search ON stories USING GIN(search_vector);

-- Index trigram cho autocomplete
CREATE INDEX idx_stories_title_trgm ON stories USING GIN (title gin_trgm_ops);
CREATE INDEX idx_stories_slug_trgm ON stories USING GIN (slug gin_trgm_ops);
```

### 6.2 Search Query

```sql
-- Full-text search
SELECT s.id, s.title, s.slug, s.cover, s.avg_rating, s.total_chapters, s.total_views,
       ts_rank(s.search_vector, plainto_tsquery('simple', ?)) AS rank
FROM stories s
WHERE s.search_vector @@ plainto_tsquery('simple', ?)
  AND s.status IN ('ONGOING', 'COMPLETED')
ORDER BY rank DESC
LIMIT ? OFFSET ?;

-- Autocomplete
SELECT s.id, s.title, s.slug, 'story' AS type
FROM stories s
WHERE s.title ILIKE ? || '%'
   OR s.slug ILIKE ? || '%'
LIMIT 10;
```

### 6.3 Repository

```java
public interface StorySearchRepository extends JpaRepository<Story, Long> {

    @Query(value = """
        SELECT s FROM Story s
        WHERE s.searchVector @@ plainto_tsquery('simple', :query)
        AND s.status IN ('ONGOING', 'COMPLETED')
        ORDER BY ts_rank(s.searchVector, plainto_tsquery('simple', :query)) DESC
        """)
    Page<Story> searchByText(@Param("query") String query, Pageable pageable);

    @Query(value = """
        SELECT s FROM Story s
        WHERE (s.title ILIKE :prefix OR s.slug ILIKE :prefix)
        AND s.status IN ('ONGOING', 'COMPLETED')
        """)
    List<Story> autocomplete(@Param("prefix") String prefix, Pageable pageable);

    @Query(value = """
        SELECT s FROM Story s
        WHERE s.status IN ('ONGOING', 'COMPLETED')
        ORDER BY (s.totalViews / GREATEST(
            FUNCTION('EXTRACT', 'DAY', AGE(CURRENT_TIMESTAMP, s.publishedAt)), 1)) DESC
        """)
    Page<Story> findHotStories(Pageable pageable);
}
```

---

## 7. Scheduling (Auto-Publish)

### 7.1 Scheduler

```java
@Component
@RequiredArgsConstructor
public class ChapterScheduler {

    private final ChapterRepository chapterRepository;
    private final ChapterEventPublisher eventPublisher;

    @Scheduled(fixedRate = 60000) // mỗi phút
    @Transactional
    public void publishScheduledChapters() {
        List<Chapter> dueChapters = chapterRepository
            .findByStatusAndScheduledAtBefore(ChapterStatus.DRAFT, Instant.now());

        for (Chapter chapter : dueChapters) {
            chapter.setStatus(ChapterStatus.PUBLISHED);
            chapter.setPublishedAt(Instant.now());
            chapterRepository.save(chapter);
            eventPublisher.publishChapterPublished(chapter);
        }
    }
}
```

---

## 8. RabbitMQ Events (Publisher)

### 8.1 Events Published

| Event | Topic | Trigger | Payload |
|-------|-------|---------|---------|
| `story.chapter.published` | `story.chapter.published` | Chapter published (manual/scheduled) | `{ storyId, chapterId, chapterNumber, title, authorId }` |

### 8.2 Outbox Pattern Implementation

```java
@Service
@RequiredArgsConstructor
public class ChapterEventPublisher {

    private final OutboxEventRepository outboxRepository;

    @Transactional
    public void publishChapterPublished(Chapter chapter) {
        var payload = Map.of(
            "storyId", chapter.getStoryId(),
            "chapterId", chapter.getId(),
            "chapterNumber", chapter.getChapterNumber(),
            "title", chapter.getTitle(),
            "authorId", getAuthorId(chapter.getStoryId())
        );
        outboxRepository.save(new OutboxEvent("story.chapter.published", payload));
    }

    private Long getAuthorId(Long storyId) {
        // lấy từ story repository
        return storyRepository.findById(storyId)
            .map(Story::getAuthorId)
            .orElseThrow();
    }
}

@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxRepository
            .findTop100ByPublishedFalseOrderByCreatedAt();
        for (OutboxEvent event : pending) {
            try {
                rabbitTemplate.convertAndSend(event.getTopic(), event.getPayload());
                event.setPublished(true);
                outboxRepository.save(event);
            } catch (Exception e) {
                log.error("Failed to publish outbox event {}", event.getId(), e);
            }
        }
    }
}
```

---

## 9. OpenFeign Clients

### 9.1 Auth Service — Lấy thông tin tác giả

```java
@FeignClient(name = "auth-service", url = "${services.auth.url}")
public interface AuthServiceClient {

    @GetMapping("/api/v1/users/{userId}")
    UserResponse getUser(@PathVariable Long userId);

    @GetMapping("/api/v1/users/batch")
    List<UserResponse> getUsersBatch(@RequestParam List<Long> userIds);
}

public record UserResponse(
    Long id,
    String displayName,
    String avatar,
    String role
) {}
```

### 9.2 Social Service — Lấy đánh giá, follow count

```java
@FeignClient(name = "social-service", url = "${services.social.url}")
public interface SocialServiceClient {

    @GetMapping("/api/v1/ratings/story/{storyId}/summary")
    RatingSummaryResponse getRatingSummary(@PathVariable Long storyId);

    @GetMapping("/api/v1/follows/story/{storyId}/count")
    FollowCountResponse getFollowCount(@PathVariable Long storyId);

    @GetMapping("/api/v1/follows/stories/counts")
    Map<Long, FollowCountResponse> getFollowCountsBatch(@RequestParam List<Long> storyIds);
}

public record RatingSummaryResponse(
    BigDecimal avgRating,
    Integer totalRatings,
    Map<Integer, Integer> distribution
) {}

public record FollowCountResponse(
    Integer count
) {}
```

### 9.3 Audio Service — Trạng thái audio

```java
@FeignClient(name = "audio-service", url = "${services.audio.url}")
public interface AudioServiceClient {

    @GetMapping("/api/v1/chapters/{chapterId}/audio")
    AudioStatusResponse getAudioStatus(@PathVariable Long chapterId);

    @GetMapping("/api/v1/chapters/audio/batch")
    Map<Long, AudioStatusResponse> getAudioStatusBatch(@RequestParam List<Long> chapterIds);
}

public record AudioStatusResponse(
    Boolean hasAudio,
    String audioUrl,
    Integer duration,
    String status
) {}
```

### 9.4 Resilience4j Configuration

```yaml
resilience4j:
  circuitbreaker:
    instances:
      authService:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
      socialService:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
      audioService:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
  retry:
    instances:
      authService:
        maxRetryAttempts: 2
        waitDuration: 500ms
  timelimiter:
    instances:
      socialService:
        timeoutDuration: 2s
```

### 9.5 Aggregation Service

```java
@Service
@RequiredArgsConstructor
public class StoryAggregationService {

    private final AuthServiceClient authClient;
    private final SocialServiceClient socialClient;
    private final AudioServiceClient audioClient;

    @CircuitBreaker(name = "socialService", fallbackMethod = "fallbackRating")
    public RatingSummaryResponse getRatingSummary(Long storyId) {
        return socialClient.getRatingSummary(storyId);
    }

    public RatingSummaryResponse fallbackRating(Long storyId, Throwable t) {
        return new RatingSummaryResponse(BigDecimal.ZERO, 0, Map.of());
    }

    public StoryResponse enrichWithExternalData(Story story) {
        // Gọi song song các Feign clients
        CompletableFuture<UserResponse> authorFuture =
            CompletableFuture.supplyAsync(() -> authClient.getUser(story.getAuthorId()));
        CompletableFuture<RatingSummaryResponse> ratingFuture =
            CompletableFuture.supplyAsync(() -> getRatingSummary(story.getId()));
        CompletableFuture<FollowCountResponse> followFuture =
            CompletableFuture.supplyAsync(() -> socialClient.getFollowCount(story.getId()));

        return CompletableFuture.allOf(authorFuture, ratingFuture, followFuture)
            .thenApply(v -> {
                UserResponse author = authorFuture.join();
                RatingSummaryResponse rating = ratingFuture.join();
                FollowCountResponse follow = followFuture.join();
                return mapToResponse(story, author, rating, follow);
            }).join();
    }
}
```

---

## 10. Flyway Migrations

```
db/migration/
├── V1__create_stories_table.sql
├── V2__create_chapters_table.sql
├── V3__create_chapter_versions_table.sql
├── V4__create_categories_table.sql
├── V5__create_story_categories_table.sql
├── V6__create_tags_table.sql
├── V7__create_story_tags_table.sql
├── V8__create_story_series_table.sql
├── V9__create_series_stories_table.sql
├── V10__create_outbox_events_table.sql
├── V11__add_search_vector_to_stories.sql
├── V12__seed_categories.sql
├── V13__enable_extensions.sql          -- CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

---

## 11. Application Configuration

```yaml
spring:
  application:
    name: story-service
  datasource:
    url: jdbc:postgresql://localhost:5433/story_db
    username: ${STORY_DB_USER:story}
    password: ${STORY_DB_PASSWORD:story}
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        jdbc:
          batch_size: 50
        order_inserts: true
        order_updates: true
  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: 8082

services:
  auth:
    url: http://auth-service:8081
  social:
    url: http://social-service:8083
  audio:
    url: http://audio-service:8084

spring.rabbitmq:
  host: ${RABBITMQ_HOST:localhost}
  port: 5672
  username: ${RABBITMQ_USER:guest}
  password: ${RABBITMQ_PASS:guest}

spring.cache:
  type: redis
  redis:
    time-to-live: 300s
    cache-null-values: false
```

---

## 12. Caching Strategy

| Cache Name | Key | TTL | Mục đích |
|-----------|-----|-----|----------|
| `storyDetail` | `story:{id}` | 300s | Chi tiết truyện |
| `storySummary` | `story:summary:{id}` | 120s | Tóm tắt truyện (listing) |
| `categoryTree` | `categories:tree` | 600s | Cây thể loại |
| `trendingTags` | `tags:trending` | 300s | Tags thịnh hành |
| `storyChapters` | `story:{id}:chapters:{page}` | 60s | DS chương |
| `searchResults` | `search:{query}:{page}` | 60s | Kết quả tìm kiếm |

---

## 13. Business Logic Highlights

### 13.1 Slug Auto-Generation

```java
public class SlugUtils {
    public static String toSlug(String input) {
        // Loại bỏ dấu tiếng Việt, chuyển thành slug
        String slug = input.toLowerCase()
            .replaceAll("[àáạảãâầấậẩẫăằắặẳẵ]", "a")
            .replaceAll("[èéẹẻẽêềếệểễ]", "e")
            .replaceAll("[ìíịỉĩ]", "i")
            .replaceAll("[òóọỏõôồốộổỗơờớợởỡ]", "o")
            .replaceAll("[ùúụủũưừứựửữ]", "u")
            .replaceAll("[ỳýỵỷỹ]", "y")
            .replaceAll("[đ]", "d")
            .replaceAll("[^a-z0-9\\s-]", "")
            .replaceAll("\\s+", "-")
            .replaceAll("-+", "-")
            .replaceAll("^-|-$", "");
        return slug;
    }
}
```

```java
// Service logic
@Transactional
public Story createStory(StoryCreateRequest request) {
    String baseSlug = SlugUtils.toSlug(request.title());
    String slug = baseSlug;
    int counter = 1;
    while (storyRepository.existsBySlug(slug)) {
        slug = baseSlug + "-" + counter++;
    }
    // ...
}
```

### 13.2 Word Count Auto-Calculation

```java
// Tính toán khi lưu chapter
@PreUpdate
@PrePersist
public void calculateWordCount() {
    if (content != null && !content.isBlank()) {
        String stripped = content.replaceAll("<[^>]*>", "")  // strip HTML tags
                                 .replaceAll("\\s+", " ")
                                 .trim();
        this.wordCount = stripped.isEmpty() ? 0 : stripped.split(" ").length;
    } else {
        this.wordCount = 0;
    }
}
```

### 13.3 Publish Chapter Event Trigger

```java
@Transactional
public Chapter publishChapter(Long chapterId) {
    Chapter chapter = chapterRepository.findById(chapterId)
        .orElseThrow(() -> new NotFoundException("Chapter not found"));

    if (chapter.getStatus() == ChapterStatus.PUBLISHED) {
        throw new BusinessException("Chapter already published");
    }

    chapter.setStatus(ChapterStatus.PUBLISHED);
    chapter.setPublishedAt(Instant.now());
    chapterRepository.save(chapter);

    // Update story metadata
    Story story = storyRepository.findById(chapter.getStoryId()).orElseThrow();
    story.setTotalChapters(story.getTotalChapters() + 1);
    story.setTotalWords(story.getTotalWords() + chapter.getWordCount());
    storyRepository.save(story);

    // Publish event
    eventPublisher.publishChapterPublished(chapter);

    return chapter;
}
```

### 13.4 Chapter Versioning

```java
@Transactional
public Chapter updateChapter(Long chapterId, ChapterUpdateRequest request) {
    Chapter chapter = chapterRepository.findById(chapterId)
        .orElseThrow(() -> new NotFoundException("Chapter not found"));

    // Lưu version hiện tại
    ChapterVersion version = new ChapterVersion();
    version.setChapterId(chapter.getId());
    version.setVersion(getNextVersion(chapterId));
    version.setTitle(chapter.getTitle());
    version.setContent(chapter.getContent());
    version.setWordCount(chapter.getWordCount());
    version.setChangeSummary(request.changeSummary());
    versionRepository.save(version);

    // Cập nhật chapter
    if (request.title() != null) chapter.setTitle(request.title());
    if (request.content() != null) chapter.setContent(request.content());
    if (request.isFree() != null) chapter.setIsFree(request.isFree());

    chapterRepository.save(chapter);
    return chapter;
}
```

### 13.5 Diff View

```java
public DiffResponse getDiff(Long chapterId, int fromVersion, int toVersion) {
    ChapterVersion v1 = versionRepository
        .findByChapterIdAndVersion(chapterId, fromVersion)
        .orElseThrow(() -> new NotFoundException("Version not found"));
    ChapterVersion v2 = versionRepository
        .findByChapterIdAndVersion(chapterId, toVersion)
        .orElseThrow(() -> new NotFoundException("Version not found"));

    String diff = computeDiff(v1.getContent(), v2.getContent());
    return new DiffResponse(fromVersion, toVersion, diff);
}

private String computeDiff(String oldText, String newText) {
    // Sử dụng java-diff-utils hoặc similar
    List<String> oldLines = Arrays.asList(oldText.split("\n"));
    List<String> newLines = Arrays.asList(newText.split("\n"));
    Patch<String> patch = DiffUtils.diff(oldLines, newLines);
    // Render unified diff → HTML with ins/del tags
    return renderDiffAsHtml(patch, oldLines, newLines);
}
```

### 13.6 Trending Tags

```java
@Component
@RequiredArgsConstructor
public class TrendingTagUpdater {

    private final TagRepository tagRepository;

    @Scheduled(cron = "0 0 * * * *") // mỗi giờ
    @Transactional
    public void updateTrendingTags() {
        // Reset tất cả trending tags
        tagRepository.resetAllTrending();

        // Lấy top 20 tags được dùng nhiều nhất trong 7 ngày
        List<Tag> trendingTags = tagRepository.findTop20ByUsageInLast7Days();
        trendingTags.forEach(tag -> tag.setIsTrending(true));
        tagRepository.saveAll(trendingTags);
    }
}
```

---

## 14. Error Codes

| Error Code | HTTP Status | Ý nghĩa |
|-----------|-------------|---------|
| `STORY_NOT_FOUND` | 404 | Không tìm thấy truyện |
| `CHAPTER_NOT_FOUND` | 404 | Không tìm thấy chương |
| `CATEGORY_NOT_FOUND` | 404 | Không tìm thấy thể loại |
| `TAG_NOT_FOUND` | 404 | Không tìm thấy tag |
| `SLUG_CONFLICT` | 409 | Slug đã tồn tại |
| `CHAPTER_NUMBER_CONFLICT` | 409 | Số chương đã tồn tại |
| `NOT_STORY_OWNER` | 403 | Không phải chủ sở hữu truyện |
| `CANNOT_DELETE_PUBLISHED_CHAPTER` | 422 | Không thể xoá chương đã xuất bản |
| `INVALID_CHAPTER_RANGE` | 400 | Khoảng số chương không hợp lệ |
| `IMPORT_FAILED` | 422 | Import thất bại |
| `EXPORT_FAILED` | 422 | Export thất bại |
| `VERSION_NOT_FOUND` | 404 | Không tìm thấy phiên bản |

---

## End of Story Service Spec
