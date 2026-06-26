# Auth Service — MeTruyenChu

> **File:** 02-auth-service.md
> **Phần của:** metruyenchu rebuild spec series
> **Công nghệ:** Spring Boot 3.4+, Spring Security 6, Spring Data JPA, Flyway, PostgreSQL 16

---

## 1. Tổng Quan

Auth Service chịu trách nhiệm toàn bộ luồng xác thực và phân quyền:

- **User management:** register, profile, account deletion
- **Authentication:** login (JWT pair), OAuth2 (Google, Facebook, Zalo), refresh token
- **Authorization:** roles (GUEST, READER, UPLOADER, MODERATOR, ADMIN)
- **Session management:** active sessions, revoke
- **Two-factor authentication:** TOTP (optional)
- **Password management:** forgot/reset password

**Tech stack:**

| Thành phần | Công nghệ | Ghi chú |
|-----------|-----------|---------|
| Framework | Spring Boot 3.4+ | |
| Security | Spring Security 6 | Method-level security |
| ORM | Spring Data JPA + Hibernate 6 | |
| DB | PostgreSQL 16 | `auth_db` |
| Migration | Flyway | |
| JWT | Nimbus JOSE + JWT | RS256 sign + verify |
| Email | Spring Mail + Thymeleaf | Template-based |
| Cache | Redis | Sessions, rate limit |
| API docs | SpringDoc OpenAPI | |

---

## 2. Entities

### 2.1 users

```sql
CREATE TABLE users (
    id              BIGSERIAL       PRIMARY KEY,
    username        VARCHAR(50)     NOT NULL,
    email           VARCHAR(255)    NOT NULL,
    password_hash   VARCHAR(255)    NOT NULL,
    display_name    VARCHAR(100),
    avatar_url      VARCHAR(500),
    bio             TEXT,
    email_verified  BOOLEAN         NOT NULL DEFAULT FALSE,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    is_deleted      BOOLEAN         NOT NULL DEFAULT FALSE,     -- soft delete
    deleted_at      TIMESTAMPTZ,
    two_factor_enabled BOOLEAN     NOT NULL DEFAULT FALSE,
    two_factor_secret VARCHAR(255),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE INDEX idx_users_email ON users(email) WHERE is_deleted = FALSE;
CREATE INDEX idx_users_username ON users(username) WHERE is_deleted = FALSE;
CREATE INDEX idx_users_created_at ON users(created_at);
```

### 2.2 roles

```sql
CREATE TABLE roles (
    id              SMALLSERIAL     PRIMARY KEY,
    code            VARCHAR(30)     NOT NULL,
    name            VARCHAR(100)    NOT NULL,
    description     VARCHAR(255),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_roles_code UNIQUE (code)
);

-- Seed data
INSERT INTO roles (code, name, description) VALUES
    ('GUEST',      'Khách',         'Người dùng chưa đăng nhập'),
    ('READER',     'Độc giả',       'Người đọc truyện'),
    ('UPLOADER',   'Người đăng',    'Người đăng và quản lý truyện'),
    ('MODERATOR',  'Kiểm duyệt',    'Người kiểm duyệt nội dung'),
    ('ADMIN',      'Quản trị',      'Quản trị viên hệ thống');
```

### 2.3 user_roles

```sql
CREATE TABLE user_roles (
    user_id         BIGINT          NOT NULL,
    role_id         SMALLINT        NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_ur_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ur_role FOREIGN KEY (role_id)
        REFERENCES roles(id) ON DELETE CASCADE
);

CREATE INDEX idx_user_roles_user_id ON user_roles(user_id);
```

### 2.4 user_sessions

```sql
CREATE TABLE user_sessions (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    refresh_token   VARCHAR(255)    NOT NULL,     -- hashed
    device_info     VARCHAR(500),                  -- User-Agent
    ip_address      VARCHAR(45),                   -- IPv6 compatible
    last_used_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ     NOT NULL,
    is_revoked      BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_us_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_user_sessions_user_id ON user_sessions(user_id);
CREATE UNIQUE INDEX idx_user_sessions_refresh_token
    ON user_sessions(refresh_token) WHERE is_revoked = FALSE;
CREATE INDEX idx_user_sessions_expires_at
    ON user_sessions(expires_at) WHERE is_revoked = FALSE;
```

### 2.5 oauth_accounts

```sql
CREATE TABLE oauth_accounts (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    provider        VARCHAR(30)     NOT NULL,     -- google, facebook, zalo
    provider_id     VARCHAR(255)    NOT NULL,     -- id từ provider
    email           VARCHAR(255),
    display_name    VARCHAR(100),
    avatar_url      VARCHAR(500),
    provider_data   JSONB,                        -- raw response từ provider
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_oa_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_oauth_provider UNIQUE (provider, provider_id)
);

CREATE INDEX idx_oauth_accounts_user_id ON oauth_accounts(user_id);
CREATE INDEX idx_oauth_accounts_provider ON oauth_accounts(provider, provider_id);
```

### 2.6 email_verifications

```sql
CREATE TABLE email_verifications (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    email           VARCHAR(255)    NOT NULL,
    token           VARCHAR(255)    NOT NULL,     -- UUID
    type            VARCHAR(30)     NOT NULL,     -- VERIFY_EMAIL, RESET_PASSWORD
    expires_at      TIMESTAMPTZ     NOT NULL,
    is_used         BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_ev_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_email_verifications_token ON email_verifications(token);
CREATE INDEX idx_email_verifications_user_id ON email_verifications(user_id);
```

### 2.7 activity_logs (audit)

```sql
CREATE TABLE activity_logs (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT,
    action          VARCHAR(50)     NOT NULL,     -- LOGIN, LOGOUT, REGISTER, etc.
    resource_type   VARCHAR(50),                  -- USER, SESSION, ROLE
    resource_id     BIGINT,
    details         JSONB,
    ip_address      VARCHAR(45),
    user_agent      VARCHAR(500),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_activity_logs_user_id ON activity_logs(user_id);
CREATE INDEX idx_activity_logs_action ON activity_logs(action);
CREATE INDEX idx_activity_logs_created_at ON activity_logs(created_at);
```

---

## 3. ER Diagram

```
┌──────────────┐       ┌──────────────────┐       ┌──────────────┐
│    roles     │       │    user_roles    │       │    users     │
│──────────────│       │──────────────────│       │──────────────│
│ id (PK)      │──1:N──│ user_id (PK,FK)  │──N:1──│ id (PK)      │
│ code         │       │ role_id (PK,FK)  │       │ username     │
│ name         │       │ created_at       │       │ email        │
│ description  │       └──────────────────┘       │ password_hash│
└──────────────┘                                   │ display_name │
                                                   │ avatar_url   │
┌──────────────────────┐      ┌─────────────┐      │ bio          │
│   user_sessions      │      │oauth_accounts│      │ email_verified│
│──────────────────────│      │─────────────│      │ is_active    │
│ id (PK)              │──N:1─│ id (PK)     │──N:1─│ is_deleted   │
│ user_id (FK)         │      │ user_id(FK) │      │ two_factor...│
│ refresh_token (hash) │      │ provider    │      │ created_at   │
│ device_info          │      │ provider_id │      │ updated_at   │
│ ip_address           │      │ email       │      └──────────────┘
│ last_used_at         │      │ display_name│           │
│ expires_at           │      │ avatar_url  │           │
│ is_revoked           │      │ provider_data│          │
│ created_at           │      │ created_at  │           │
└──────────────────────┘      │ updated_at  │           │
                              └─────────────┘           │
                                          ┌─────────────▼──────────────┐
                                          │   email_verifications     │
                                          │───────────────────────────│
                                          │ id (PK)                   │
                                          │ user_id (FK)              │
                                          │ email                     │
                                          │ token (UUID)              │
                                          │ type (VERIFY/RESET)       │
                                          │ expires_at                │
                                          │ is_used                   │
                                          │ created_at                │
                                          └───────────────────────────┘
```

---

## 4. API Endpoints

Tất cả endpoints sử dụng prefix `/api/v1`.

### 4.1 Public Endpoints (không cần auth)

#### POST /api/v1/auth/register

Đăng ký tài khoản mới.

**Request:**
```json
{
  "username": "nguyenvanA",
  "email": "nguyenvana@email.com",
  "password": "Str0ng!Pass123",
  "displayName": "Nguyễn Văn A"
}
```

**Validation rules:**
| Field | Rule | Error code |
|-------|------|------------|
| `username` | 3-50 ký tự, chỉ chứa chữ thường, số, `_`, `.` | `INVALID_USERNAME` |
| `email` | Email hợp lệ, max 255 ký tự | `INVALID_EMAIL` |
| `password` | 8-128 ký tự, cần 1 chữ hoa, 1 số, 1 ký tự đặc biệt | `WEAK_PASSWORD` |
| `displayName` | 2-100 ký tự (optional, mặc định = username) | `INVALID_DISPLAY_NAME` |

**Response 201:**
```json
{
  "code": 201,
  "message": "Registration successful. Please check your email to verify.",
  "data": {
    "userId": 12345,
    "email": "nguyenvana@email.com",
    "emailVerified": false
  }
}
```

**Error codes:**
| Status | Code | Message |
|--------|------|---------|
| 409 | `EMAIL_EXISTS` | Email already registered |
| 409 | `USERNAME_EXISTS` | Username already taken |
| 400 | `VALIDATION_ERROR` | Field validation failed |

---

#### POST /api/v1/auth/login

Đăng nhập, trả về JWT pair.

**Request:**
```json
{
  "login": "nguyenvanA",
  "password": "Str0ng!Pass123",
  "deviceInfo": "Mozilla/5.0 ...",
  "totpCode": "123456"
}
```

**Response 200:**
```json
{
  "code": 200,
  "message": "Login successful",
  "data": {
    "accessToken": "eyJhbGciOiJSUzI1NiIs...",
    "expiresIn": 900,
    "refreshToken": "550e8400-e29b-41d4-a716-446655440000",
    "tokenType": "Bearer",
    "user": {
      "id": 12345,
      "username": "nguyenvanA",
      "displayName": "Nguyễn Văn A",
      "avatarUrl": "https://cdn.metruyenchu.com/avatars/12345.jpg",
      "roles": ["READER"],
      "emailVerified": true,
      "twoFactorEnabled": false
    }
  }
}
```

**Error codes:**
| Status | Code | Message |
|--------|------|---------|
| 401 | `INVALID_CREDENTIALS` | Invalid username/email or password |
| 401 | `ACCOUNT_LOCKED` | Account temporarily locked (too many attempts) |
| 401 | `EMAIL_NOT_VERIFIED` | Please verify your email first |
| 428 | `TOTP_REQUIRED` | 2FA code required (trả về `sessionId` tạm) |
| 429 | `TOO_MANY_ATTEMPTS` | Rate limit exceeded |

**2FA flow:** Nếu `twoFactorEnabled = true`, lần gọi đầu trả về `428 TOTP_REQUIRED` + `sessionId`. Client gọi lại với `totpCode`.

---

#### POST /api/v1/auth/refresh

Làm mới access_token bằng refresh_token.

**Request** (httpOnly cookie hoặc request body):
```json
{
  "refreshToken": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response 200:**
```json
{
  "code": 200,
  "message": "Token refreshed",
  "data": {
    "accessToken": "eyJhbGciOiJSUzI1NiIs...",
    "expiresIn": 900,
    "refreshToken": "660e8400-e29b-41d4-a716-446655440001",
    "tokenType": "Bearer"
  }
}
```

**Error codes:**
| Status | Code | Message |
|--------|------|---------|
| 401 | `INVALID_REFRESH_TOKEN` | Token not found or invalid |
| 401 | `REFRESH_TOKEN_EXPIRED` | Token expired, please login again |
| 401 | `SESSION_REVOKED` | Session has been revoked |

---

#### POST /api/v1/auth/logout

Đăng xuất, hủy refresh token và session.

**Request:**
```json
{
  "refreshToken": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response 200:** `204 No Content`

---

#### GET /api/v1/auth/jwt-public-key

Lấy public key để Gateway verify JWT.

**Response 200:**
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

#### POST /api/v1/auth/forgot-password

Gửi email reset password.

**Request:**
```json
{
  "email": "nguyenvana@email.com"
}
```

**Response 200:**
```json
{
  "code": 200,
  "message": "If the email exists, a reset link has been sent."
}
```

---

#### POST /api/v1/auth/reset-password

Đặt lại password bằng token từ email.

**Request:**
```json
{
  "token": "550e8400-e29b-41d4-a716-446655440000",
  "newPassword": "NewStr0ng!Pass456"
}
```

**Response 200:**
```json
{
  "code": 200,
  "message": "Password has been reset successfully."
}
```

**Error codes:**
| Status | Code | Message |
|--------|------|---------|
| 400 | `INVALID_TOKEN` | Token invalid or expired |
| 400 | `WEAK_PASSWORD` | New password doesn't meet requirements |

---

#### GET /api/v1/auth/verify-email

Verify email bằng token.

**Query:**
```
GET /api/v1/auth/verify-email?token=550e8400-e29b-41d4-a716-446655440000
```

**Response 200:**
```json
{
  "code": 200,
  "message": "Email verified successfully."
}
```

**Error codes:**
| Status | Code | Message |
|--------|------|---------|
| 400 | `INVALID_TOKEN` | Token invalid or expired |

---

#### POST /api/v1/auth/resend-verification

Gửi lại email verification.

**Request:**
```json
{
  "email": "nguyenvana@email.com"
}
```

**Response 200:**
```json
{
  "code": 200,
  "message": "Verification email sent."
}
```

---

### 4.2 OAuth2 Endpoints

#### GET /oauth2/authorization/{provider}

Redirect đến provider OAuth2.

| Provider | URL |
|----------|-----|
| Google | `/oauth2/authorization/google` |
| Facebook | `/oauth2/authorization/facebook` |
| Zalo | `/oauth2/authorization/zalo` |

#### GET /oauth2/callback/{provider}

Callback từ OAuth2 provider.

**Success:** Redirect đến frontend với tokens trong URL fragment:
```
https://metruyenchu.com/auth/oauth2/callback#accessToken=...&refreshToken=...
```

**Error:** Redirect đến frontend với error:
```
https://metruyenchu.com/auth/oauth2/callback?error=ACCESS_DENIED
```

#### POST /api/v1/auth/oauth2/link

Liên kết tài khoản OAuth2 với user hiện tại (cần JWT).

**Request:**
```json
{
  "provider": "google",
  "providerCode": "auth_code_from_google"
}
```

**Response 200:**
```json
{
  "code": 200,
  "message": "OAuth account linked successfully."
}
```

---

### 4.3 User Profile Endpoints (cần JWT)

#### GET /api/v1/users/me

Lấy thông tin profile.

**Response 200:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 12345,
    "username": "nguyenvanA",
    "email": "nguyenvana@email.com",
    "displayName": "Nguyễn Văn A",
    "avatarUrl": "https://cdn.metruyenchu.com/avatars/12345.jpg",
    "bio": "Mọt sách chính hiệu",
    "emailVerified": true,
    "twoFactorEnabled": false,
    "roles": ["READER", "UPLOADER"],
    "oauthAccounts": [
      { "provider": "google", "email": "nguyenvana@gmail.com" }
    ],
    "stats": {
      "totalStoriesRead": 1234,
      "totalComments": 89,
      "totalBookmarks": 45,
      "totalFollows": 67,
      "memberSince": "2024-01-15T10:30:00Z"
    },
    "createdAt": "2024-01-15T10:30:00Z",
    "updatedAt": "2024-06-26T08:00:00Z"
  }
}
```

#### PUT /api/v1/users/me

Cập nhật profile.

**Request:**
```json
{
  "displayName": "Nguyễn Văn A (cập nhật)",
  "bio": "New bio",
  "avatarUrl": "https://cdn.metruyenchu.com/avatars/12345-new.jpg"
}
```

**Response 200:** (trả về user object như GET)

---

#### DELETE /api/v1/users/me

Xóa tài khoản (soft delete).

**Request:**
```json
{
  "password": "Str0ng!Pass123",
  "reason": "Tôi không còn sử dụng dịch vụ"
}
```

**Response 200:** `204 No Content`

---

#### POST /api/v1/users/me/change-password

Đổi mật khẩu.

**Request:**
```json
{
  "currentPassword": "Str0ng!Pass123",
  "newPassword": "NewStr0ng!Pass456"
}
```

**Response 200:** `204 No Content`

---

#### POST /api/v1/users/me/avatar

Upload avatar.

**Request:** `multipart/form-data`

| Field | Type | Rules |
|-------|------|-------|
| `file` | Image | JPEG/PNG/WebP, max 2MB, 500x500px |

**Response 200:**
```json
{
  "code": 200,
  "message": "Avatar updated",
  "data": {
    "avatarUrl": "https://cdn.metruyenchu.com/avatars/12345.jpg"
  }
}
```

---

### 4.4 Session Management (cần JWT)

#### GET /api/v1/auth/sessions

Danh sách active sessions.

**Response 200:**
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 1,
      "deviceInfo": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)...",
      "ipAddress": "192.168.1.1",
      "lastUsedAt": "2024-06-26T07:55:00Z",
      "createdAt": "2024-06-25T10:30:00Z",
      "expiresAt": "2024-07-02T10:30:00Z",
      "isCurrent": true
    },
    {
      "id": 2,
      "deviceInfo": "Mozilla/5.0 (Linux; Android 14)...",
      "ipAddress": "10.0.0.1",
      "lastUsedAt": "2024-06-24T18:20:00Z",
      "createdAt": "2024-06-20T09:00:00Z",
      "expiresAt": "2024-06-27T09:00:00Z",
      "isCurrent": false
    }
  ]
}
```

#### DELETE /api/v1/auth/sessions/{sessionId}

Thu hồi một session cụ thể.

**Response 200:** `204 No Content`

#### DELETE /api/v1/auth/sessions

Thu hồi tất cả sessions (trừ session hiện tại).

**Response 200:** `204 No Content`

---

### 4.5 2FA Endpoints (cần JWT)

#### POST /api/v1/auth/2fa/enable

Kích hoạt 2FA TOTP.

**Request:**
```json
{
  "password": "Str0ng!Pass123"
}
```

**Response 200:**
```json
{
  "code": 200,
  "message": "Scan the QR code with your authenticator app",
  "data": {
    "secret": "JBSWY3DPEHPK3PXP",
    "qrCodeUrl": "otpauth://totp/MeTruyenChu:nguyenvana@email.com?secret=JBSWY3DPEHPK3PXP&issuer=MeTruyenChu",
    "recoveryCodes": ["ABCD-1234", "EFGH-5678", "IJKL-9012", "MNOP-3456", "QRST-7890"]
  }
}
```

#### POST /api/v1/auth/2fa/verify

Xác nhận kích hoạt 2FA bằng TOTP code.

**Request:**
```json
{
  "totpCode": "123456"
}
```

**Response 200:**
```json
{
  "code": 200,
  "message": "2FA enabled successfully."
}
```

#### POST /api/v1/auth/2fa/disable

Tắt 2FA.

**Request:**
```json
{
  "password": "Str0ng!Pass123",
  "totpCode": "123456"
}
```

**Response 200:** `204 No Content`

#### POST /api/v1/auth/2fa/recovery

Sử dụng recovery code để đăng nhập khi mất 2FA.

**Request:**
```json
{
  "recoveryCode": "ABCD-1234",
  "sessionId": "temporary_session_id"
}
```

**Response 200:** (trả về JWT pair như login)

---

### 4.6 Admin Endpoints (cần ADMIN role)

#### GET /api/v1/admin/users

Danh sách users (phân trang, filter).

**Parameters:**
| Param | Type | Mô tả |
|-------|------|-------|
| `page` | int | Page number (1-indexed) |
| `size` | int | Page size (max 100) |
| `search` | string | Search by username/email |
| `role` | string | Filter by role code |
| `status` | string | `ACTIVE`, `BANNED`, `DELETED` |
| `sort` | string | `createdAt,desc` |

**Response 200:**
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 12345,
      "username": "nguyenvanA",
      "email": "nguyenvana@email.com",
      "displayName": "Nguyễn Văn A",
      "roles": ["READER"],
      "isActive": true,
      "emailVerified": true,
      "createdAt": "2024-01-15T10:30:00Z"
    }
  ],
  "pagination": {
    "page": 1,
    "size": 20,
    "totalElements": 150,
    "totalPages": 8
  }
}
```

#### PUT /api/v1/admin/users/{userId}/roles

Cập nhật roles cho user.

**Request:**
```json
{
  "roles": ["READER", "UPLOADER"]
}
```

**Response 200:** `204 No Content`

#### POST /api/v1/admin/users/{userId}/ban

Ban/unban user.

**Request:**
```json
{
  "reason": "Spam comments",
  "duration": "7d"
}
```

**Response 200:** `204 No Content`

---

## 5. Spring Security Configuration

### 5.1 SecurityConfig

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers(
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/forgot-password",
                    "/api/v1/auth/reset-password",
                    "/api/v1/auth/verify-email",
                    "/api/v1/auth/resend-verification",
                    "/api/v1/auth/jwt-public-key",
                    "/oauth2/**",
                    "/login/oauth2/**",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/actuator/**"
                ).permitAll()
                // Admin only
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                // Any other request needs authentication
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .authorizationEndpoint(auth -> auth
                    .baseUri("/oauth2/authorization"))
                .redirectionEndpoint(redir -> redir
                    .baseUri("/oauth2/callback/*"))
                .successHandler(oAuth2SuccessHandler())
                .failureHandler(oAuth2FailureHandler())
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, authEx) -> {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    res.getWriter().write(
                        "{\"code\":401,\"message\":\"Unauthorized\"}");
                })
            );

        return http.build();
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
            "https://metruyenchu.com",
            "http://localhost:3000"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
```

### 5.2 Method Security

```java
@PreAuthorize("hasRole('ADMIN')")
@GetMapping("/api/v1/admin/users")
public List<UserDto> listUsers() { ... }

@PreAuthorize("hasRole('UPLOADER') or hasRole('ADMIN')")
@PostMapping("/api/v1/stories")
public StoryDto createStory() { ... }
```

---

## 6. JWT Implementation

### 6.1 JWT Properties

```yaml
app:
  jwt:
    private-key: classpath:keys/private.pem
    public-key: classpath:keys/public.pem
    access-token-expiration: 900        # 15 phút
    refresh-token-expiration: 604800    # 7 ngày
    issuer: metruyenchu
    key-id: "2024-06-v1"
```

### 6.2 RSA Key Generation

```bash
# Generate RSA key pair (2048-bit)
openssl genrsa -out private.pem 2048
openssl rsa -in private.pem -pubout -out public.pem
```

### 6.3 JWT Service

```java
@Service
public class JwtService {

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final JWTClaimsSet.Builder claimsBuilder;

    public JwtService(
            @Value("${app.jwt.private-key}") Resource privateKeyResource,
            @Value("${app.jwt.public-key}") Resource publicKeyResource,
            @Value("${app.jwt.issuer}") String issuer,
            @Value("${app.jwt.key-id}") String keyId) throws Exception {

        this.privateKey = (RSAPrivateKey) PemUtils.parsePrivateKey(
                new String(privateKeyResource.getInputStream().readAllBytes()));
        this.publicKey = (RSAPublicKey) PemUtils.parsePublicKey(
                new String(publicKeyResource.getInputStream().readAllBytes()));

        this.claimsBuilder = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .claim("keyId", keyId);
    }

    public String generateAccessToken(User user) {
        JWTClaimsSet claims = claimsBuilder
                .subject(user.getId().toString())
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 900_000))
                .claim("roles", user.getRoles().stream()
                        .map(Role::getCode).toList())
                .claim("displayName", user.getDisplayName())
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID("2024-06-v1")
                        .type(JOSEObjectType.JWT)
                        .build(),
                claims);
        signedJWT.sign(new RSASSASigner(privateKey));
        return signedJWT.serialize();
    }

    public RefreshToken generateRefreshToken(User user, String deviceInfo, String ip) {
        String token = UUID.randomUUID().toString();
        // Lưu hash vào DB
        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setRefreshToken(hashToken(token));
        session.setDeviceInfo(deviceInfo);
        session.setIpAddress(ip);
        session.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        sessionRepository.save(session);
        return new RefreshToken(token, session);
    }

    private String hashToken(String token) {
        return DigestUtils.sha256Hex(token);
    }

    // JWT → Claims
    public JWTClaimsSet verifyAndGetClaims(String token) throws Exception {
        SignedJWT signedJWT = SignedJWT.parse(token);
        JWSVerifier verifier = new RSASSAVerifier(publicKey);
        if (!signedJWT.verify(verifier)) {
            throw new SecurityException("Invalid JWT signature");
        }
        JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
        if (new Date().after(claims.getExpirationTime())) {
            throw new SecurityException("JWT expired");
        }
        return claims;
    }
}
```

---

## 7. Rate Limiting — Bucket4j

Auth Service áp dụng rate limiting ở endpoint level (bổ sung cho Gateway).

| Endpoint | Rate | Burst | Key |
|----------|------|-------|-----|
| `POST /api/v1/auth/login` | 5/min | 10 | IP |
| `POST /api/v1/auth/register` | 3/min | 5 | IP |
| `POST /api/v1/auth/forgot-password` | 3/min | 5 | IP |
| `POST /api/v1/auth/resend-verification` | 3/min | 5 | IP |
| `POST /api/v1/auth/refresh` | 10/min | 20 | IP |
| `POST /api/v1/auth/2fa/enable` | 3/min | 5 | User |
| `POST /api/v1/auth/2fa/verify` | 10/min | 15 | User |
| `POST /api/v1/auth/logout` | 10/min | 20 | User |
| `PUT /api/v1/users/me` | 5/min | 10 | User |

**429 Response:**
```json
{
  "code": 429,
  "message": "Too many requests. Please try again later.",
  "errorCode": "RATE_LIMIT_EXCEEDED",
  "retryAfter": 45
}
```

---

## 8. OAuth2 Configuration

### 8.1 application.yml

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID}
            client-secret: ${GOOGLE_CLIENT_SECRET}
            scope:
              - email
              - profile
            redirect-uri: "{baseUrl}/oauth2/callback/google"

          facebook:
            client-id: ${FACEBOOK_CLIENT_ID}
            client-secret: ${FACEBOOK_CLIENT_SECRET}
            scope:
              - email
              - public_profile
            redirect-uri: "{baseUrl}/oauth2/callback/facebook"

          zalo:
            client-id: ${ZALO_CLIENT_ID}
            client-secret: ${ZALO_CLIENT_SECRET}
            authorization-grant-type: authorization_code
            scope:
              - email
              - profile
            redirect-uri: "{baseUrl}/oauth2/callback/zalo"
            client-authentication-method: post

        provider:
          google:
            authorization-uri: https://accounts.google.com/o/oauth2/v2/auth
            token-uri: https://oauth2.googleapis.com/token
            user-info-uri: https://www.googleapis.com/oauth2/v3/userinfo

          facebook:
            authorization-uri: https://www.facebook.com/v19.0/dialog/oauth
            token-uri: https://graph.facebook.com/v19.0/oauth/access_token
            user-info-uri: https://graph.facebook.com/me?fields=id,name,email,picture

          zalo:
            authorization-uri: https://oauth.zaloapp.com/v4/permission
            token-uri: https://oauth.zaloapp.com/v4/access_token
            user-info-uri: https://graph.zalo.me/v2.0/me
```

### 8.2 OAuth2SuccessHandler

```java
@Component
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtService jwtService;
    private final UserService userService;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException {

        OAuth2User oauthUser = (OAuth2User) authentication.getPrincipal();
        String provider = (String) authentication.getDetails();
        String providerId = oauthUser.getName();
        String email = oauthUser.getAttribute("email");
        String name = oauthUser.getAttribute("name");

        // Find or create user
        User user = userService.findOrCreateOAuthUser(provider, providerId, email, name);

        // Generate JWT
        String accessToken = jwtService.generateAccessToken(user);
        RefreshToken refreshToken = jwtService.generateRefreshToken(
                user, request.getHeader("User-Agent"), request.getRemoteAddr());

        // Redirect to frontend with tokens
        String redirectUrl = UriComponentsBuilder
                .fromUriString("https://metruyenchu.com/auth/oauth2/callback")
                .fragment("accessToken=" + accessToken
                        + "&refreshToken=" + refreshToken.getToken())
                .build().toUriString();

        getRedirectStrategy().sendRedirect(request, response, redirectUrl);
    }
}
```

### 8.3 Account Linking Logic

```java
public User findOrCreateOAuthUser(
        String provider, String providerId, String email, String name) {

    // 1. Check if OAuth account already exists
    Optional<OAuthAccount> existing = oauthAccountRepository
            .findByProviderAndProviderId(provider, providerId);
    if (existing.isPresent()) {
        return existing.get().getUser();
    }

    // 2. Check if email already registered
    Optional<User> userByEmail = userRepository.findByEmail(email);
    if (userByEmail.isPresent()) {
        // Link OAuth to existing account
        User user = userByEmail.get();
        linkOAuthAccount(user, provider, providerId, email, name);
        return user;
    }

    // 3. Create new user
    User newUser = new User();
    newUser.setUsername(generateUniqueUsername(name));
    newUser.setEmail(email);
    newUser.setPasswordHash(""); // OAuth users have no password
    newUser.setDisplayName(name);
    newUser.setEmailVerified(true); // OAuth email is pre-verified
    newUser.setActive(true);
    userRepository.save(newUser);

    // Assign default role
    Role readerRole = roleRepository.findByCode("READER")
            .orElseThrow(() -> new RuntimeException("Default role not found"));
    userRoleRepository.save(new UserRole(newUser.getId(), readerRole.getId()));

    // Create OAuth account record
    linkOAuthAccount(newUser, provider, providerId, email, name);

    return newUser;
}
```

---

## 9. Email Templates (Thymeleaf)

### 9.1 Email Verification

**Template:** `email/verification.html`

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head><meta charset="UTF-8"></head>
<body>
  <h2>Xác thực email — MeTruyenChu</h2>
  <p>Chào <span th:text="${displayName}">User</span>,</p>
  <p>Vui lòng click vào link dưới đây để xác thực email của bạn:</p>
  <a th:href="${verificationUrl}"
     style="padding:12px 24px; background:#2563eb; color:white; text-decoration:none; border-radius:6px;">
    Xác thực email
  </a>
  <p>Link hết hạn sau 24 giờ.</p>
  <p style="color:#666; font-size:12px;">
    Nếu bạn không đăng ký tài khoản, vui lòng bỏ qua email này.
  </p>
</body>
</html>
```

### 9.2 Password Reset

**Template:** `email/reset-password.html`

```html
<body>
  <h2>Đặt lại mật khẩu — MeTruyenChu</h2>
  <p>Chào <span th:text="${displayName}">User</span>,</p>
  <p>Click vào link dưới đây để đặt lại mật khẩu:</p>
  <a th:href="${resetUrl}"
     style="padding:12px 24px; background:#2563eb; color:white; text-decoration:none; border-radius:6px;">
    Đặt lại mật khẩu
  </a>
  <p>Link hết hạn sau 1 giờ.</p>
  <p style="color:#666;">Nếu bạn không yêu cầu đặt lại mật khẩu, vui lòng bỏ qua email này.</p>
</body>
```

### 9.3 Email Config

```yaml
spring:
  mail:
    host: ${SMTP_HOST:smtp.gmail.com}
    port: 587
    username: ${SMTP_USERNAME}
    password: ${SMTP_PASSWORD}
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true
          connectiontimeout: 5000
          timeout: 5000
          writetimeout: 5000
    default-encoding: UTF-8

app:
  email:
    from: "MeTruyenChu <noreply@metruyenchu.com>"
    verification-url: "https://metruyenchu.com/auth/verify-email?token="
    reset-url: "https://metruyenchu.com/auth/reset-password?token="
```

---

## 10. Flyway Migrations

### 10.1 Migration Files

```
services/auth-service/src/main/resources/db/migration/
├── V1__create_users_table.sql
├── V2__create_roles_table.sql
├── V3__seed_roles.sql
├── V4__create_user_roles_table.sql
├── V5__create_user_sessions_table.sql
├── V6__create_oauth_accounts_table.sql
├── V7__create_email_verifications_table.sql
├── V8__create_activity_logs_table.sql
├── V9__add_two_factor_columns.sql
├── V10__add_user_stats_columns.sql
├── V11__add_user_deleted_columns.sql
└── V12__create_indexes.sql
```

### 10.2 Migration Details

#### V1__create_users_table.sql
```sql
CREATE TABLE users (
    id              BIGSERIAL       PRIMARY KEY,
    username        VARCHAR(50)     NOT NULL,
    email           VARCHAR(255)    NOT NULL,
    password_hash   VARCHAR(255)    NOT NULL,
    display_name    VARCHAR(100),
    avatar_url      VARCHAR(500),
    bio             TEXT,
    email_verified  BOOLEAN         NOT NULL DEFAULT FALSE,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    is_deleted      BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email UNIQUE (email)
);
```

#### V2__create_roles_table.sql
```sql
CREATE TABLE roles (
    id              SMALLSERIAL     PRIMARY KEY,
    code            VARCHAR(30)     NOT NULL,
    name            VARCHAR(100)    NOT NULL,
    description     VARCHAR(255),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_roles_code UNIQUE (code)
);
```

#### V3__seed_roles.sql
```sql
INSERT INTO roles (code, name, description) VALUES
    ('GUEST',      'Khách',         'Người dùng chưa đăng nhập'),
    ('READER',     'Độc giả',       'Người đọc truyện'),
    ('UPLOADER',   'Người đăng',    'Người đăng và quản lý truyện'),
    ('MODERATOR',  'Kiểm duyệt',    'Người kiểm duyệt nội dung'),
    ('ADMIN',      'Quản trị',      'Quản trị viên hệ thống');
```

#### V4__create_user_roles_table.sql
```sql
CREATE TABLE user_roles (
    user_id         BIGINT          NOT NULL,
    role_id         SMALLINT        NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_ur_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ur_role FOREIGN KEY (role_id)
        REFERENCES roles(id) ON DELETE CASCADE
);
```

#### V5__create_user_sessions_table.sql
```sql
CREATE TABLE user_sessions (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    refresh_token   VARCHAR(255)    NOT NULL,
    device_info     VARCHAR(500),
    ip_address      VARCHAR(45),
    last_used_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ     NOT NULL,
    is_revoked      BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_us_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
);
```

#### V6__create_oauth_accounts_table.sql
```sql
CREATE TABLE oauth_accounts (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    provider        VARCHAR(30)     NOT NULL,
    provider_id     VARCHAR(255)    NOT NULL,
    email           VARCHAR(255),
    display_name    VARCHAR(100),
    avatar_url      VARCHAR(500),
    provider_data   JSONB,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_oa_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_oauth_provider UNIQUE (provider, provider_id)
);
```

#### V7__create_email_verifications_table.sql
```sql
CREATE TABLE email_verifications (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    email           VARCHAR(255)    NOT NULL,
    token           VARCHAR(255)    NOT NULL,
    type            VARCHAR(30)     NOT NULL,
    expires_at      TIMESTAMPTZ     NOT NULL,
    is_used         BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_ev_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
);
```

#### V8__create_activity_logs_table.sql
```sql
CREATE TABLE activity_logs (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT,
    action          VARCHAR(50)     NOT NULL,
    resource_type   VARCHAR(50),
    resource_id     BIGINT,
    details         JSONB,
    ip_address      VARCHAR(45),
    user_agent      VARCHAR(500),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);
```

#### V9__add_two_factor_columns.sql
```sql
ALTER TABLE users
    ADD COLUMN two_factor_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN two_factor_secret VARCHAR(255);
```

#### V10__add_user_stats_columns.sql
```sql
ALTER TABLE users
    ADD COLUMN total_stories_read INT NOT NULL DEFAULT 0,
    ADD COLUMN total_comments INT NOT NULL DEFAULT 0,
    ADD COLUMN total_bookmarks INT NOT NULL DEFAULT 0,
    ADD COLUMN total_follows INT NOT NULL DEFAULT 0;
```

#### V11__add_user_deleted_columns.sql
```sql
ALTER TABLE users
    ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_reason VARCHAR(500),
    ADD COLUMN recovery_token VARCHAR(255),
    ADD COLUMN recovery_expires_at TIMESTAMPTZ;
```

#### V12__create_indexes.sql
```sql
-- Users
CREATE INDEX idx_users_email ON users(email) WHERE is_deleted = FALSE;
CREATE INDEX idx_users_username ON users(username) WHERE is_deleted = FALSE;
CREATE INDEX idx_users_created_at ON users(created_at);

-- User roles
CREATE INDEX idx_user_roles_user_id ON user_roles(user_id);

-- Sessions
CREATE INDEX idx_user_sessions_user_id ON user_sessions(user_id);
CREATE UNIQUE INDEX idx_user_sessions_refresh_token
    ON user_sessions(refresh_token) WHERE is_revoked = FALSE;
CREATE INDEX idx_user_sessions_expires_at
    ON user_sessions(expires_at) WHERE is_revoked = FALSE;

-- OAuth
CREATE INDEX idx_oauth_accounts_user_id ON oauth_accounts(user_id);
CREATE INDEX idx_oauth_accounts_provider
    ON oauth_accounts(provider, provider_id);

-- Email verifications
CREATE UNIQUE INDEX idx_email_verifications_token
    ON email_verifications(token);
CREATE INDEX idx_email_verifications_user_id
    ON email_verifications(user_id);

-- Activity logs
CREATE INDEX idx_activity_logs_user_id ON activity_logs(user_id);
CREATE INDEX idx_activity_logs_action ON activity_logs(action);
CREATE INDEX idx_activity_logs_created_at ON activity_logs(created_at);
```

---

## 11. DTOs

### 11.1 Request DTOs

```java
@Data
public class RegisterRequest {
    @NotBlank @Size(min = 3, max = 50)
    @Pattern(regexp = "^[a-z0-9_.]+$")
    private String username;

    @NotBlank @Email @Size(max = 255)
    private String email;

    @NotBlank @Size(min = 8, max = 128)
    private String password;

    @Size(min = 2, max = 100)
    private String displayName;
}

@Data
public class LoginRequest {
    @NotBlank
    private String login;          // username or email

    @NotBlank
    private String password;

    private String deviceInfo;
    private String totpCode;       // optional, for 2FA
}

@Data
public class RefreshTokenRequest {
    @NotBlank
    private String refreshToken;
}

@Data
public class ForgotPasswordRequest {
    @NotBlank @Email
    private String email;
}

@Data
public class ResetPasswordRequest {
    @NotBlank
    private String token;

    @NotBlank @Size(min = 8, max = 128)
    private String newPassword;
}

@Data
public class ChangePasswordRequest {
    @NotBlank
    private String currentPassword;

    @NotBlank @Size(min = 8, max = 128)
    private String newPassword;
}

@Data
public class Enable2faRequest {
    @NotBlank
    private String password;
}

@Data
public class Verify2faRequest {
    @NotBlank
    @Pattern(regexp = "^\\d{6}$")
    private String totpCode;
}

@Data
public class Disable2faRequest {
    @NotBlank
    private String password;

    @NotBlank
    @Pattern(regexp = "^\\d{6}$")
    private String totpCode;
}

@Data
public class UpdateProfileRequest {
    @Size(min = 2, max = 100)
    private String displayName;

    @Size(max = 500)
    private String bio;

    @Size(max = 500)
    private String avatarUrl;
}

@Data
public class LinkOAuthRequest {
    @NotBlank
    private String provider;

    @NotBlank
    private String providerCode;
}
```

### 11.2 Response DTOs

```java
@Data
@Builder
public class AuthResponse {
    private String accessToken;
    private int expiresIn;
    private String refreshToken;
    private String tokenType;
    private UserDto user;
}

@Data
@Builder
public class UserDto {
    private Long id;
    private String username;
    private String email;
    private String displayName;
    private String avatarUrl;
    private String bio;
    private boolean emailVerified;
    private boolean twoFactorEnabled;
    private List<String> roles;
    private List<OAuthAccountDto> oauthAccounts;
    private UserStatsDto stats;
    private Instant createdAt;
    private Instant updatedAt;
}

@Data
@Builder
public class OAuthAccountDto {
    private String provider;
    private String email;
}

@Data
@Builder
public class UserStatsDto {
    private int totalStoriesRead;
    private int totalComments;
    private int totalBookmarks;
    private int totalFollows;
    private Instant memberSince;
}

@Data
@Builder
public class SessionDto {
    private Long id;
    private String deviceInfo;
    private String ipAddress;
    private Instant lastUsedAt;
    private Instant createdAt;
    private Instant expiresAt;
    private boolean isCurrent;
}

@Data
@Builder
public class TwoFactorSetupDto {
    private String secret;
    private String qrCodeUrl;
    private List<String> recoveryCodes;
}
```

---

## 12. Service Layer

### 12.1 AuthService

```java
@Service
@Transactional
public class AuthService {

    public AuthResponse login(LoginRequest request, String ip, String userAgent);
    public AuthResponse refresh(RefreshTokenRequest request);
    public void logout(String refreshToken);
    public AuthResponse register(RegisterRequest request, String ip);
    public void verifyEmail(String token);
    public void resendVerification(String email);
    public void forgotPassword(String email);
    public void resetPassword(ResetPasswordRequest request);
    public TwoFactorSetupDto enable2fa(Long userId, String password);
    public void verify2faSetup(Long userId, String totpCode);
    public void disable2fa(Long userId, Disable2faRequest request);
    public AuthResponse verify2faLogin(String sessionId, String totpCode);
    public AuthResponse recover2fa(String sessionId, String recoveryCode);
    public List<SessionDto> getActiveSessions(Long userId, Long currentSessionId);
    public void revokeSession(Long userId, Long sessionId);
    public void revokeAllSessions(Long userId, Long currentSessionId);
}
```

---

## 13. Exception Handling

### 13.1 Custom Exceptions

```java
public class BusinessException extends RuntimeException {
    private final int status;
    private final String errorCode;
}

// Specific exceptions
public class InvalidCredentialsException extends BusinessException {
    public InvalidCredentialsException() {
        super(401, "INVALID_CREDENTIALS", "Invalid username/email or password");
    }
}

public class EmailNotVerifiedException extends BusinessException {
    public EmailNotVerifiedException() {
        super(401, "EMAIL_NOT_VERIFIED", "Please verify your email first");
    }
}

public class TotpRequiredException extends BusinessException {
    private final String sessionId;
    public TotpRequiredException(String sessionId) {
        super(428, "TOTP_REQUIRED", "2FA code required");
        this.sessionId = sessionId;
    }
}

public class AccountLockedException extends BusinessException {
    public AccountLockedException() {
        super(401, "ACCOUNT_LOCKED", "Account temporarily locked");
    }
}

public class RateLimitExceededException extends BusinessException {
    public RateLimitExceededException(long retryAfter) {
        super(429, "RATE_LIMIT_EXCEEDED",
              "Too many requests. Retry after " + retryAfter + "s");
    }
}
```

### 13.2 Global Exception Handler

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus()).body(
            ErrorResponse.builder()
                .code(ex.getStatus())
                .message(ex.getMessage())
                .errorCode(ex.getErrorCode())
                .build()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> new FieldError(e.getField(), e.getDefaultMessage()))
            .collect(Collectors.toList());
        return ResponseEntity.badRequest().body(
            ErrorResponse.builder()
                .code(400)
                .message("Validation failed")
                .errors(errors)
                .build()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception ex) {
        return ResponseEntity.status(500).body(
            ErrorResponse.builder()
                .code(500)
                .message("Internal server error")
                .traceId(TracingContext.current().getTraceId())
                .build()
        );
    }
}
```

---

## 14. Cấu Trúc Project

```
services/auth-service/
├── build.gradle.kts
├── src/
│   ├── main/
│   │   ├── java/com/metruyenchu/auth/
│   │   │   ├── AuthApplication.java
│   │   │   ├── config/
│   │   │   │   ├── SecurityConfig.java
│   │   │   │   ├── RateLimitingConfig.java
│   │   │   │   └── MailConfig.java
│   │   │   ├── controller/
│   │   │   │   ├── AuthController.java
│   │   │   │   ├── UserController.java
│   │   │   │   ├── SessionController.java
│   │   │   │   ├── TwoFactorController.java
│   │   │   │   └── AdminUserController.java
│   │   │   ├── service/
│   │   │   │   ├── AuthService.java
│   │   │   │   ├── UserService.java
│   │   │   │   ├── JwtService.java
│   │   │   │   ├── SessionService.java
│   │   │   │   ├── TwoFactorService.java
│   │   │   │   ├── OAuth2Service.java
│   │   │   │   ├── EmailService.java
│   │   │   │   └── RateLimiterService.java
│   │   │   ├── security/
│   │   │   │   ├── JwtAuthenticationFilter.java
│   │   │   │   └── OAuth2SuccessHandler.java
│   │   │   ├── model/
│   │   │   │   ├── entity/
│   │   │   │   │   ├── User.java
│   │   │   │   │   ├── Role.java
│   │   │   │   │   ├── UserRole.java
│   │   │   │   │   ├── UserSession.java
│   │   │   │   │   ├── OAuthAccount.java
│   │   │   │   │   ├── EmailVerification.java
│   │   │   │   │   └── ActivityLog.java
│   │   │   │   ├── dto/
│   │   │   │   │   ├── request/
│   │   │   │   │   │   ├── RegisterRequest.java
│   │   │   │   │   │   ├── LoginRequest.java
│   │   │   │   │   │   └── ... (all request DTOs)
│   │   │   │   │   └── response/
│   │   │   │   │       ├── AuthResponse.java
│   │   │   │   │       ├── UserDto.java
│   │   │   │   │       └── ... (all response DTOs)
│   │   │   │   └── enums/
│   │   │   │       ├── RoleCode.java
│   │   │   │       ├── VerificationType.java
│   │   │   │       └── ActivityAction.java
│   │   │   ├── repository/
│   │   │   │   ├── UserRepository.java
│   │   │   │   ├── RoleRepository.java
│   │   │   │   ├── UserRoleRepository.java
│   │   │   │   ├── UserSessionRepository.java
│   │   │   │   ├── OAuthAccountRepository.java
│   │   │   │   ├── EmailVerificationRepository.java
│   │   │   │   └── ActivityLogRepository.java
│   │   │   ├── exception/
│   │   │   │   ├── BusinessException.java
│   │   │   │   ├── InvalidCredentialsException.java
│   │   │   │   ├── EmailNotVerifiedException.java
│   │   │   │   ├── TotpRequiredException.java
│   │   │   │   ├── AccountLockedException.java
│   │   │   │   └── RateLimitExceededException.java
│   │   │   └── handler/
│   │   │       └── GlobalExceptionHandler.java
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-dev.yml
│   │       ├── application-prod.yml
│   │       ├── keys/
│   │       │   ├── private.pem         # NOT committed to git
│   │       │   └── public.pem
│   │       ├── db/migration/
│   │       │   ├── V1__create_users_table.sql
│   │       │   ├── V2__create_roles_table.sql
│   │       │   ├── V3__seed_roles.sql
│   │       │   ├── V4__create_user_roles_table.sql
│   │       │   ├── V5__create_user_sessions_table.sql
│   │       │   ├── V6__create_oauth_accounts_table.sql
│   │       │   ├── V7__create_email_verifications_table.sql
│   │       │   ├── V8__create_activity_logs_table.sql
│   │       │   ├── V9__add_two_factor_columns.sql
│   │       │   ├── V10__add_user_stats_columns.sql
│   │       │   ├── V11__add_user_deleted_columns.sql
│   │       │   └── V12__create_indexes.sql
│   │       └── templates/email/
│   │           ├── verification.html
│   │           └── reset-password.html
│   └── test/
│       └── java/com/metruyenchu/auth/
│           ├── service/
│           │   ├── AuthServiceTest.java
│           │   ├── JwtServiceTest.java
│           │   └── TwoFactorServiceTest.java
│           ├── controller/
│           │   ├── AuthControllerTest.java
│           │   └── UserControllerTest.java
│           └── repository/
│               └── UserRepositoryTest.java
```

---

## 15. Biến Môi Trường

| Variable | Default | Mô tả |
|----------|---------|-------|
| `SERVER_PORT` | 8081 | Cổng service |
| `DB_URL` | jdbc:postgresql://localhost:5432/auth_db | Database URL |
| `DB_USERNAME` | postgres | DB user |
| `DB_PASSWORD` | postgres | DB password |
| `REDIS_HOST` | localhost | Redis host |
| `REDIS_PORT` | 6379 | Redis port |
| `SMTP_HOST` | smtp.gmail.com | SMTP server |
| `SMTP_PORT` | 587 | SMTP port |
| `SMTP_USERNAME` | — | SMTP username |
| `SMTP_PASSWORD` | — | SMTP password |
| `GOOGLE_CLIENT_ID` | — | Google OAuth2 client ID |
| `GOOGLE_CLIENT_SECRET` | — | Google OAuth2 client secret |
| `FACEBOOK_CLIENT_ID` | — | Facebook OAuth2 client ID |
| `FACEBOOK_CLIENT_SECRET` | — | Facebook OAuth2 client secret |
| `ZALO_CLIENT_ID` | — | Zalo OAuth2 client ID |
| `ZALO_CLIENT_SECRET` | — | Zalo OAuth2 client secret |
| `JWT_PRIVATE_KEY` | — | RSA private key (base64) |
| `JWT_PUBLIC_KEY` | — | RSA public key (base64) |
| `FRONTEND_URL` | http://localhost:3000 | Frontend URL |
| `RATE_LIMIT_ENABLED` | true | Bật/tắt rate limiting |

---

## End of Auth Service Spec
