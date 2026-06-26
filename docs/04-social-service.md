# Social Service — MeTruyenChu

> **File:** 04-social-service.md
> **Part of:** metruyenchu rebuild spec series
> **Version:** 1.0

---

## 1. Tổng quan

**Social Service** quản lý tất cả tương tác xã hội trên nền tảng — bình luận, đánh dấu trang, theo dõi, đánh giá, lịch sử đọc, danh sách đọc, huy hiệu, và báo cáo.

| Thuộc tính | Giá trị |
|-----------|---------|
| Service name | `social-service` |
| Port | 8083 |
| Database | PostgreSQL 16 — `social_db` |
| ORM | Spring Data JPA + Hibernate 6 |
| Migration | Flyway |
| Message Queue | RabbitMQ (publisher + consumer) |
| Service Comms | OpenFeign + Resilience4j |
| Cache | Redis 7 (rating summary, comment count) |

---

## 2. Entities & SQL Schema

### 2.1 comments

```sql
CREATE TABLE comments (
    id              BIGSERIAL       PRIMARY KEY,
    story_id        BIGINT          NOT NULL,
    chapter_id      BIGINT,
    user_id         BIGINT          NOT NULL,
    parent_id       BIGINT          REFERENCES comments(id) ON DELETE CASCADE,
    content         TEXT            NOT NULL,
    is_spoiler      BOOLEAN         NOT NULL DEFAULT FALSE,
    depth           INT             NOT NULL DEFAULT 0
                        CHECK (depth BETWEEN 0 AND 2),
    like_count      INT             NOT NULL DEFAULT 0,
    reply_count     INT             NOT NULL DEFAULT 0,
    status          VARCHAR(20)     NOT NULL DEFAULT 'APPROVED'
                        CHECK (status IN ('APPROVED','HIDDEN','SPAM','PENDING')),
    is_edited       BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_comments_story ON comments (story_id, created_at DESC);
CREATE INDEX idx_comments_chapter ON comments (chapter_id, created_at DESC);
CREATE INDEX idx_comments_user ON comments (user_id);
CREATE INDEX idx_comments_parent ON comments (parent_id);
CREATE INDEX idx_comments_status ON comments (status);
```

### 2.2 comment_reactions

```sql
CREATE TABLE comment_reactions (
    id              BIGSERIAL       PRIMARY KEY,
    comment_id      BIGINT          NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
    user_id         BIGINT          NOT NULL,
    reaction        VARCHAR(20)     NOT NULL
                        CHECK (reaction IN ('LIKE','LOVE','LAUGH','ANGRY')),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_comment_user_reaction UNIQUE (comment_id, user_id)
);

CREATE INDEX idx_comment_reactions_comment ON comment_reactions (comment_id);
```

### 2.3 bookmark_folders

```sql
CREATE TABLE bookmark_folders (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    name            VARCHAR(100)    NOT NULL,
    description     VARCHAR(500),
    sort_order      INT             NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_bookmark_folder_user_name UNIQUE (user_id, name)
);

CREATE INDEX idx_bookmark_folders_user ON bookmark_folders (user_id, sort_order);
```

### 2.4 bookmarks

```sql
CREATE TABLE bookmarks (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    story_id        BIGINT          NOT NULL,
    folder_id       BIGINT          REFERENCES bookmark_folders(id) ON DELETE SET NULL,
    note            VARCHAR(1000),
    tags            TEXT[],
    last_chapter_id BIGINT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_bookmark_user_story UNIQUE (user_id, story_id)
);

CREATE INDEX idx_bookmarks_user ON bookmarks (user_id);
CREATE INDEX idx_bookmarks_folder ON bookmarks (folder_id);
CREATE INDEX idx_bookmarks_story ON bookmarks (story_id);
CREATE INDEX idx_bookmarks_tags ON bookmarks USING GIN (tags);
```

### 2.5 story_follows

```sql
CREATE TABLE story_follows (
    id                  BIGSERIAL       PRIMARY KEY,
    user_id             BIGINT          NOT NULL,
    story_id            BIGINT          NOT NULL,
    notify_on_update    BOOLEAN         NOT NULL DEFAULT TRUE,
    notify_on_comment   BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_story_follow_user UNIQUE (user_id, story_id)
);

CREATE INDEX idx_story_follows_user ON story_follows (user_id);
CREATE INDEX idx_story_follows_story ON story_follows (story_id);
```

### 2.6 user_follows

```sql
CREATE TABLE user_follows (
    id              BIGSERIAL       PRIMARY KEY,
    follower_id     BIGINT          NOT NULL,
    following_id    BIGINT          NOT NULL,
    notify_on_new_story BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_user_follow UNIQUE (follower_id, following_id)
);

CREATE INDEX idx_user_follows_follower ON user_follows (follower_id);
CREATE INDEX idx_user_follows_following ON user_follows (following_id);
```

### 2.7 ratings

```sql
CREATE TABLE ratings (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    story_id        BIGINT          NOT NULL,
    rating          INT             NOT NULL CHECK (rating BETWEEN 1 AND 5),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_rating_user_story UNIQUE (user_id, story_id)
);

CREATE INDEX idx_ratings_story ON ratings (story_id);
CREATE INDEX idx_ratings_user ON ratings (user_id);
```

### 2.8 reviews

```sql
CREATE TABLE reviews (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    story_id        BIGINT          NOT NULL,
    rating_id       BIGINT          NOT NULL REFERENCES ratings(id) ON DELETE CASCADE,
    title           VARCHAR(255),
    content         TEXT            NOT NULL,
    is_spoiler      BOOLEAN         NOT NULL DEFAULT FALSE,
    like_count      INT             NOT NULL DEFAULT 0,
    status          VARCHAR(20)     NOT NULL DEFAULT 'APPROVED'
                        CHECK (status IN ('APPROVED','PENDING','HIDDEN')),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_review_user_story UNIQUE (user_id, story_id)
);

CREATE INDEX idx_reviews_story ON reviews (story_id, created_at DESC);
CREATE INDEX idx_reviews_user ON reviews (user_id);
```

### 2.9 reading_histories

```sql
CREATE TABLE reading_histories (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    story_id        BIGINT          NOT NULL,
    last_chapter_id BIGINT          NOT NULL,
    last_chapter_number DECIMAL(6,1) NOT NULL,
    progress        DECIMAL(5,2)    NOT NULL DEFAULT 0.00
                        CHECK (progress BETWEEN 0 AND 100),
    word_position   INT             NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    last_read_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_reading_history_user_story UNIQUE (user_id, story_id)
);

CREATE INDEX idx_reading_histories_user ON reading_histories (user_id, last_read_at DESC);
CREATE INDEX idx_reading_histories_story ON reading_histories (story_id);
```

### 2.10 chapters_read

```sql
CREATE TABLE chapters_read (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    chapter_id      BIGINT          NOT NULL,
    story_id        BIGINT          NOT NULL,
    read_at         TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_chapters_read_user UNIQUE (user_id, chapter_id)
);

CREATE INDEX idx_chapters_read_user ON chapters_read (user_id, story_id, read_at DESC);
CREATE INDEX idx_chapters_read_story ON chapters_read (story_id);
```

### 2.11 booklists

```sql
CREATE TABLE booklists (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    name            VARCHAR(200)    NOT NULL,
    slug            VARCHAR(240)    NOT NULL UNIQUE,
    description     TEXT,
    cover           VARCHAR(500),
    is_public       BOOLEAN         NOT NULL DEFAULT TRUE,
    is_official     BOOLEAN         NOT NULL DEFAULT FALSE,
    is_featured     BOOLEAN         NOT NULL DEFAULT FALSE,
    like_count      INT             NOT NULL DEFAULT 0,
    follow_count    INT             NOT NULL DEFAULT 0,
    story_count     INT             NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_booklists_user ON booklists (user_id);
CREATE INDEX idx_booklists_official ON booklists (is_official, is_featured DESC);
CREATE INDEX idx_booklists_trending ON booklists (follow_count DESC)
    WHERE is_public = TRUE AND is_official = FALSE;
```

### 2.12 booklist_stories

```sql
CREATE TABLE booklist_stories (
    id              BIGSERIAL       PRIMARY KEY,
    booklist_id     BIGINT          NOT NULL REFERENCES booklists(id) ON DELETE CASCADE,
    story_id        BIGINT          NOT NULL,
    note            VARCHAR(500),
    sort_order      INT             NOT NULL DEFAULT 0,
    added_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_booklist_story UNIQUE (booklist_id, story_id)
);

CREATE INDEX idx_booklist_stories_list ON booklist_stories (booklist_id, sort_order);
```

### 2.13 booklist_follows

```sql
CREATE TABLE booklist_follows (
    id              BIGSERIAL       PRIMARY KEY,
    booklist_id     BIGINT          NOT NULL REFERENCES booklists(id) ON DELETE CASCADE,
    user_id         BIGINT          NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_booklist_follow UNIQUE (booklist_id, user_id)
);

CREATE INDEX idx_booklist_follows_list ON booklist_follows (booklist_id);
```

### 2.14 badges

```sql
CREATE TABLE badges (
    id              BIGSERIAL       PRIMARY KEY,
    code            VARCHAR(50)     NOT NULL UNIQUE,
    name            VARCHAR(100)    NOT NULL,
    description     VARCHAR(500)    NOT NULL,
    icon            VARCHAR(300),
    category        VARCHAR(50)     NOT NULL
                        CHECK (category IN ('READING','SOCIAL','ACHIEVEMENT','SPECIAL')),
    criteria        JSONB           NOT NULL,
    sort_order      INT             NOT NULL DEFAULT 0,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);
```

### 2.15 user_badges

```sql
CREATE TABLE user_badges (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    badge_id        BIGINT          NOT NULL REFERENCES badges(id) ON DELETE CASCADE,
    progress        JSONB,
    earned_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_user_badge UNIQUE (user_id, badge_id)
);

CREATE INDEX idx_user_badges_user ON user_badges (user_id);
CREATE INDEX idx_user_badges_earned ON user_badges (earned_at DESC)
    WHERE earned_at IS NOT NULL;
```

### 2.16 reports

```sql
CREATE TABLE reports (
    id              BIGSERIAL       PRIMARY KEY,
    reporter_id     BIGINT          NOT NULL,
    target_type     VARCHAR(20)     NOT NULL
                        CHECK (target_type IN ('STORY','CHAPTER','COMMENT','USER','REVIEW')),
    target_id       BIGINT          NOT NULL,
    reason          VARCHAR(20)     NOT NULL
                        CHECK (reason IN ('SPAM','ABUSE','PLAGIARISM','OFFENSIVE','NSFW','OTHER')),
    description     TEXT,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING','REVIEWED','RESOLVED','DISMISSED')),
    resolved_by     BIGINT,
    resolution_note TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_reports_status ON reports (status, created_at);
CREATE INDEX idx_reports_target ON reports (target_type, target_id);
CREATE INDEX idx_reports_reporter ON reports (reporter_id);
```

### 2.17 outbox_events

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

### 3.1 Comment

```java
@Entity
@Table(name = "comments")
public class Comment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storyId;

    private Long chapterId;

    @Column(nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Comment parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL)
    @OrderBy("createdAt ASC")
    private List<Comment> replies = new ArrayList<>();

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private Boolean isSpoiler = false;

    @Column(nullable = false)
    private Integer depth = 0;

    @Column(nullable = false)
    private Integer likeCount = 0;

    @Column(nullable = false)
    private Integer replyCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CommentStatus status;

    @Column(nullable = false)
    private Boolean isEdited = false;

    private Instant createdAt;
    private Instant updatedAt;
}

public enum CommentStatus {
    APPROVED, HIDDEN, SPAM, PENDING
}
```

### 3.2 CommentReaction

```java
@Entity
@Table(name = "comment_reactions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"comment_id", "user_id"}))
public class CommentReaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long commentId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReactionType reaction;

    private Instant createdAt;
}

public enum ReactionType {
    LIKE, LOVE, LAUGH, ANGRY
}
```

### 3.3 Bookmark

```java
@Entity
@Table(name = "bookmarks",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "story_id"}))
public class Bookmark {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long storyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id")
    private BookmarkFolder folder;

    @Column(length = 1000)
    private String note;

    @Column(columnDefinition = "TEXT[]")
    private List<String> tags;

    private Long lastChapterId;
    private Instant createdAt;
    private Instant updatedAt;
}

@Entity
@Table(name = "bookmark_folders",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "name"}))
public class BookmarkFolder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private Integer sortOrder = 0;

    private Instant createdAt;
    private Instant updatedAt;

    @OneToMany(mappedBy = "folder")
    private List<Bookmark> bookmarks = new ArrayList<>();
}
```

### 3.4 StoryFollow

```java
@Entity
@Table(name = "story_follows",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "story_id"}))
public class StoryFollow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long storyId;

    @Column(nullable = false)
    private Boolean notifyOnUpdate = true;

    @Column(nullable = false)
    private Boolean notifyOnComment = false;

    private Instant createdAt;
}

@Entity
@Table(name = "user_follows",
       uniqueConstraints = @UniqueConstraint(columnNames = {"follower_id", "following_id"}))
public class UserFollow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long followerId;

    @Column(nullable = false)
    private Long followingId;

    @Column(nullable = false)
    private Boolean notifyOnNewStory = true;

    private Instant createdAt;
}
```

### 3.5 Rating & Review

```java
@Entity
@Table(name = "ratings",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "story_id"}))
public class Rating {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long storyId;

    @Column(nullable = false)
    @Min(1) @Max(5)
    private Integer rating;

    private Instant createdAt;
    private Instant updatedAt;

    @OneToOne(mappedBy = "rating", cascade = CascadeType.ALL)
    private Review review;
}

@Entity
@Table(name = "reviews",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "story_id"}))
public class Review {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long storyId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rating_id", nullable = false)
    private Rating rating;

    @Column(length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private Boolean isSpoiler = false;

    @Column(nullable = false)
    private Integer likeCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewStatus status;

    private Instant createdAt;
    private Instant updatedAt;
}

public enum ReviewStatus {
    APPROVED, PENDING, HIDDEN
}
```

### 3.6 ReadingHistory

```java
@Entity
@Table(name = "reading_histories",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "story_id"}))
public class ReadingHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long storyId;

    @Column(nullable = false)
    private Long lastChapterId;

    @Column(nullable = false, precision = 6, scale = 1)
    private BigDecimal lastChapterNumber;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal progress = BigDecimal.ZERO;

    @Column(nullable = false)
    private Integer wordPosition = 0;

    private Instant startedAt;

    @Column(nullable = false)
    private Instant lastReadAt;
}

@Entity
@Table(name = "chapters_read",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "chapter_id"}))
public class ChaptersRead {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long chapterId;

    @Column(nullable = false)
    private Long storyId;

    private Instant readAt;
}
```

### 3.7 Booklist

```java
@Entity
@Table(name = "booklists")
public class Booklist {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, unique = true, length = 240)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 500)
    private String cover;

    @Column(nullable = false)
    private Boolean isPublic = true;

    @Column(nullable = false)
    private Boolean isOfficial = false;

    @Column(nullable = false)
    private Boolean isFeatured = false;

    @Column(nullable = false)
    private Integer likeCount = 0;

    @Column(nullable = false)
    private Integer followCount = 0;

    @Column(nullable = false)
    private Integer storyCount = 0;

    @ManyToMany
    @JoinTable(name = "booklist_stories",
        joinColumns = @JoinColumn(name = "booklist_id"),
        inverseJoinColumns = @JoinColumn(name = "story_id"))
    @OrderColumn(name = "sort_order")
    private List<Story> stories = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;
}

@Entity
@Table(name = "booklist_stories",
       uniqueConstraints = @UniqueConstraint(columnNames = {"booklist_id", "story_id"}))
public class BooklistStory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long booklistId;

    @Column(nullable = false)
    private Long storyId;

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    private Integer sortOrder = 0;

    private Instant addedAt;
}

@Entity
@Table(name = "booklist_follows",
       uniqueConstraints = @UniqueConstraint(columnNames = {"booklist_id", "user_id"}))
public class BooklistFollow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long booklistId;

    @Column(nullable = false)
    private Long userId;

    private Instant createdAt;
}
```

### 3.8 Badge

```java
@Entity
@Table(name = "badges")
public class Badge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(length = 300)
    private String icon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private BadgeCategory category;

    @Column(nullable = false, columnDefinition = "JSONB")
    private String criteria;

    @Column(nullable = false)
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private Boolean isActive = true;

    private Instant createdAt;
}

public enum BadgeCategory {
    READING, SOCIAL, ACHIEVEMENT, SPECIAL
}

@Entity
@Table(name = "user_badges",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "badge_id"}))
public class UserBadge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long badgeId;

    @Column(columnDefinition = "JSONB")
    private String progress;

    private Instant earnedAt;
    private Instant createdAt;
}
```

### 3.9 Report

```java
@Entity
@Table(name = "reports")
public class Report {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long reporterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportTargetType targetType;

    @Column(nullable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportReason reason;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportStatus status;

    private Long resolvedBy;
    private String resolutionNote;
    private Instant createdAt;
    private Instant updatedAt;
}

public enum ReportTargetType { STORY, CHAPTER, COMMENT, USER, REVIEW }
public enum ReportReason { SPAM, ABUSE, PLAGIARISM, OFFENSIVE, NSFW, OTHER }
public enum ReportStatus { PENDING, REVIEWED, RESOLVED, DISMISSED }
```

---

## 4. DTOs

### 4.1 Comment DTOs

```java
public record CommentCreateRequest(
    @NotNull Long storyId,
    Long chapterId,
    Long parentId,
    @NotBlank String content,
    Boolean isSpoiler
) {}

public record CommentUpdateRequest(
    String content,
    Boolean isSpoiler
) {}

public record CommentResponse(
    Long id,
    Long storyId,
    Long chapterId,
    Long userId,
    String userName,
    String userAvatar,
    Long parentId,
    String content,
    Boolean isSpoiler,
    Integer depth,
    Integer likeCount,
    Integer replyCount,
    String status,
    Boolean isEdited,
    Instant createdAt,
    Instant updatedAt,
    Map<String, Integer> reactions     // {"LIKE": 5, "LOVE": 3, "LAUGH": 1, "ANGRY": 0}
) {}

public record CommentListRequest(
    @DefaultValue("1") int page,
    @DefaultValue("20") int size,
    String sort,                    // newest, oldest, most_liked
    Long chapterId
) {}
```

### 4.2 Bookmark DTOs

```java
public record BookmarkCreateRequest(
    @NotNull Long storyId,
    Long folderId,
    String note,
    List<String> tags,
    Long lastChapterId
) {}

public record BookmarkUpdateRequest(
    Long folderId,
    String note,
    List<String> tags,
    Long lastChapterId
) {}

public record BookmarkResponse(
    Long id,
    Long userId,
    Long storyId,
    String storyTitle,
    String storySlug,
    String storyCover,
    Long folderId,
    String folderName,
    String note,
    List<String> tags,
    Long lastChapterId,
    Instant createdAt
) {}

public record BookmarkFolderCreateRequest(
    @NotBlank String name,
    String description,
    Integer sortOrder
) {}

public record BookmarkFolderResponse(
    Long id,
    String name,
    String description,
    Integer sortOrder,
    Integer bookmarkCount,
    Instant createdAt
) {}
```

### 4.3 Follow DTOs

```java
public record StoryFollowRequest(
    @NotNull Long storyId,
    Boolean notifyOnUpdate,
    Boolean notifyOnComment
) {}

public record StoryFollowResponse(
    Long id,
    Long userId,
    Long storyId,
    String storyTitle,
    String storySlug,
    String storyCover,
    Boolean notifyOnUpdate,
    Boolean notifyOnComment,
    Instant createdAt
) {}

public record UserFollowRequest(
    @NotNull Long followingId,
    Boolean notifyOnNewStory
) {}

public record UserFollowResponse(
    Long id,
    Long followingId,
    String followingName,
    String followingAvatar,
    Boolean notifyOnNewStory,
    Instant createdAt
) {}

public record FollowCountResponse(
    Integer count
) {}

public record FollowCheckResponse(
    Boolean isFollowing,
    StoryFollowResponse follow
) {}
```

### 4.4 Rating DTOs

```java
public record RatingUpsertRequest(
    @NotNull Long storyId,
    @NotNull @Min(1) @Max(5) Integer rating
) {}

public record RatingResponse(
    Long id,
    Long userId,
    Long storyId,
    Integer rating,
    Instant createdAt,
    Instant updatedAt
) {}

public record RatingSummaryResponse(
    BigDecimal avgRating,
    Integer totalRatings,
    Map<Integer, Integer> distribution   // {1: 10, 2: 5, 3: 20, 4: 50, 5: 100}
) {}

public record ReviewCreateRequest(
    @NotNull Long storyId,
    @NotNull @Min(1) @Max(5) Integer rating,
    String title,
    @NotBlank String content,
    Boolean isSpoiler
) {}

public record ReviewUpdateRequest(
    String title,
    String content,
    Boolean isSpoiler
) {}

public record ReviewResponse(
    Long id,
    Long userId,
    String userName,
    String userAvatar,
    Long storyId,
    Integer rating,
    String title,
    String content,
    Boolean isSpoiler,
    Integer likeCount,
    String status,
    Instant createdAt,
    Instant updatedAt
) {}
```

### 4.5 ReadingHistory DTOs

```java
public record ReadingProgressRequest(
    @NotNull Long storyId,
    @NotNull Long lastChapterId,
    @NotNull BigDecimal lastChapterNumber,
    BigDecimal progress,
    Integer wordPosition
) {}

public record ReadingHistoryResponse(
    Long id,
    Long storyId,
    String storyTitle,
    String storySlug,
    String storyCover,
    Long lastChapterId,
    BigDecimal lastChapterNumber,
    String lastChapterTitle,
    BigDecimal progress,
    Integer wordPosition,
    Instant startedAt,
    Instant lastReadAt,
    // Thống kê
    Integer totalChaptersRead,
    Long totalWordsRead
) {}
```

### 4.6 Booklist DTOs

```java
public record BooklistCreateRequest(
    @NotBlank String name,
    String description,
    String cover,
    Boolean isPublic
) {}

public record BooklistUpdateRequest(
    String name,
    String description,
    String cover,
    Boolean isPublic
) {}

public record BooklistResponse(
    Long id,
    Long userId,
    String userName,
    String name,
    String slug,
    String description,
    String cover,
    Boolean isPublic,
    Boolean isOfficial,
    Boolean isFeatured,
    Integer likeCount,
    Integer followCount,
    Integer storyCount,
    Instant createdAt,
    Instant updatedAt,
    List<BooklistStoryResponse> stories
) {}

public record BooklistSummaryResponse(
    Long id,
    String name,
    String slug,
    String cover,
    String userName,
    Integer likeCount,
    Integer followCount,
    Integer storyCount,
    Boolean isOfficial,
    Instant createdAt
) {}

public record BooklistStoryResponse(
    Long storyId,
    String storyTitle,
    String storySlug,
    String storyCover,
    String note,
    Integer sortOrder,
    Instant addedAt
) {}

public record BooklistAddStoryRequest(
    Long storyId,
    String note,
    Integer sortOrder
) {}
```

### 4.7 Badge DTOs

```java
public record BadgeResponse(
    Long id,
    String code,
    String name,
    String description,
    String icon,
    String category,
    String criteria,
    Integer sortOrder,
    Boolean earned,
    Instant earnedAt,
    Map<String, Object> progress
) {}
```

### 4.8 Report DTOs

```java
public record ReportCreateRequest(
    @NotNull ReportTargetType targetType,
    @NotNull Long targetId,
    @NotNull ReportReason reason,
    String description
) {}

public record ReportResponse(
    Long id,
    Long reporterId,
    String targetType,
    Long targetId,
    String reason,
    String description,
    String status,
    Long resolvedBy,
    String resolutionNote,
    Instant createdAt
) {}

public record ReportResolveRequest(
    ReportStatus status,
    String resolutionNote
) {}
```

---

## 5. API Endpoints

### 5.1 Comments

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `POST` | `/api/v1/comments` | Tạo bình luận | USER |
| `GET` | `/api/v1/stories/{storyId}/comments` | DS bình luận của truyện | - |
| `GET` | `/api/v1/chapters/{chapterId}/comments` | DS bình luận của chương | - |
| `GET` | `/api/v1/comments/{id}` | Chi tiết bình luận (kèm replies) | - |
| `PUT` | `/api/v1/comments/{id}` | Sửa bình luận | USER (chủ sở hữu) |
| `DELETE` | `/api/v1/comments/{id}` | Xoá bình luận | USER (chủ sở hữu), ADMIN |
| `POST` | `/api/v1/comments/{id}/reactions` | Thả reaction | USER |
| `DELETE` | `/api/v1/comments/{id}/reactions` | Xoá reaction | USER |
| `POST` | `/api/v1/comments/{id}/report` | Báo cáo bình luận | USER |
| `PATCH` | `/api/v1/moderation/comments/{id}` | Duyệt/từ chối bình luận | ADMIN, MOD |

```http
POST /api/v1/comments
Content-Type: application/json
Authorization: Bearer <token>

{
  "storyId": 1,
  "chapterId": 101,
  "parentId": null,
  "content": "Truyện hay quá! Tác giả cố gắng lên nhé!",
  "isSpoiler": false
}
```

```http
HTTP/1.1 201 Created
Content-Type: application/json

{
  "code": 201,
  "message": "success",
  "data": {
    "id": 1001,
    "storyId": 1,
    "chapterId": 101,
    "userId": 42,
    "userName": "NguyenVanA",
    "userAvatar": "https://cdn.metruyenchu.com/avatars/42.jpg",
    "parentId": null,
    "content": "Truyện hay quá! Tác giả cố gắng lên nhé!",
    "isSpoiler": false,
    "depth": 0,
    "likeCount": 0,
    "replyCount": 0,
    "status": "APPROVED",
    "isEdited": false,
    "createdAt": "2025-01-15T12:00:00Z",
    "reactions": {}
  }
}
```

```http
GET /api/v1/stories/1/comments?sort=newest&page=1&size=20
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
        "id": 1001,
        "content": "Truyện hay quá!",
        "userId": 42,
        "userName": "NguyenVanA",
        "depth": 0,
        "likeCount": 5,
        "replyCount": 2,
        "status": "APPROVED",
        "createdAt": "2025-01-15T12:00:00Z",
        "reactions": { "LIKE": 5, "LOVE": 2, "LAUGH": 0, "ANGRY": 0 },
        "replies": [
          {
            "id": 1002,
            "content": "Cảm ơn bạn!",
            "userId": 1,
            "userName": "TacGia",
            "depth": 1,
            "parentId": 1001,
            "likeCount": 3,
            "createdAt": "2025-01-15T12:30:00Z"
          }
        ]
      }
    ],
    "page": 1,
    "size": 20,
    "totalElements": 150,
    "totalPages": 8
  }
}
```

### 5.2 Bookmarks

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `POST` | `/api/v1/bookmarks` | Đánh dấu truyện | USER |
| `GET` | `/api/v1/bookmarks` | DS bookmark của user (phân trang) | USER |
| `GET` | `/api/v1/bookmarks/folders` | DS folder của user | USER |
| `GET` | `/api/v1/bookmarks/folders/{folderId}` | DS bookmark trong folder | USER |
| `GET` | `/api/v1/bookmarks/tags` | DS tag bookmark của user | USER |
| `GET` | `/api/v1/bookmarks/tags/{tag}` | DS bookmark theo tag | USER |
| `GET` | `/api/v1/bookmarks/story/{storyId}` | Kiểm tra đã bookmark | USER |
| `PUT` | `/api/v1/bookmarks/{id}` | Cập nhật bookmark | USER (chủ sở hữu) |
| `DELETE` | `/api/v1/bookmarks/{id}` | Xoá bookmark | USER (chủ sở hữu) |
| `POST` | `/api/v1/bookmarks/folders` | Tạo folder | USER |
| `PUT` | `/api/v1/bookmarks/folders/{folderId}` | Sửa folder | USER |
| `DELETE` | `/api/v1/bookmarks/folders/{folderId}` | Xoá folder | USER |

```http
GET /api/v1/bookmarks?page=1&size=20
Authorization: Bearer <token>
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
        "id": 5,
        "userId": 42,
        "storyId": 1,
        "storyTitle": "Đấu Phá Thương Khung",
        "storySlug": "dau-pha-thuong-khung",
        "storyCover": "https://cdn.metruyenchu.com/covers/dau-pha.jpg",
        "folderId": 1,
        "folderName": "Đang đọc",
        "note": "Đọc tới chương 500",
        "tags": ["tiên hiệp", "hay"],
        "lastChapterId": 500,
        "createdAt": "2025-01-10T08:00:00Z"
      }
    ],
    "page": 1,
    "size": 20,
    "totalElements": 10,
    "totalPages": 1
  }
}
```

### 5.3 Follows

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `POST` | `/api/v1/follows/stories` | Theo dõi truyện | USER |
| `DELETE` | `/api/v1/follows/stories/{storyId}` | Bỏ theo dõi truyện | USER |
| `PUT` | `/api/v1/follows/stories/{storyId}` | Cập nhật thông báo | USER |
| `GET` | `/api/v1/follows/stories` | DS truyện đang theo dõi | USER |
| `GET` | `/api/v1/follows/stories/{storyId}/check` | Kiểm tra đã theo dõi | USER |
| `GET` | `/api/v1/follows/story/{storyId}/count` | Đếm số người theo dõi | - |
| `POST` | `/api/v1/follows/users` | Theo dõi người dùng | USER |
| `DELETE` | `/api/v1/follows/users/{userId}` | Bỏ theo dõi | USER |
| `GET` | `/api/v1/follows/users` | DS đang theo dõi | USER |
| `GET` | `/api/v1/follows/users/{userId}/check` | Kiểm tra đã theo dõi | USER |
| `GET` | `/api/v1/follows/users/{userId}/followers` | DS người theo dõi | - |
| `GET` | `/api/v1/follows/users/{userId}/following` | DS đang theo dõi | - |

```http
POST /api/v1/follows/stories
Content-Type: application/json
Authorization: Bearer <token>

{
  "storyId": 1,
  "notifyOnUpdate": true,
  "notifyOnComment": false
}
```

```http
HTTP/1.1 201 Created
Content-Type: application/json

{
  "code": 201,
  "message": "success",
  "data": {
    "id": 50,
    "storyId": 1,
    "notifyOnUpdate": true,
    "notifyOnComment": false,
    "createdAt": "2025-01-15T12:00:00Z"
  }
}
```

### 5.4 Ratings

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `POST` | `/api/v1/ratings` | Đánh giá truyện | USER |
| `PUT` | `/api/v1/ratings` | Cập nhật đánh giá | USER |
| `GET` | `/api/v1/ratings/story/{storyId}/summary` | Tổng quan đánh giá | - |
| `GET` | `/api/v1/ratings/story/{storyId}` | DS đánh giá (phân trang) | - |
| `GET` | `/api/v1/ratings/user/{userId}` | DS đánh giá của user | - |
| `GET` | `/api/v1/ratings/user/story/{storyId}` | Đánh giá của user cho truyện | USER |

```http
POST /api/v1/ratings
Content-Type: application/json
Authorization: Bearer <token>

{
  "storyId": 1,
  "rating": 5
}
```

```http
HTTP/1.1 201 Created
Content-Type: application/json

{
  "code": 201,
  "message": "success",
  "data": {
    "id": 500,
    "storyId": 1,
    "rating": 5,
    "createdAt": "2025-01-15T12:00:00Z"
  }
}
```

### 5.5 Reviews

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `POST` | `/api/v1/reviews` | Tạo review (kèm rating) | USER |
| `PUT` | `/api/v1/reviews/{id}` | Sửa review | USER (chủ sở hữu) |
| `GET` | `/api/v1/stories/{storyId}/reviews` | DS review của truyện | - |
| `GET` | `/api/v1/reviews/{id}` | Chi tiết review | - |
| `DELETE` | `/api/v1/reviews/{id}` | Xoá review | USER (chủ sở hữu), ADMIN |
| `PATCH` | `/api/v1/moderation/reviews/{id}` | Duyệt review | ADMIN, MOD |

### 5.6 Reading History

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `POST` | `/api/v1/reading-history` | Lưu tiến độ đọc | USER |
| `GET` | `/api/v1/reading-history` | DS lịch sử đọc (gần đây) | USER |
| `GET` | `/api/v1/reading-history/story/{storyId}` | Tiến độ của 1 truyện | USER |
| `GET` | `/api/v1/reading-history/continue-reading` | DS tiếp tục đọc | USER |
| `DELETE` | `/api/v1/reading-history/story/{storyId}` | Xoá lịch sử 1 truyện | USER |
| `POST` | `/api/v1/chapters-read` | Đánh dấu đã đọc chương | USER |
| `GET` | `/api/v1/stories/{storyId}/chapters-read` | DS chương đã đọc | USER |

```http
POST /api/v1/reading-history
Content-Type: application/json
Authorization: Bearer <token>

{
  "storyId": 1,
  "lastChapterId": 101,
  "lastChapterNumber": 5.0,
  "progress": 45.5,
  "wordPosition": 1200
}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "code": 200,
  "message": "success",
  "data": {
    "id": 10,
    "storyId": 1,
    "lastChapterId": 101,
    "lastChapterNumber": 5.0,
    "progress": 45.5,
    "lastReadAt": "2025-01-15T12:05:00Z"
  }
}
```

```http
GET /api/v1/reading-history/continue-reading?page=1&size=10
Authorization: Bearer <token>
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
        "storyId": 1,
        "storyTitle": "Đấu Phá Thương Khung",
        "storySlug": "dau-pha-thuong-khung",
        "storyCover": "https://cdn.metruyenchu.com/covers/dau-pha.jpg",
        "lastChapterId": 500,
        "lastChapterNumber": 500.0,
        "lastChapterTitle": "Chương 500: Đại chiến",
        "progress": 75.0,
        "lastReadAt": "2025-01-15T10:00:00Z",
        "totalChaptersRead": 500,
        "totalWordsRead": 1250000
      }
    ],
    "page": 1,
    "size": 10,
    "totalElements": 3,
    "totalPages": 1
  }
}
```

### 5.7 Booklists

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `POST` | `/api/v1/booklists` | Tạo danh sách đọc | USER |
| `GET` | `/api/v1/booklists` | DS danh sách đọc (phân trang, filter) | - |
| `GET` | `/api/v1/booklists/trending` | Danh sách thịnh hành | - |
| `GET` | `/api/v1/booklists/official` | Danh sách chính thức | - |
| `GET` | `/api/v1/booklists/{id}` | Chi tiết danh sách | - |
| `GET` | `/api/v1/booklists/slug/{slug}` | Chi tiết theo slug | - |
| `PUT` | `/api/v1/booklists/{id}` | Cập nhật danh sách | USER (chủ sở hữu) |
| `DELETE` | `/api/v1/booklists/{id}` | Xoá danh sách | USER (chủ sở hữu), ADMIN |
| `POST` | `/api/v1/booklists/{id}/stories` | Thêm truyện vào danh sách | USER (chủ sở hữu) |
| `DELETE` | `/api/v1/booklists/{id}/stories/{storyId}` | Xoá truyện khỏi danh sách | USER (chủ sở hữu) |
| `POST` | `/api/v1/booklists/{id}/like` | Thích danh sách | USER |
| `DELETE` | `/api/v1/booklists/{id}/like` | Bỏ thích | USER |
| `POST` | `/api/v1/booklists/{id}/follow` | Theo dõi danh sách | USER |
| `DELETE` | `/api/v1/booklists/{id}/follow` | Bỏ theo dõi | USER |
| `GET` | `/api/v1/users/{userId}/booklists` | DS của người dùng | - |

```http
GET /api/v1/booklists/trending?page=1&size=10
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
        "name": "Tiên Hiệp Hay Nhất 2025",
        "slug": "tien-hiep-hay-nhat-2025",
        "cover": "https://cdn.metruyenchu.com/booklists/tien-hiep.jpg",
        "userName": "Bookworm",
        "likeCount": 1250,
        "followCount": 340,
        "storyCount": 20,
        "isOfficial": false,
        "createdAt": "2025-01-01T00:00:00Z"
      }
    ],
    "page": 1,
    "size": 10,
    "totalElements": 50,
    "totalPages": 5
  }
}
```

### 5.8 Badges

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `GET` | `/api/v1/badges` | DS huy hiệu hệ thống | - |
| `GET` | `/api/v1/users/{userId}/badges` | Huy hiệu của người dùng | - |
| `GET` | `/api/v1/badges/progress` | Tiến độ huy hiệu của user | USER |

```http
GET /api/v1/users/42/badges
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
      "code": "TAN_THU",
      "name": "Tân Thủ",
      "description": "Đăng ký tài khoản",
      "icon": "https://cdn.metruyenchu.com/badges/tan-thu.svg",
      "category": "ACHIEVEMENT",
      "earned": true,
      "earnedAt": "2025-01-01T00:00:00Z",
      "progress": null
    },
    {
      "id": 2,
      "code": "MOT_SACH",
      "name": "Mọt Sách",
      "description": "Đọc 100 chương",
      "icon": "https://cdn.metruyenchu.com/badges/mot-sach.svg",
      "category": "READING",
      "earned": true,
      "earnedAt": "2025-01-10T00:00:00Z",
      "progress": null
    },
    {
      "id": 3,
      "code": "DOC_GIA_TRUNG_THANH",
      "name": "Độc Giả Trung Thành",
      "description": "Đọc 1000 chương",
      "icon": "https://cdn.metruyenchu.com/badges/doc-gia-trung-thanh.svg",
      "category": "READING",
      "earned": false,
      "earnedAt": null,
      "progress": { "current": 750, "target": 1000, "percentage": 75 }
    }
  ]
}
```

### 5.9 Reports & Moderation

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `POST` | `/api/v1/reports` | Gửi báo cáo | USER |
| `GET` | `/api/v1/moderation/reports` | DS báo cáo (queue) | ADMIN, MOD |
| `GET` | `/api/v1/moderation/reports/{id}` | Chi tiết báo cáo | ADMIN, MOD |
| `PATCH` | `/api/v1/moderation/reports/{id}` | Xử lý báo cáo | ADMIN, MOD |

```http
PATCH /api/v1/moderation/reports/100
Content-Type: application/json
Authorization: Bearer <admin_token>

{
  "status": "RESOLVED",
  "resolutionNote": "Đã xoá bình luận spam"
}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "code": 200,
  "message": "success",
  "data": {
    "id": 100,
    "targetType": "COMMENT",
    "targetId": 5000,
    "reason": "SPAM",
    "status": "RESOLVED",
    "resolvedBy": 1,
    "resolutionNote": "Đã xoá bình luận spam",
    "updatedAt": "2025-01-15T14:00:00Z"
  }
}
```

### 5.10 Moderation Comments/Reviews

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `PATCH` | `/api/v1/moderation/comments/{id}` | Duyệt/ẩn bình luận | ADMIN, MOD |
| `PATCH` | `/api/v1/moderation/reviews/{id}` | Duyệt/ẩn review | ADMIN, MOD |
| `GET` | `/api/v1/moderation/queue` | Hàng chờ duyệt | ADMIN, MOD |

```http
PATCH /api/v1/moderation/comments/1001
Content-Type: application/json
Authorization: Bearer <mod_token>

{
  "status": "HIDDEN",
  "reason": "Nội dung không phù hợp"
}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1001,
    "status": "HIDDEN",
    "updatedAt": "2025-01-15T14:30:00Z"
  }
}
```

---

## 6. RabbitMQ Events

### 6.1 Published Events

| Event | Topic | Trigger | Payload |
|-------|-------|---------|---------|
| `social.comment.created` | `social.comment.created` | Comment mới | `{ commentId, storyId, chapterId, authorId, parentId }` |
| `social.follow.created` | `social.follow.created` | Follow mới | `{ followerId, targetId, targetType, action }` |
| `social.rating.updated` | `social.rating.updated` | Rating thay đổi | `{ storyId, newRating, oldRating, avgRating, totalRatings }` |

### 6.2 Event Publishing

```java
@Service
@RequiredArgsConstructor
public class SocialEventPublisher {

    private final OutboxEventRepository outboxRepository;

    @Transactional
    public void commentCreated(Comment comment) {
        var payload = Map.of(
            "commentId", comment.getId(),
            "storyId", comment.getStoryId(),
            "chapterId", comment.getChapterId(),
            "authorId", comment.getUserId(),
            "parentId", comment.getParent() != null ? comment.getParent().getId() : null
        );
        outboxRepository.save(new OutboxEvent("social.comment.created", payload));
    }

    @Transactional
    public void followCreated(Long followerId, Long targetId, String targetType) {
        var payload = Map.of(
            "followerId", followerId,
            "targetId", targetId,
            "targetType", targetType,
            "action", "FOLLOW"
        );
        outboxRepository.save(new OutboxEvent("social.follow.created", payload));
    }

    @Transactional
    public void ratingUpdated(Long storyId, Integer newRating,
                               Integer oldRating, Double avgRating, Integer totalRatings) {
        var payload = Map.of(
            "storyId", storyId,
            "newRating", newRating,
            "oldRating", oldRating != null ? oldRating : 0,
            "avgRating", avgRating,
            "totalRatings", totalRatings
        );
        outboxRepository.save(new OutboxEvent("social.rating.updated", payload));
    }
}
```

### 6.3 Event Consumers

```java
@Component
@RequiredArgsConstructor
public class RatingEventConsumer {

    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = "social.rating.updated.story")
    public void handleRatingUpdated(Map<String, Object> payload) {
        // Story Service cập nhật avg_rating và rating_count
        Long storyId = Long.valueOf(payload.get("storyId").toString());
        Double avgRating = (Double) payload.get("avgRating");
        Integer totalRatings = (Integer) payload.get("totalRatings");

        // Gửi đến Story Service qua RabbitMQ
        rabbitTemplate.convertAndSend("story.rating.updated", payload);
    }
}
```

---

## 7. OpenFeign Clients

### 7.1 Story Service — Validate story/chapter

```java
@FeignClient(name = "story-service", url = "${services.story.url}")
public interface StoryServiceClient {

    @GetMapping("/api/v1/stories/{storyId}")
    StoryValidationResponse validateStory(@PathVariable Long storyId);

    @GetMapping("/api/v1/chapters/{chapterId}")
    ChapterValidationResponse validateChapter(@PathVariable Long chapterId);

    @GetMapping("/api/v1/stories/batch")
    Map<Long, StoryValidationResponse> validateStoriesBatch(@RequestParam List<Long> storyIds);
}

public record StoryValidationResponse(
    Long id,
    String title,
    String slug,
    String status,
    Long authorId
) {}

public record ChapterValidationResponse(
    Long id,
    Long storyId,
    BigDecimal chapterNumber,
    String status
) {}
```

### 7.2 Auth Service — Validate user

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

### 7.3 Resilience4j Configuration

```yaml
resilience4j:
  circuitbreaker:
    instances:
      storyService:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
      authService:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
  retry:
    instances:
      storyService:
        maxRetryAttempts: 2
        waitDuration: 500ms
```

---

## 8. Flyway Migrations

```
db/migration/
├── V1__create_comments_table.sql
├── V2__create_comment_reactions_table.sql
├── V3__create_bookmarks_tables.sql
├── V4__create_follows_tables.sql
├── V5__create_ratings_table.sql
├── V6__create_reviews_table.sql
├── V7__create_reading_histories_table.sql
├── V8__create_chapters_read_table.sql
├── V9__create_booklists_tables.sql
├── V10__create_badges_tables.sql
├── V11__create_reports_table.sql
├── V12__create_outbox_events_table.sql
├── V13__seed_system_badges.sql
├── V14__seed_official_booklists.sql
```

---

## 9. Application Configuration

```yaml
spring:
  application:
    name: social-service
  datasource:
    url: jdbc:postgresql://localhost:5435/social_db
    username: ${SOCIAL_DB_USER:social}
    password: ${SOCIAL_DB_PASSWORD:social}
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        jdbc:
          batch_size: 50
  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: 8083

services:
  story:
    url: http://story-service:8082
  auth:
    url: http://auth-service:8081

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

## 10. Caching Strategy

| Cache Name | Key | TTL | Mục đích |
|-----------|-----|-----|----------|
| `ratingSummary` | `rating:summary:{storyId}` | 120s | Tổng quan đánh giá |
| `commentCount` | `comment:count:{storyId}` | 60s | Số lượng bình luận |
| `userBadges` | `badges:user:{userId}` | 300s | Huy hiệu người dùng |
| `readingHistory` | `reading:user:{userId}` | 60s | Lịch sử đọc gần đây |
| `booklistDetail` | `booklist:{id}` | 120s | Chi tiết danh sách đọc |
| `trendingBooklists` | `booklists:trending` | 300s | Danh sách thịnh hành |
| `followCount` | `follow:count:story:{storyId}` | 120s | Số lượng follow |

---

## 11. Business Logic Highlights

### 11.1 Threaded Comments (Max Depth 2)

```java
@Transactional
public Comment createComment(CommentCreateRequest request, Long userId) {
    // Validate story/chapter via Feign
    storyClient.validateStory(request.storyId());

    Comment comment = new Comment();
    comment.setStoryId(request.storyId());
    comment.setChapterId(request.chapterId());
    comment.setUserId(userId);

    if (request.parentId() != null) {
        Comment parent = commentRepository.findById(request.parentId())
            .orElseThrow(() -> new NotFoundException("Parent comment not found"));

        if (parent.getDepth() >= 2) {
            throw new BusinessException("Maximum comment depth is 2");
        }

        comment.setParent(parent);
        comment.setDepth(parent.getDepth() + 1);

        // Tăng reply_count của parent
        parent.setReplyCount(parent.getReplyCount() + 1);
        commentRepository.save(parent);
    }

    comment.setContent(request.content());
    comment.setIsSpoiler(request.isSpoiler() != null && request.isSpoiler());
    comment.setStatus(CommentStatus.APPROVED); // Auto-approve, có thể config
    commentRepository.save(comment);

    // Publish event
    eventPublisher.commentCreated(comment);

    return comment;
}
```

### 11.2 Rating Update → Publish → Story Service

```java
@Transactional
public Rating upsertRating(RatingUpsertRequest request, Long userId) {
    // Validate
    storyClient.validateStory(request.storyId());

    Optional<Rating> existing = ratingRepository
        .findByUserIdAndStoryId(userId, request.storyId());

    Integer oldRating = existing.map(Rating::getRating).orElse(null);

    Rating rating = existing.orElse(new Rating());
    rating.setUserId(userId);
    rating.setStoryId(request.storyId());
    rating.setRating(request.rating());

    if (existing.isPresent()) {
        rating.setCreatedAt(existing.get().getCreatedAt());
        rating.setUpdatedAt(Instant.now());
    }

    ratingRepository.save(rating);

    // Calculate new average
    DoubleSummaryStatistics stats = ratingRepository
        .findByStoryId(request.storyId())
        .stream()
        .mapToInt(Rating::getRating)
        .summaryStatistics();

    // Publish event
    eventPublisher.ratingUpdated(
        request.storyId(),
        request.rating(),
        oldRating,
        stats.getAverage(),
        (int) stats.getCount()
    );

    return rating;
}
```

### 11.3 Reading Progress Save (Debounced)

```java
@Service
@RequiredArgsConstructor
public class ReadingHistoryService {

    private final ReadingHistoryRepository repository;

    @Transactional
    public ReadingHistory saveProgress(ReadingProgressRequest request, Long userId) {
        ReadingHistory history = repository
            .findByUserIdAndStoryId(userId, request.storyId())
            .orElseGet(() -> {
                ReadingHistory h = new ReadingHistory();
                h.setUserId(userId);
                h.setStoryId(request.storyId());
                h.setStartedAt(Instant.now());
                return h;
            });

        history.setLastChapterId(request.lastChapterId());
        history.setLastChapterNumber(request.lastChapterNumber());
        history.setProgress(request.progress());
        history.setWordPosition(request.wordPosition());
        history.setLastReadAt(Instant.now());

        return repository.save(history);
    }
}
```

### 11.4 Badge Logic

```java
@Component
@RequiredArgsConstructor
public class BadgeEvaluator {

    private final UserBadgeRepository userBadgeRepository;
    private final ChaptersReadRepository chaptersReadRepository;
    private final CommentRepository commentRepository;

    @Transactional
    public void evaluate(Long userId) {
        // Kiểm tra từng badge dựa trên criteria
        evaluateReadingBadges(userId);
        evaluateSocialBadges(userId);
        evaluateAchievementBadges(userId);
    }

    private void evaluateReadingBadges(Long userId) {
        int totalChapters = chaptersReadRepository.countByUserId(userId);

        // Mọt Sách: đọc 100 chương
        checkAndAward(userId, "MOT_SACH", totalChapters >= 100);

        // Độc Giả Trung Thành: đọc 1000 chương
        checkAndAward(userId, "DOC_GIA_TRUNG_THANH", totalChapters >= 1000);

        // Bá Vương: đọc 10000 chương
        checkAndAward(userId, "BA_VUONG", totalChapters >= 10000);
    }

    private void checkAndAward(Long userId, String badgeCode, boolean condition) {
        if (!condition) return;

        Badge badge = badgeRepository.findByCode(badgeCode)
            .orElseThrow(() -> new NotFoundException("Badge not found"));

        if (userBadgeRepository.findByUserIdAndBadgeId(userId, badge.getId()).isEmpty()) {
            UserBadge userBadge = new UserBadge();
            userBadge.setUserId(userId);
            userBadge.setBadgeId(badge.getId());
            userBadge.setEarnedAt(Instant.now());
            userBadgeRepository.save(userBadge);
        }
    }
}
```

### 11.5 Trending Booklists

```java
@Component
@RequiredArgsConstructor
public class TrendingBooklistUpdater {

    private final BooklistRepository booklistRepository;

    @Scheduled(cron = "0 0 */2 * * *") // mỗi 2 giờ
    @Transactional
    public void updateTrending() {
        List<Booklist> trending = booklistRepository.findTrending(
            OffsetDateTime.now().minusDays(7),
            PageRequest.of(0, 20)
        );

        booklistRepository.resetFeatured();
        trending.forEach(b -> b.setIsFeatured(true));
        booklistRepository.saveAll(trending);
    }
}
```

---

## 12. Seed Data — System Badges

| Code | Name | Category | Description | Criteria |
|------|------|----------|-------------|----------|
| `TAN_THU` | Tân Thủ | ACHIEVEMENT | Đăng ký tài khoản | `{ "type": "register" }` |
| `MOT_SACH` | Mọt Sách | READING | Đọc 100 chương | `{ "type": "chapters_read", "target": 100 }` |
| `DOC_GIA_TRUNG_THANH` | Độc Giả Trung Thành | READING | Đọc 1000 chương | `{ "type": "chapters_read", "target": 1000 }` |
| `BA_VUONG` | Bá Vương | READING | Đọc 10000 chương | `{ "type": "chapters_read", "target": 10000 }` |
| `THANH_VIEN_NOI_BAT` | Thành Viên Nổi Bật | SOCIAL | Có 100 người theo dõi | `{ "type": "followers", "target": 100 }` |
| `NHAN_XET` | Nhà Nhận Xét | SOCIAL | Viết 10 review | `{ "type": "reviews", "target": 10 }` |
| `BINH_LUAN_VIEN` | Bình Luận Viên | SOCIAL | Viết 100 bình luận | `{ "type": "comments", "target": 100 }` |
| `TUONG_TAC` | Người Tương Tác | SOCIAL | Nhận 1000 reaction | `{ "type": "reactions_received", "target": 1000 }` |
| `TRIEU_PHIEU_BAU` | Triệu Phiếu Bầu | SOCIAL | Nhận 10000 lượt thích | `{ "type": "likes_received", "target": 10000 }` |
| `DANH_GIA` | Chuyên Gia Đánh Giá | ACHIEVEMENT | Đánh giá 50 truyện | `{ "type": "ratings", "target": 50 }` |
| `TAP_HOP` | Nhà Sưu Tầm | ACHIEVEMENT | Tạo 10 danh sách đọc | `{ "type": "booklists", "target": 10 }` |
| `TAC_GIA_DOC_GIA` | Tác Giả - Độc Giả | SPECIAL | Vừa đăng truyện vừa đọc | `{ "type": "author_reader", "conditions": ["has_story", "chapters_read >= 50"] }` |

---

## 13. Error Codes

| Error Code | HTTP Status | Ý nghĩa |
|-----------|-------------|---------|
| `STORY_NOT_FOUND` | 404 | Story Service trả về không tìm thấy truyện |
| `CHAPTER_NOT_FOUND` | 404 | Chapter không tồn tại |
| `COMMENT_NOT_FOUND` | 404 | Không tìm thấy bình luận |
| `MAX_DEPTH_EXCEEDED` | 422 | Vượt quá độ sâu tối đa (2 cấp) |
| `ALREADY_RATED` | 409 | Đã đánh giá truyện này (dùng PUT để cập nhật) |
| `ALREADY_FOLLOWING` | 409 | Đã theo dõi |
| `NOT_FOLLOWING` | 400 | Chưa theo dõi |
| `BOOKMARK_EXISTS` | 409 | Đã bookmark truyện này |
| `CANNOT_FOLLOW_SELF` | 422 | Không thể tự theo dõi chính mình |
| `REPORT_NOT_FOUND` | 404 | Không tìm thấy báo cáo |
| `ALREADY_REPORTED` | 409 | Đã báo cáo nội dung này |
| `ALREADY_REACTED` | 409 | Đã reaction, dùng DELETE để xoá |
| `BOOKLIST_FULL` | 422 | Danh sách đã đạt tối đa 100 truyện |
| `SLUG_CONFLICT` | 409 | Slug danh sách đã tồn tại |

---

## End of Social Service Spec
