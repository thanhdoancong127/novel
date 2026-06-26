# Notification Service — Dịch vụ Thông báo

> **File:** 06-notification-service.md
> **Phần của:** metruyenchu rebuild spec series
> **Service:** `notification-service` | Port: `8085` | DB: `notif_db`

---

## 1. Tổng quan

Notification Service là trung tâm thông báo của platform, chịu trách nhiệm nhận các domain events từ các service khác qua RabbitMQ và phân phối thông báo đến người dùng qua 3 kênh: in-app (trong ứng dụng), push (Web Push API), và email (SMTP). Hỗ trợ quản lý preferences cho phép người dùng chọn loại thông báo và kênh nhận.

---

## 2. Kiến trúc

```
                         ┌────────────────────────────────────┐
                         │        Notification Service        │
                         │                                    │
                         │  ┌──────────────────────────────┐  │
                ┌────────┼──┤  EventConsumer               │  │
                │        │  │  @RabbitListener             │  │
                │        │  └──────────┬───────────────────┘  │
                │        │             │                       │
                │        │  ┌──────────▼───────────────────┐  │
                │        │  │  NotificationRouter          │  │
                │        │  │  (phân loại, enrich, route)  │  │
                │        │  └──┬──────┬─────────┬──────────┘  │
                │        │     │      │         │             │
     ┌──────────┴──┐    │  ┌──▼──┐ ┌──▼───┐ ┌──▼─────────┐  │
     │  RabbitMQ   │    │  │ In- │ │ Push │ │   Email    │  │
     │  Events     │────┼──┤ App │ │Sender│ │  Sender    │  │
     └─────────────┘    │  │ DB  │ │(Web  │ │(JavaMail)  │  │
                        │  │     │ │Push) │ │            │  │
                        │  └─────┘ └──────┘ └────────────┘  │
                        │                                    │
                        │  ┌──────────────────────────────┐  │
                        │  │  DigestService (weekly)      │  │
                        │  └──────────────────────────────┘  │
                        │                                    │
                        │  ┌──────────────────────────────┐  │
                        │  │  PreferenceService           │  │
                        │  │  (user channels x types)     │  │
                        │  └──────────────────────────────┘  │
                        └────────────────────────────────────┘
                                          │
                      ┌───────────────────┼───────────────────┐
                      │                   │                   │
                 ┌────▼────┐        ┌─────▼──────┐     ┌─────▼──────┐
                 │DB notif │        │Push Service │     │SMTP Server │
                 │ (in-app)│        │(Web Push)   │     │(SendGrid/  │
                 └─────────┘        └─────────────┘     │ AWS SES)   │
                                                        └────────────┘
```

---

## 3. Event Consumers

### 3.1 Danh sách Events

| Event | Publisher | Consumer Method | Notification Type |
|-------|-----------|----------------|-------------------|
| `story.chapter.published` | Story Service | `handleChapterPublished` | `NEW_CHAPTER` |
| `story.approved` | Story Service | `handleStoryApproved` | `STORY_APPROVED` |
| `audio.job.completed` | Audio Service | `handleAudioReady` | `AUDIO_READY` |
| `social.comment.created` | Social Service | `handleCommentCreated` | `COMMENT_REPLY` |
| `social.follow.created` | Social Service | `handleNewFollower` | `NEW_FOLLOWER` |
| `social.booklist.item.added` | Social Service | `handleBooklistItemAdded` | `NEW_BOOKLIST_ITEM` |
| `social.achievement.unlocked` | Social Service | `handleAchievementUnlocked` | `ACHIEVEMENT_UNLOCKED` |
| `system.announcement` | Admin Panel | `handleSystemAnnouncement` | `SYSTEM_ANNOUNCEMENT` |

### 3.2 Consumer Config

```yaml
notification:
  rabbitmq:
    exchange: notification.topic
    queues:
      chapter-events: notif.chapter.events
      audio-events: notif.audio.events
      social-events: notif.social.events
      system-events: notif.system.events
    routing:
      chapter-published: story.chapter.published
      chapter-approved: story.approved
      audio-completed: audio.job.completed
      comment-created: social.comment.created
      follow-created: social.follow.created
      booklist-item-added: social.booklist.item.added
      achievement-unlocked: social.achievement.unlocked
      system-announcement: system.announcement
```

```java
@RabbitListener(queues = "notif.chapter.events")
public void handleChapterPublished(ChapterPublishedEvent event) {
    // 1. Xác định target users: followers của tác giả
    // 2. Gọi Social Service: GET /api/v1/followers/{authorId}
    // 3. Kiểm tra preference từng user
    // 4. Gửi notification qua các channel đã chọn
}

@RabbitListener(queues = "notif.audio.events")
public void handleAudioReady(AudioJobCompletedEvent event) {
    // 1. Lấy thông tin story/chapter từ event payload
    // 2. Tìm user: author + readers đang theo dõi story
    // 3. Gửi thông báo audio đã sẵn sàng
}

@RabbitListener(queues = "notif.social.events")
public void handleCommentCreated(CommentCreatedEvent event) {
    // 1. Phân biệt: comment mới (notify author) hay reply (notify parent commenter)
    // 2. Enrich với thông tin chapter/story
    // 3. Gửi thông báo
}
```

### 3.3 Routing Logic

```java
@Service
public class NotificationRouter {

    private final Map<NotificationType, NotificationHandler> handlers;

    public void route(NotificationEvent event) {
        NotificationType type = event.getType();
        NotificationHandler handler = handlers.get(type);
        if (handler == null) {
            log.warn("No handler for notification type: {}", type);
            return;
        }

        // Kiểm tra user preference
        UserPreference pref = preferenceService.getPreference(event.getTargetUserId());
        if (!pref.isChannelEnabled(type, event.getChannel())) {
            log.debug("User {} disabled {} via {}", event.getTargetUserId(), type, event.getChannel());
            return;
        }

        // Gửi qua channel
        handler.send(event);
    }
}
```

---

## 4. Notification Types

### 4.1 Enum

```java
public enum NotificationType {
    NEW_CHAPTER,           // Chapter mới được đăng
    STORY_APPROVED,        // Truyện được duyệt
    AUDIO_READY,           // Audio TTS đã sẵn sàng
    COMMENT_REPLY,         // Có người reply comment
    NEW_FOLLOWER,          // Có người follow
    NEW_BOOKLIST_ITEM,     // Truyện được thêm vào booklist
    ACHIEVEMENT_UNLOCKED,  // Mở khóa thành tựu
    SYSTEM_ANNOUNCEMENT    // Thông báo hệ thống
}
```

### 4.2 Notification Content Templates

| Type | Title | Body | Icon |
|------|-------|------|------|
| `NEW_CHAPTER` | "Chương mới: {chapterTitle}" | "{storyTitle} — Tác giả vừa đăng chương {chapterNumber}" | `📖` |
| `STORY_APPROVED` | "Truyện đã được duyệt!" | "{storyTitle} đã được phê duyệt và có thể xuất bản" | `✅` |
| `AUDIO_READY` | "Audio đã sẵn sàng" | "Chương {chapterNumber} của {storyTitle} đã có bản audio" | `🎧` |
| `COMMENT_REPLY` | "{userName} đã trả lời bạn" | "Trong chapter {chapterNumber} của {storyTitle}" | `💬` |
| `NEW_FOLLOWER` | "{userName} đã theo dõi bạn" | "{followerCount} người đang theo dõi bạn" | `👤` |
| `NEW_BOOKLIST_ITEM` | "Truyện yêu thích mới" | "{storyTitle} đã được thêm vào booklist {booklistName}" | `📚` |
| `ACHIEVEMENT_UNLOCKED` | "Thành tựu mới!" | "Bạn đã mở khóa thành tựu {achievementName}" | `🏆` |
| `SYSTEM_ANNOUNCEMENT` | "{title}" | "{message}" | `🔔` |

---

## 5. Database Schema

### 5.1 Table: `notifications`

```sql
CREATE TABLE notifications (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    type            VARCHAR(30) NOT NULL,
    title           VARCHAR(200) NOT NULL,
    body            TEXT,
    icon            VARCHAR(10),
    link            VARCHAR(500),                   -- URL dẫn đến nội dung
    image_url       VARCHAR(500),                   -- Ảnh đại diện
    metadata        JSONB,                          -- { "storyId": 42, "chapterId": 128, ... }
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    read_at         TIMESTAMP WITH TIME ZONE,
    is_seen         BOOLEAN NOT NULL DEFAULT FALSE, -- User đã thấy trong dropdown
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_notif_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_notifications_user_unread
    ON notifications(user_id, is_read, created_at DESC)
    WHERE is_read = FALSE;
CREATE INDEX idx_notifications_user_all
    ON notifications(user_id, created_at DESC);
```

### 5.2 Table: `notification_prefs`

```sql
CREATE TABLE notification_prefs (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL UNIQUE,
    -- In-app channels (per type)
    in_app_new_chapter          BOOLEAN NOT NULL DEFAULT TRUE,
    in_app_story_approved       BOOLEAN NOT NULL DEFAULT TRUE,
    in_app_audio_ready          BOOLEAN NOT NULL DEFAULT TRUE,
    in_app_comment_reply        BOOLEAN NOT NULL DEFAULT TRUE,
    in_app_new_follower         BOOLEAN NOT NULL DEFAULT TRUE,
    in_app_new_booklist_item    BOOLEAN NOT NULL DEFAULT TRUE,
    in_app_achievement_unlocked BOOLEAN NOT NULL DEFAULT TRUE,
    in_app_system_announcement  BOOLEAN NOT NULL DEFAULT TRUE,
    -- Push channels (per type)
    push_new_chapter            BOOLEAN NOT NULL DEFAULT FALSE,
    push_story_approved         BOOLEAN NOT NULL DEFAULT FALSE,
    push_audio_ready            BOOLEAN NOT NULL DEFAULT FALSE,
    push_comment_reply          BOOLEAN NOT NULL DEFAULT TRUE,
    push_new_follower           BOOLEAN NOT NULL DEFAULT TRUE,
    push_new_booklist_item      BOOLEAN NOT NULL DEFAULT FALSE,
    push_achievement_unlocked   BOOLEAN NOT NULL DEFAULT TRUE,
    push_system_announcement    BOOLEAN NOT NULL DEFAULT TRUE,
    -- Email channels (per type)
    email_new_chapter            BOOLEAN NOT NULL DEFAULT FALSE,
    email_story_approved         BOOLEAN NOT NULL DEFAULT FALSE,
    email_audio_ready            BOOLEAN NOT NULL DEFAULT FALSE,
    email_comment_reply          BOOLEAN NOT NULL DEFAULT FALSE,
    email_new_follower           BOOLEAN NOT NULL DEFAULT FALSE,
    email_new_booklist_item      BOOLEAN NOT NULL DEFAULT FALSE,
    email_achievement_unlocked   BOOLEAN NOT NULL DEFAULT FALSE,
    email_system_announcement    BOOLEAN NOT NULL DEFAULT TRUE,
    -- Global switches
    digest_enabled              BOOLEAN NOT NULL DEFAULT FALSE,
    digest_frequency            VARCHAR(10) NOT NULL DEFAULT 'WEEKLY', -- WEEKLY | NEVER
    quiet_hours_enabled         BOOLEAN NOT NULL DEFAULT FALSE,
    quiet_hours_start           TIME DEFAULT '22:00',
    quiet_hours_end             TIME DEFAULT '07:00',
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_notif_pref_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_notif_prefs_user ON notification_prefs(user_id);
```

### 5.3 Table: `web_push_subscriptions`

```sql
CREATE TABLE web_push_subscriptions (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    endpoint        TEXT NOT NULL,
    p256dh_key      TEXT NOT NULL,
    auth_key        TEXT NOT NULL,
    user_agent      VARCHAR(500),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_push_sub_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uq_push_sub_endpoint UNIQUE (endpoint)
);

CREATE INDEX idx_push_sub_user ON web_push_subscriptions(user_id);
```

### 5.4 Flyway Migration

```sql
-- V1__create_notification_schema.sql
-- File đặt tại: notification-service/src/main/resources/db/migration/
```

---

## 6. API Endpoints

### 6.1 In-App Notifications

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `GET` | `/api/v1/notifications` | Danh sách thông báo (phân trang, sort mới nhất) | `USER` |
| `GET` | `/api/v1/notifications/unread/count` | Số thông báo chưa đọc | `USER` |
| `PUT` | `/api/v1/notifications/{id}/read` | Đánh dấu đã đọc 1 thông báo | `USER` |
| `PUT` | `/api/v1/notifications/read-all` | Đánh dấu tất cả đã đọc | `USER` |
| `PUT` | `/api/v1/notifications/{id}/seen` | Đánh dấu đã thấy | `USER` |
| `DELETE` | `/api/v1/notifications/{id}` | Xóa 1 thông báo | `USER` |
| `DELETE` | `/api/v1/notifications` | Xóa tất cả thông báo | `USER` |

**Request — Danh sách notifications:**
```
GET /api/v1/notifications?page=1&size=20&type=NEW_CHAPTER&unread=true
```

**Response:**
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 1,
      "type": "NEW_CHAPTER",
      "title": "Chương mới: Hồi Ức Đầu Tiên",
      "body": "Kiếm Lai — Tác giả vừa đăng chương 528",
      "icon": "📖",
      "link": "/truyen/kiem-lai/chuong-528",
      "metadata": {
        "storyId": 42,
        "chapterId": 528,
        "chapterNumber": 528
      },
      "isRead": false,
      "isSeen": false,
      "createdAt": "2025-06-01T10:00:00Z"
    }
  ],
  "pagination": {
    "page": 1,
    "size": 20,
    "totalElements": 5,
    "totalPages": 1
  }
}
```

**Response — Unread count:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "totalUnread": 12,
    "byType": {
      "NEW_CHAPTER": 5,
      "COMMENT_REPLY": 3,
      "NEW_FOLLOWER": 2,
      "ACHIEVEMENT_UNLOCKED": 1,
      "SYSTEM_ANNOUNCEMENT": 1
    }
  }
}
```

### 6.2 Push Notification Subscriptions

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `POST` | `/api/v1/push/subscribe` | Đăng ký subscription mới | `USER` |
| `PUT` | `/api/v1/push/subscribe/{id}` | Cập nhật subscription | `USER` |
| `DELETE` | `/api/v1/push/subscribe/{id}` | Hủy subscription | `USER` |
| `GET` | `/api/v1/push/subscriptions` | Danh sách subscriptions | `USER` |

**Request — Subscribe:**
```json
// POST /api/v1/push/subscribe
{
  "endpoint": "https://fcm.googleapis.com/...",
  "keys": {
    "p256dh": "BIPUL12DLf...",
    "auth": "kJ23dEf..."
  }
}
```

### 6.3 Notification Preferences

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `GET` | `/api/v1/notifications/preferences` | Lấy preferences | `USER` |
| `PUT` | `/api/v1/notifications/preferences` | Cập nhật preferences | `USER` |

**Request — Update preferences:**
```json
// PUT /api/v1/notifications/preferences
{
  "inApp": {
    "newChapter": true,
    "commentReply": true,
    "newFollower": true
  },
  "push": {
    "commentReply": true,
    "newFollower": true
  },
  "email": {
    "newChapter": true,
    "weeklyDigest": true
  },
  "quietHours": {
    "enabled": true,
    "start": "22:00",
    "end": "07:00"
  }
}
```

### 6.4 Admin

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `POST` | `/api/v1/notifications/admin/announcement` | Gửi system announcement đến tất cả users | `ADMIN` |
| `GET` | `/api/v1/notifications/admin/stats` | Thống kê notifications | `ADMIN` |

---

## 7. Push Notification (Web Push API)

### 7.1 VAPID Keys

```java
// Tạo VAPID keys lần đầu
// npx web-push generate-vapid-keys --json

// Hoặc dùng thư viện trong Java
VapidHelper.generateVapidKeys();
```

**Config:**
```yaml
notification:
  push:
    vapid:
      public-key: ${VAPID_PUBLIC_KEY}
      private-key: ${VAPID_PRIVATE_KEY}
      subject: mailto:admin@metruyenchu.dev
```

### 7.2 Send Push Notification

```java
@Service
public class PushSender {

    private final NotificationPreferenceRepository prefRepo;
    private final PushSubscriptionRepository subRepo;

    @Async("notificationTaskExecutor")
    public void send(NotificationEvent event) {
        List<PushSubscription> subs = subRepo.findByUserId(event.getTargetUserId());

        for (PushSubscription sub : subs) {
            try {
                PushPayload payload = PushPayload.builder()
                    .title(event.getTitle())
                    .body(event.getBody())
                    .icon(event.getIcon())
                    .badge("/icons/badge-72x72.png")
                    .data(Map.of(
                        "link", event.getLink(),
                        "type", event.getType().name(),
                        "metadata", event.getMetadata()
                    ))
                    .build();

                PushService.send(
                    new PushMessage(sub.getEndpoint(), sub.getKeys()),
                    payload
                );
            } catch (WebPushException e) {
                if (e.getStatusCode() == 410) {
                    // Subscription expired — xóa khỏi DB
                    subRepo.delete(sub);
                }
                log.warn("Push send failed for sub {}: {}", sub.getId(), e.getMessage());
            }
        }
    }
}
```

### 7.3 Push Payload

```json
{
  "title": "Chương mới: Hồi Ức Đầu Tiên",
  "body": "Kiếm Lai — Tác giả vừa đăng chương 528",
  "icon": "https://metruyenchu.dev/icons/icon-192x192.png",
  "badge": "https://metruyenchu.dev/icons/badge-72x72.png",
  "vibrate": [200, 100, 200],
  "data": {
    "link": "/truyen/kiem-lai/chuong-528",
    "type": "NEW_CHAPTER",
    "metadata": {
      "storyId": 42,
      "chapterId": 528
    }
  },
  "actions": [
    {
      "action": "open",
      "title": "Đọc ngay"
    }
  ]
}
```

---

## 8. Email Notification

### 8.1 Config

```yaml
notification:
  email:
    from: "MeTruyenChu <noreply@metruyenchu.dev>"
    reply-to: support@metruyenchu.dev
    smtp:
      host: ${SMTP_HOST:smtp.sendgrid.net}
      port: ${SMTP_PORT:587}
      username: ${SMTP_USERNAME:apikey}
      password: ${SMTP_PASSWORD}
      properties:
        mail.smtp.auth: true
        mail.smtp.starttls.enable: true
    templates:
      directory: classpath:/templates/email/
      suffix: .html
```

### 8.2 Template Engine (Thymeleaf)

```java
@Bean
public SpringResourceTemplateResolver emailTemplateResolver() {
    SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
    resolver.setPrefix("classpath:/templates/email/");
    resolver.setSuffix(".html");
    resolver.setTemplateMode(TemplateMode.HTML);
    resolver.setCharacterEncoding("UTF-8");
    resolver.setOrder(2);
    return resolver;
}
```

### 8.3 Email Templates

**8.3.1 New Chapter — `new-chapter.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
</head>
<body style="font-family: Arial, sans-serif; background: #f4f4f4; padding: 20px;">
  <div style="max-width: 600px; margin: 0 auto; background: white; border-radius: 8px; overflow: hidden;">
    <div style="background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 30px; text-align: center;">
      <h1 style="color: white; margin: 0; font-size: 24px;">Chương Mới Đã Đăng!</h1>
    </div>
    <div style="padding: 30px;">
      <h2 th:text="${storyTitle}" style="color: #333; margin: 0 0 10px;">Tên truyện</h2>
      <p style="color: #666; font-size: 16px; line-height: 1.6;">
        <strong th:text="${chapterTitle}">Tên chương</strong> —
        Chương <span th:text="${chapterNumber}">1</span> vừa được đăng tải.
      </p>
      <p style="color: #666; font-size: 14px;" th:text="${chapterDescription}">Mô tả ngắn...</p>
      <div style="text-align: center; margin: 30px 0;">
        <a th:href="${chapterUrl}"
           style="background: #667eea; color: white; padding: 12px 30px; text-decoration: none; border-radius: 5px; font-size: 16px; display: inline-block;">
          Đọc Ngay
        </a>
      </div>
      <hr style="border: none; border-top: 1px solid #eee; margin: 20px 0;">
      <p style="color: #999; font-size: 12px; text-align: center;">
        Bạn nhận được email này vì đang theo dõi truyện
        <span th:text="${storyTitle}">Tên truyện</span> trên MeTruyenChu.<br>
        <a th:href="${unsubscribeUrl}" style="color: #667eea;">Hủy theo dõi thông báo</a>
      </p>
    </div>
  </div>
</body>
</html>
```

**8.3.2 Weekly Digest — `weekly-digest.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8">
</head>
<body style="font-family: Arial, sans-serif; background: #f4f4f4; padding: 20px;">
  <div style="max-width: 600px; margin: 0 auto; background: white; border-radius: 8px; overflow: hidden;">
    <div style="background: linear-gradient(135deg, #43e97b 0%, #38f9d7 100%); padding: 30px; text-align: center;">
      <h1 style="color: white; margin: 0;">Tuần Của Bạn Trên MeTruyenChu</h1>
      <p style="color: rgba(255,255,255,0.8); margin: 10px 0 0;">
        <span th:text="${#temporals.format(#temporals.createNow().minusDays(7), 'dd/MM')}">01/06</span>
        —
        <span th:text="${#temporals.format(#temporals.createNow(), 'dd/MM')}">07/06</span>
      </p>
    </div>
    <div style="padding: 30px;">
      <h2 style="color: #333; font-size: 18px;">📖 Chương mới từ truyện bạn theo dõi</h2>
      <div th:each="story : ${newChapters}" style="margin: 15px 0; padding: 15px; background: #f9f9f9; border-radius: 5px;">
        <h3 style="margin: 0 0 5px; color: #333;" th:text="${story.title}">Tên truyện</h3>
        <p style="margin: 0; color: #666; font-size: 14px;" th:text="${story.chapterCount} + ' chương mới'">3 chương mới</p>
      </div>
      <hr style="border: none; border-top: 1px solid #eee; margin: 20px 0;">
      <h2 style="color: #333; font-size: 18px;">💬 Hoạt động gần đây</h2>
      <p style="color: #666;" th:text="${#strings.format(activitySummary, '5 comments, 2 followers')}">5 bình luận, 2 người theo dõi</p>
      <div style="text-align: center; margin: 30px 0;">
        <a href="https://metruyenchu.dev"
           style="background: linear-gradient(135deg, #43e97b 0%, #38f9d7 100%); color: white; padding: 12px 30px; text-decoration: none; border-radius: 5px; font-size: 16px; display: inline-block;">
          Khám Phá Ngay
        </a>
      </div>
    </div>
  </div>
</body>
</html>
```

**8.3.3 Comment Reply — `comment-reply.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8">
</head>
<body style="font-family: Arial, sans-serif; background: #f4f4f4; padding: 20px;">
  <div style="max-width: 600px; margin: 0 auto; background: white; border-radius: 8px; overflow: hidden; padding: 30px;">
    <div style="text-align: center; margin-bottom: 20px;">
      <img th:src="${replyerAvatar}" alt="" style="width: 64px; height: 64px; border-radius: 50%;">
      <h2 style="color: #333; margin: 10px 0 0;">
        <span th:text="${replyerName}">Người dùng</span> đã trả lời bạn
      </h2>
    </div>
    <div style="background: #f0f2f5; padding: 15px; border-radius: 8px; margin: 15px 0;">
      <p style="margin: 0; color: #333; font-style: italic; font-size: 14px;" th:text="${replyContent}">Nội dung reply...</p>
    </div>
    <div style="background: #fff; padding: 15px; border: 1px solid #eee; border-radius: 8px; margin: 15px 0;">
      <p style="margin: 0; color: #999; font-size: 13px;">Bạn đã viết:</p>
      <p style="margin: 5px 0 0; color: #666; font-size: 14px;" th:text="${originalContent}">Nội dung gốc...</p>
    </div>
    <div style="text-align: center; margin: 20px 0;">
      <a th:href="${replyUrl}" style="background: #667eea; color: white; padding: 12px 30px; text-decoration: none; border-radius: 5px; display: inline-block;">
        Xem bình luận
      </a>
    </div>
  </div>
</body>
</html>
```

**8.3.4 Audio Ready — `audio-ready.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8">
</head>
<body style="font-family: Arial, sans-serif; background: #f4f4f4; padding: 20px;">
  <div style="max-width: 600px; margin: 0 auto; background: white; border-radius: 8px; overflow: hidden;">
    <div style="background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%); padding: 30px; text-align: center;">
      <h1 style="color: white; margin: 0 0 10px;">🎧 Audio Đã Sẵn Sàng!</h1>
      <p style="color: rgba(255,255,255,0.9); margin: 0;">
        <span th:text="${storyTitle}">Tên truyện</span> — Chương <span th:text="${chapterNumber}">1</span>
      </p>
    </div>
    <div style="padding: 30px; text-align: center;">
      <p style="color: #666; line-height: 1.6;">
        Bản audio của chương <strong th:text="${chapterTitle}">Tên chương</strong> đã được tạo.
        <br>Thời lượng: <span th:text="${duration}">4 phút 5 giây</span>
      </p>
      <div style="margin: 30px 0;">
        <a th:href="${audioUrl}" style="background: #f5576c; color: white; padding: 15px 40px; text-decoration: none; border-radius: 25px; font-size: 16px; display: inline-block;">
          ▶ Nghe Ngay
        </a>
      </div>
    </div>
  </div>
</body>
</html>
```

### 8.4 Email Sending

```java
@Service
public class EmailSender {

    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;

    @Async
    public void sendNotification(NotificationEvent event) {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        Context context = new Context();
        context.setVariables(event.getTemplateVars());

        String html = templateEngine.process(event.getTemplateName(), context);

        helper.setTo(event.getTargetEmail());
        helper.setSubject(event.getTitle());
        helper.setText(html, true);
        helper.setFrom("MeTruyenChu <noreply@metruyenchu.dev>");

        mailSender.send(message);
    }
}
```

### 8.5 Weekly Digest Scheduler

```java
@Component
public class DigestScheduler {

    private final DigestService digestService;

    @Scheduled(cron = "0 0 8 * * MON") // 8h sáng thứ Hai hàng tuần
    public void sendWeeklyDigest() {
        // 1. Query users có digest_enabled = true
        // 2. Batch processing: mỗi batch 100 users
        // 3. Tổng hợp: new chapters, comments, followers trong 7 ngày qua
        // 4. Render template và gửi email
        digestService.generateAndSendDigests();
    }
}
```

---

## 9. Security

| Endpoint | Auth | Ghi chú |
|----------|------|---------|
| `/api/v1/notifications/**` | `USER` | In-app notifications |
| `/api/v1/push/**` | `USER` | Push subscription management |
| `/api/v1/notifications/preferences` | `USER` | Preferences |
| `/api/v1/notifications/admin/**` | `ADMIN` | System announcement, stats |

---

## 10. Error Codes

| Code | HTTP | Ý nghĩa |
|------|------|---------|
| `NOTIFICATION_NOT_FOUND` | 404 | Notification ID không tồn tại |
| `NOTIFICATION_NOT_OWNER` | 403 | Notification không thuộc user |
| `PUSH_SUB_NOT_FOUND` | 404 | Subscription không tồn tại |
| `PUSH_SEND_FAILED` | 502 | Lỗi gửi push notification |
| `EMAIL_SEND_FAILED` | 502 | Lỗi gửi email |
| `INVALID_TEMPLATE` | 500 | Template không tồn tại hoặc lỗi |

---

## 11. Environment Variables

| Variable | Default | Mô tả |
|----------|---------|-------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/notif_db` | DB URL |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ host |
| `RABBITMQ_PORT` | `5672` | RabbitMQ port |
| `VAPID_PUBLIC_KEY` | — | VAPID public key |
| `VAPID_PRIVATE_KEY` | — | VAPID private key |
| `VAPID_SUBJECT` | `mailto:admin@metruyenchu.dev` | VAPID subject |
| `SMTP_HOST` | `smtp.sendgrid.net` | SMTP host |
| `SMTP_PORT` | `587` | SMTP port |
| `SMTP_USERNAME` | `apikey` | SMTP username |
| `SMTP_PASSWORD` | — | SMTP password |
| `NOTIF_EMAIL_FROM` | `noreply@metruyenchu.dev` | Email sender |
| `DIGEST_ENABLED` | `true` | Bật/tắt weekly digest |

---

## 12. Gradle Dependencies

```groovy
// notification-service/build.gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-mail'
    implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'
    implementation 'org.springframework.boot:spring-boot-starter-amqp'
    implementation 'nl.martijndwars:web-push:5.1.1'
    implementation 'org.bouncycastle:bcprov-jdk18on:1.78'
    runtimeOnly 'org.postgresql:postgresql'
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-postgresql'
}
```

---

## 13. Quiet Hours

```java
public boolean isWithinQuietHours(UserPreference pref) {
    if (!pref.isQuietHoursEnabled()) return false;

    LocalTime now = LocalTime.now();
    LocalTime start = pref.getQuietHoursStart(); // 22:00
    LocalTime end = pref.getQuietHoursEnd();      // 07:00

    if (start.isBefore(end)) {
        // Normal range: 22:00 → 07:00 (crosses midnight)
        return now.isAfter(start) || now.isBefore(end);
    } else {
        // Same day range
        return now.isAfter(start) && now.isBefore(end);
    }
}
```

**Xử lý quiet hours:**
- In-app notifications: vẫn lưu vào DB, không ảnh hưởng
- Push notifications: queue lại và gửi khi hết quiet hours
- Email: queue lại và gửi vào sáng hôm sau

---

## End of Notification Service Spec
