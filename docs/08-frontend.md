# Frontend — MeTruyenChu

> **File:** 08-frontend.md
> **Part of:** metruyenchu rebuild spec series
> **Dev server:** `npm run dev` → http://localhost:3000

---

## 1. Tổng quan

### 1.1 Công nghệ

| Thành phần | Công nghệ | Ghi chú |
|-----------|-----------|---------|
| Framework | Next.js 14 (App Router) | TypeScript strict mode |
| UI | TailwindCSS 3.4 | Utility-first, custom design tokens |
| Server State | TanStack Query v5 | Caching, stale-while-revalidate, optimistic updates |
| Client State | Zustand 4 | Stores nhỏ gọn, persist middleware |
| Audio Player | howler.js (core) + wavesurfer.js (read-along) | Lazy load |
| PWA | @ducanh2912/next-pwa | Workbox-based SW, Webpack plugin |
| Icons | Lucide React | Icon set nhẹ, tree-shakeable |
| HTTP Client | ky | Fetch-based, nhỏ gọn, retry native |
| Forms | React Hook Form + Zod | Validation type-safe |
| Charts | Recharts | Nhẹ, React-native, SVG-based |
| i18n | next-intl | File-based translations, lazy load |
| Linting | ESLint + Prettier + Husky | pre-commit hooks |

### 1.2 Rendering Strategy

| Trang | Strategy | Lý do |
|------|----------|-------|
| Homepage | ISR (revalidate: 300s) | SEO + dữ liệu thay đổi chậm |
| Browse | ISR (revalidate: 60s) | Cập nhật truyện mới nhanh |
| Story Detail | SSR | SEO + dynamic head tags |
| Chapter Reader | SSR with streaming | Content lớn, cần hiển thị nhanh phần đầu |
| Search | CSR (client fetch) | User-dependent, real-time |
| Rankings | SSR | SEO + static data |
| User Profile | SSR | SEO (public) + CSR sections |
| Admin | CSR | Internal, auth-required, interactive |
| Auth (login/register) | CSR | Post-redirect pattern |

---

## 2. Route Map

```
/ (Homepage)
├── dang-nhap (Login)
├── dang-ky (Register)
├── quen-mat-khau (Forgot Password)
├── dat-lai-mat-khau (Reset Password)
├── xac-thuc-email (Verify Email)
│
├── truyen (Browse all stories)
│   ├── danh-sach (Story list with filters)
│   ├── {slug} (Story Detail)
│   │   ├── chuong-{chapterNumber} (Chapter Reader)
│   │   │   └── binh-luan (Chapter Comments)
│   │   └── audio (Story Audio page)
│   └── {slug}/binh-luan (Story comments page)
│
├── tim-kiem (Search)
│   └── ?q={query}&genre={genre}&status={status}&sort={sort}
│
├── bang-xep-hang (Rankings)
│   ├── ?period=daily
│   ├── ?period=weekly
│   └── ?period=monthly
│
├── tac-gia (Author profiles)
│   └── {slug} (Author detail + stories list)
│
├── nguoi-dung (User)
│   ├── {id}/trang-ca-nhan (Public Profile)
│   ├── {id}/tu-sach (Booklists)
│   ├── {id}/danh-gia (Ratings/Reviews)
│   ├── {id}/theo-doi (Following)
│   └── {id}/thanh-tuu (Badges/Achievements)
│
├── tai-khoan (Account settings — private)
│   ├── thong-tin (Profile info)
│   ├── mat-khau (Change password)
│   ├── thong-bao (Notification prefs)
│   ├── ca-nhan-hoa (Reading preferences: theme, font)
│   └── bao-mat (Security: 2FA, sessions)
│
├── theo-doi (My follows)
├── lich-su (Reading history)
├── tu-sach-cua-toi (My booklists)
├── thong-bao (Notifications)
│
├── admin (Admin dashboard)
│   ├── truyen (Story management)
│   │   ├── danh-sach (All stories)
│   │   ├── cho-duyet (Pending approval)
│   │   ├── them-moi (Create story)
│   │   └── {id}/sua (Edit story)
│   ├── chuong (Chapter management)
│   │   ├── {storyId} (Chapters of story)
│   │   └── {id}/sua (Edit chapter)
│   ├── nguoi-dung (User management)
│   ├── the-loai (Category management)
│   ├── tag (Tag management)
│   ├── danh-gia (Review management)
│   ├── audio (Audio job management)
│   ├── bao-cao (Reported content)
│   ├── thong-ke (Analytics dashboard)
│   └── ab-testing (A/B test management)
│
├── sitemap.xml (Dynamic sitemap)
└── manifest.json (PWA manifest)
```

---

## 3. Component Tree

```
<AppLayout>
├── <Navbar>
│   ├── Logo
│   ├── Navigation links (Truyện, Xếp hạng, Tìm kiếm)
│   ├── SearchInput (desktop)
│   └── UserMenu / AuthButtons
│
├── <AudioGlobalBar> (conditionally rendered)
│   ├── NowPlaying (thumbnail, title, chapter)
│   ├── AudioControls (play/pause, prev, next, progress)
│   ├── SpeedSelector
│   ├── VolumeSlider
│   └── CloseButton
│
├── <main> (page content)
│   ├── HomePage
│   │   ├── <HeroBanner> (auto-rotate carousel)
│   │   ├── <HotStoriesSection> (ranked grid)
│   │   ├── <NewUpdatesSection> (horizontal scroll)
│   │   ├── <CompletedStoriesSection>
│   │   ├── <CategoryCloud> (tag cloud click → browse)
│   │   └── <TopRatedSidebar> (top 10 sidebar)
│   │
│   ├── BrowsePage
│   │   ├── <FilterSidebar> (genre, status, sort, range)
│   │   ├── <StoryGridView> (card grid 4-5 columns)
│   │   └── <Pagination>
│   │
│   ├── StoryDetailPage
│   │   ├── <StoryHeader>
│   │   │   ├── CoverImage (with ImageWithFallback)
│   │   │   ├── StoryInfo (title, author, status, genres)
│   │   │   ├── StoryStats (views, followers, rating)
│   │   │   ├── ActionButtons (follow, bookmark, rating)
│   │   │   └── ShareButtons
│   │   ├── <StoryDescription> (expandable)
│   │   ├── <ChapterList> (searchable, sortable)
│   │   ├── <CommentSection>
│   │   └── <RelatedStories>
│   │
│   ├── ChapterReaderPage
│   │   ├── <ReaderToolbar> (theme, font, line-height, Aa)
│   │   ├── <ChapterContent> (styled prose)
│   │   │   └── <AudioSyncText> (highlighted segment)
│   │   ├── <AudioPlayer> (inline)
│   │   ├── <ChapterNavigation> (prev/next chapter)
│   │   └── <ChapterComments>
│   │
│   ├── SearchResultsPage
│   │   ├── <SearchInput> (large, auto-focus)
│   │   ├── <FilterPanel> (genre, status, sort)
│   │   ├── <AutocompleteSuggestions>
│   │   ├── <ResultsGrid>
│   │   └── <Pagination>
│   │
│   ├── RankingsPage
│   │   ├── <PeriodTabs> (daily, weekly, monthly)
│   │   ├── <MetricTabs> (views, followers, rating, comments)
│   │   └── <RankingTable>
│   │
│   ├── UserProfilePage
│   │   ├── <ProfileHeader> (avatar, bio, stats)
│   │   ├── <TabBar> (booklists, ratings, follows, badges)
│   │   ├── <BooklistGrid>
│   │   ├── <RatingList>
│   │   └── <BadgeList>
│   │
│   ├── AccountSettingsPage
│   │   ├── <SideNav> (profile, password, notifications, prefs, security)
│   │   ├── <ProfileForm>
│   │   ├── <PasswordForm>
│   │   ├── <NotificationPrefs>
│   │   ├── <ReadingPrefs> (theme toggle, font selector)
│   │   └── <SecuritySettings> (2FA, sessions list)
│   │
│   ├── ReadingHistoryPage
│   │   ├── <HistoryList> (grouped by date)
│   │   └── <ClearHistoryButton>
│   │
│   ├── FollowsPage
│   │   ├── <FollowedStories>
│   │   │   ├── <ChapterBadge> (unread count)
│   │   │   └── <ContinueReadingButton>
│   │   └── <FollowedAuthors>
│   │
│   ├── NotificationsPage
│   │   ├── <NotificationList> (grouped: today, this week, earlier)
│   │   └── <MarkAllReadButton>
│   │
│   └── AdminPages
│       ├── Dashboard
│       │   ├── <StatCards> (users, stories, chapters, audio)
│       │   ├── <Charts> (views, growth)
│       │   ├── <PendingStories> (approval queue)
│       │   └── <AudioJobs> (queue status)
│       ├── StoryManagement
│       │   ├── <DataTable> (sortable, filterable)
│       │   ├── <StoryForm> (create/edit)
│       │   │   ├── <CoverUploader>
│       │   │   └── <GenreSelector>
│       │   └── <BulkActions>
│       ├── UserManagement
│       │   ├── <DataTable>
│       │   └── <UserDetailModal>
│       └── CategoryManagement
│           └── <CategoryTree> (drag-and-drop)
│
├── <Footer>
│   ├── Logo + Description
│   ├── Navigation links
│   ├── Social links
│   └── Copyright
│
└── <Toasts>
    └── <ConfirmModal>
```

---

## 4. Shared Components

### 4.1 `AudioPlayer`

```tsx
// Đa năng: dùng cả trong global bar lẫn inline chapter reader
interface AudioPlayerProps {
  src: string;          // URL audio file từ MinIO
  chapterId: number;
  storyId: number;
  title: string;
  onProgress?: (currentTime: number) => void;
  onComplete?: () => void;
  variant: 'global' | 'inline' | 'minimal';
  segments?: AudioSegment[]; // Cho read-along
}

interface AudioSegment {
  start: number;        // seconds
  end: number;
  text: string;
}
```

### 4.2 `StoryCard`

```tsx
interface StoryCardProps {
  story: {
    id: number;
    title: string;
    slug: string;
    coverUrl: string;
    authorName: string;
    genres: { id: number; name: string; slug: string }[];
    status: 'ONGOING' | 'COMPLETED' | 'HIATUS' | 'DROPPED';
    avgRating: number;
    totalViews: number;
    totalFollowers: number;
    lastChapterNumber: number;
    lastChapterUpdate: string; // ISO date
  };
  variant: 'grid' | 'list' | 'compact' | 'featured' | 'horizontal';
}
```

### 4.3 `ChapterTable`

```tsx
interface ChapterTableProps {
  chapters: Chapter[];
  storySlug: string;
  searchQuery?: string;
  sortOrder: 'asc' | 'desc';
  showAudioBadge?: boolean;
  onChapterClick?: (chapterNumber: number) => void;
}
```

### 4.4 `CommentSection`

```tsx
interface CommentSectionProps {
  targetType: 'chapter' | 'story';
  targetId: number;
  storySlug?: string;      // For linking
  chapterNumber?: number;
}
```

Log:
- Gửi comment → POST /api/v1/comments → invalidate query cache
- Real-time replies (polling 30s hoặc WebSocket cho phiên đang active)
- Nested comments tối đa 3 cấp
- Spam filter (từ khoá cấm, rate limit 10 comment/phút)

### 4.5 `Pagination`

```tsx
interface PaginationProps {
  currentPage: number;
  totalPages: number;
  onPageChange: (page: number) => void;
  siblingCount?: number; // Số trang hiển thị bên cạnh current
  variant: 'default' | 'simple' | 'infinite-scroll';
}
```

### 4.6 `StarRating`

```tsx
interface StarRatingProps {
  rating: number;
  maxRating?: 5 | 10;
  size: 'sm' | 'md' | 'lg';
  interactive?: boolean;
  onRate?: (rating: number) => void;
  showValue?: boolean;
  count?: number;          // Số lượt đánh giá
}
```

### 4.7 `ImageWithFallback`

```tsx
// Tự động fallback khi ảnh lỗi
interface ImageWithFallbackProps {
  src: string;
  alt: string;
  fallbackSrc?: string;   // Default: placeholder gradient
  width: number;
  height: number;
  className?: string;
  priority?: boolean;     // next/image priority
  unoptimized?: boolean;  // Cho external images
}
```

### 4.8 `ConfirmModal`

```tsx
interface ConfirmModalProps {
  open: boolean;
  title: string;
  message: string;
  confirmText?: string;
  cancelText?: string;
  variant: 'danger' | 'warning' | 'info';
  onConfirm: () => void;
  onCancel: () => void;
  isLoading?: boolean;
}
```

### 4.9 `Toast`

```tsx
// Global toast system dùng Zustand
interface Toast {
  id: string;
  type: 'success' | 'error' | 'warning' | 'info';
  title: string;
  message?: string;
  duration?: number;       // ms, 0 = persist
  action?: {
    label: string;
    onClick: () => void;
  };
}
```

### 4.10 `BooklistCard`

```tsx
interface BooklistCardProps {
  booklist: {
    id: number;
    name: string;
    description: string;
    storyCount: number;
    followerCount: number;
    coverStories: string[];  // URLs của 4 truyện đầu
    isPublic: boolean;
    owner: { id: number; displayName: string; avatarUrl: string };
  };
}
```

### 4.11 `BadgeList`

```tsx
interface BadgeListProps {
  badges: {
    id: number;
    name: string;
    iconUrl: string;
    description: string;
    earnedAt: string;
    rarity: 'common' | 'rare' | 'epic' | 'legendary';
  }[];
  limit?: number; // Show +N more
}
```

### 4.12 `RankingTable`

```tsx
interface RankingTableProps {
  items: {
    rank: number;
    rankChange: number;    // +/- so với kỳ trước
    story: {
      id: number;
      title: string;
      slug: string;
      coverUrl: string;
      authorName: string;
    };
    metric: number;
    metricLabel: string;
  }[];
  period: 'daily' | 'weekly' | 'monthly';
  metric: 'views' | 'followers' | 'rating' | 'comments';
}
```

---

## 5. Page Descriptions Chi Tiết

### 5.1 Homepage (`/`)

```
Layout: Sidebar trái + Main content + Sidebar phải
Strategy: ISR, revalidate 300s
Cache tags: homepage

Components:
├── HeroBanner (slider 5 stories nổi bật)
│   ├── Background image (cover art, gradient overlay)
│   ├── Story info overlay (title, author, genres, rating, view count)
│   ├── "Đọc ngay" + "Xem chi tiết" CTA buttons
│   ├── Auto-play 5s, dots indicator, prev/next arrows
│   └── Swipe support mobile
│
├── Hot Stories (Danh sách truyện hot)
│   ├── Grid 4 cột desktop, 2 cột tablet, 1 cột mobile
│   ├── Mỗi card: cover, title, author, rating, chapter badge mới nhất
│   └── "Xem thêm" link → /truyen?sort=hot
│
├── New Updates (Truyện vừa cập nhật)
│   ├── Horizontal scroll row (scroll-snap)
│   ├── Card compact: cover nhỏ + title + "Chương 123" + "2 giờ trước"
│   └── Auto-scroll tạm dừng khi hover
│
├── Completed Stories (Truyện đã hoàn thành)
│   ├── Grid 4 cột
│   ├── Badge "Full" overlay trên cover
│   └── Show chapter count
│
├── CategoryCloud
│   ├── Danh sách thể loại dạng tag cloud
│   ├── Kích thước font dựa trên số lượng truyện
│   └── Click → /truyen?genre={slug}
│
└── TopRatedSidebar (sidebar phải)
    ├── Top 10 truyện theo rating
    ├── Ranking number + cover nhỏ + title + rating stars
    └── Hiệu ứng podium cho top 3
```

### 5.2 Browse Page (`/truyen`)

```
Layout: Sidebar trái (filters) + Main (grid)
Strategy: ISR, revalidate 60s

Components:
├── FilterSidebar
│   ├── Genre multi-select checkboxes
│   │   ├── Parent genres (mở rộng → child genres)
│   │   ├── "Tất cả" button để bỏ chọn
│   │   └── URL sync: ?genre=ngon-tinh,kiem-hiep
│   ├── Status radio group
│   │   ├── Tất cả, Đang ra, Hoàn thành, Tạm ngưng, Drop
│   │   └── URL sync: ?status=ONGOING
│   ├── Sort dropdown
│   │   ├── Mới cập nhật, Mới đăng, Xem nhiều, Đánh giá cao, Nhiều theo dõi
│   │   └── URL sync: ?sort=latest_update
│   ├── Chapter count range slider
│   └── "Áp dụng" + "Đặt lại" buttons
│
├── ActiveFilters bar
│   ├── Chip hiển thị filter đang chọn (có thể xoá từng cái)
│   └── Responsive: trên mobile filter là drawer
│
├── StoryGridView
│   ├── Grid 4 cột desktop, 3 cột tablet, 2 cột mobile
│   ├── StoryCard variant="grid"
│   └── Transition animation khi filter thay đổi (layout shift)
│
└── Pagination
    └── variant="default"
```

### 5.3 Story Detail Page (`/truyen/{slug}`)

```
Layout: Header full width + Content 2 columns
Strategy: SSR

Components:
├── StoryHeader
│   ├── CoverImage (300x400, priority)
│   ├── StoryInfo
│   │   ├── Title (h1)
│   │   ├── Author link → /tac-gia/{slug}
│   │   ├── Status badge (color-coded)
│   │   │   ├── Đang ra → xanh lá
│   │   │   ├── Hoàn thành → xanh dương
│   │   │   ├── Tạm ngưng → cam
│   │   │   └── Drop → đỏ
│   │   ├── Genres (clickable chips → browse)
│   │   └── Last update time
│   ├── StoryStats
│   │   ├── Lượt xem (số)
│   │   ├── Theo dõi (số)
│   │   ├── Đánh giá (StarRating)
│   │   └── Số chương
│   ├── ActionButtons
│   │   ├── "Đọc từ đầu" (primary CTA)
│   │   ├── "Đọc tiếp" (nếu có reading history)
│   │   ├── Follow button (toggle)
│   │   │   ├── Chưa follow: "Theo dõi" + icon heart
│   │   │   └── Đã follow: "Đang theo dõi" + bell icon (chọn notify)
│   │   ├── Bookmark button (toggle) + folder selector
│   │   ├── StarRating interactive
│   │   └── Share button (copy link, social share)
│   └── ShareButtons
│       ├── Copy link
│       ├── Facebook share
│       └── Zalo share
│
├── Description
│   ├── Expandable text (> 300字 show "Xem thêm" / "Thu gọn")
│   ├── Hashtags inline
│   └── Tags list
│
├── ChapterList
│   ├── Search input (filter chapters by title/number)
│   ├── Sort toggle (mới nhất / cũ nhất)
│   ├── Virtual scroll nếu > 200 chapters
│   ├── ChapterTable rows
│   │   ├── Chapter number + title
│   │   ├── Audio icon (nếu có audio)
│   │   ├── View count
│   │   ├── Updated date
│   │   └── Link → /truyen/{slug}/chuong-{n}
│   └── Load more / pagination cho chapters
│
├── CommentSection
│   ├── Tab: "Bình luận" + "Đánh giá"
│   ├── Sort: mới nhất, cũ nhất, nhiều like nhất
│   └── targetType="story"
│
└── RelatedStories
    ├── Horizontal scroll row
    ├── Dựa vào cùng genre (trừ truyện hiện tại)
    └── StoryCard variant="horizontal"
```

### 5.4 Chapter Reader Page (`/truyen/{slug}/chuong-{chapterNumber}`)

```
Layout: Full screen reader (ẩn sidebar)
Strategy: SSR with streaming

Components:
├── ReaderToolbar (sticky top, ẩn/hiện khi scroll)
│   ├── Back button → story detail
│   ├── Chapter title + story title
│   ├── Theme selector
│   │   ├── Sáng (white bg, black text)
│   │   ├── Tối (dark bg, light text)
│   │   ├── Sepia (nâu nhạt bg)
│   │   └── Xanh đen (xanh đen bg, xanh nhạt text) — cho đọc đêm
│   ├── Font selector
│   │   ├── Font family dropdown (Noto Sans, Noto Serif, Roboto Mono, ...)
│   │   └── Preview text sample
│   ├── Font size slider (14px → 32px)
│   ├── Line height slider (1.2 → 2.5)
│   ├── Margin width slider (narrow → wide)
│   ├── Audio toggle (mở inline audio player)
│   └── Settings persist to Zustand + localStorage
│
├── ChapterContent
│   ├── Styled prose (themed)
│   ├── Images trong chapter (optional)
│   ├── AudioSyncText segments (nếu audio playing)
│   │   ├── Mỗi segment highlight khi audio đến
│   │   ├── Auto-scroll để segment luôn trong viewport
│   │   └── Click segment → seek audio đến time đó
│   └── Reading progress indicator (scroll %)
│
├── AudioPlayer (inline variant)
│   ├── Waveform visualization (nếu read-along active)
│   │   ├── wavesurfer.js peaks
│   │   ├── Progress cursor
│   │   └── Click waveform → seek
│   ├── Play/Pause, skip ±15s
│   ├── Speed (0.5x, 0.75x, 1x, 1.25x, 1.5x, 2x)
│   ├── Volume
│   ├── Download button (nếu user có quyền)
│   └── Minimize → global bar
│
├── ChapterNavigation
│   ├── Previous chapter (← shortcut)
│   │   ├── Button ở đầu và cuối trang
│   │   └── Prefetch chapter nội dung (hover → preload)
│   ├── Next chapter (→ shortcut)
│   │   ├── Button ở đầu và cuối trang
│   │   └── Auto-scroll to top khi chuyển
│   └── Chapter dropdown (chọn chapter bất kỳ)
│
├── ChapterComments
│   ├── CommentSection targetType="chapter"
│   └── Auto-load khi scroll đến cuối
│
└── Keyboard shortcuts guide (modal, press ?)
    ├── ← : Chapter trước
    ├── → : Chapter sau
    ├── F : Fullscreen
    ├── T : Đổi theme
    ├── M : Toggle audio
    ├── Space: Play/Pause audio
    ├── Esc: Thoát fullscreen / đóng modal
    └── ? : Hiện shortcuts
```

### 5.5 Search Results Page (`/tim-kiem`)

```
Layout: Full width
Strategy: CSR

Components:
├── SearchInput (large, auto-focus when navigated to)
│   ├── Search icon + clear button
│   ├── Debounce 300ms
│   └── URL sync: ?q={query}
│
├── AutocompleteSuggestions (dropdown khi đang gõ)
│   ├── Top 5 truyện match title
│   ├── Top 5 tác giả
│   ├── Top 5 thể loại
│   └── Highlight matched text
│
├── FilterPanel (horizontal, compact)
│   ├── Genre multi-select
│   ├── Status
│   ├── Sort (relevance, views, rating, latest, most follows)
│   └── Active filters count badge
│
├── SearchStats
│   ├── "Kết quả cho '{query}'"
│   ├── Total results count
│   └── Time taken
│
├── ResultsGrid
│   ├── StoryCard variant="list" (cover + text details)
│   ├── Empty state: illustration + "Không tìm thấy kết quả"
│   └── Search suggestions nếu không có kết quả
│
└── Pagination
    └── variant="infinite-scroll" (IntersectionObserver)
```

### 5.6 Rankings Page (`/bang-xep-hang`)

```
Layout: Sidebar (period tabs) + Main (ranking table)
Strategy: SSR

Components:
├── PeriodTabs (sticky)
│   ├── Trong ngày / Trong tuần / Trong tháng
│   ├── URL sync: ?period=daily
│   └── Animated underline indicator
│
├── MetricTabs
│   ├── Xem nhiều / Theo dõi nhiều / Đánh giá cao / Bình luận nhiều
│   ├── URL sync: ?metric=views
│   └── Nếu là rating → show avg rating thay vì count
│
├── RankingTable
│   ├── Rank number (top 3 có podium styling: Vàng/Bạc/Đồng)
│   ├── Rank change indicator (▲ lên, ▼ xuống, ― giữ nguyên)
│   ├── Story info (cover small + title + author)
│   ├── Metric value + label
│   └── Click → story detail
│
└── Previous period comparison
    ├── Small text: "Tuần trước: vị trí thứ X với Y views"
    └── Progress icon nếu đang tăng/giảm
```

### 5.7 Admin Dashboard (`/admin`)

```
Layout: Sidebar nav + Main content
Strategy: CSR

Components:
├── StatCards (grid 4)
│   ├── Total Users (icon people, number, % change)
│   ├── Total Stories (icon book, number, % change)
│   ├── Active Today (icon activity, number)
│   └── Audio Jobs Pending (icon headphones, number)
│
├── Charts section
│   ├── Views over time (Recharts LineChart)
│   │   ├── Granularity toggle: 7D / 30D / 90D
│   │   └── Tooltip interactive
│   ├── User growth (Recharts AreaChart)
│   └── Genre distribution (Recharts PieChart)
│
├── PendingStories
│   ├── DataTable: title, author, submitted date, status
│   ├── Bulk approve / reject
│   └── Click → review modal
│
├── AudioJobs queue
│   ├── DataTable: story, chapter, status, progress %
│   ├── Filter: pending, processing, completed, failed
│   └── Retry failed jobs button
│
└── Recent reports list
    ├── Latest pending reports
    └── Link → /admin/bao-cao
```

### 5.8 Admin Story Management (`/admin/truyen`)

```
Components:
├── DataTable
│   ├── Sortable columns: title, author, status, views, rating, updated
│   ├── Search by title/author
│   ├── Filter by status, genre, date range
│   ├── Bulk actions: publish, unpublish, delete
│   └── Row click → edit page
│
├── StoryForm (create/edit)
│   ├── Title (required, validate unique slug)
│   ├── Alternate titles (optional)
│   ├── Author (autocomplete from existing users)
│   ├── CoverUploader
│   │   ├── Drag-and-drop
│   │   ├── Crop tool (300x400 ratio)
│   │   └── Preview
│   ├── Description (rich text: bold, italic, links)
│   ├── GenreSelector (multi-select, parent-child hierarchy)
│   ├── Tags (input, enter-to-add, autocomplete)
│   ├── Status dropdown
│   ├── SEO section
│   │   ├── Custom slug
│   │   ├── Meta description
│   │   └── OG image
│   ├── Scheduling (publish date picker, optional)
│   └── Save + Publish / Save as Draft
```

### 5.9 Admin User Management (`/admin/nguoi-dung`)

```
Components:
├── DataTable
│   ├── Columns: avatar, username, email, role, status (active/banned), registered date, last login
│   ├── Search by username/email
│   ├── Filter by role, status, date range
│   └── Bulk actions: ban, activate, change role
│
├── UserDetailModal
│   ├── Profile info (editable fields)
│   ├── Role selector
│   ├── Account status toggle (active/banned)
│   ├── Recent activity log
│   ├── Stories list (nếu là author)
│   └── Actions: reset password, delete account
```

### 5.10 Admin Audio Management (`/admin/audio`)

```
Components:
├── Job queue view
│   ├── DataTable: story, chapter, type (TTS), status, progress, created, duration
│   ├── Real-time updates (polling 10s)
│   ├── Filters: status, story, date range
│   └── Actions: retry, cancel, regenerate
│
├── GenerateAudioForm (create job form)
│   ├── ChapterSelector (search/select chapter)
│   ├── VoiceProfileSelector (dropdown)
│   │   ├── GET /api/v1/audio/voice-profiles → list
│   │   ├── Mỗi option: name + gender icon + style tag
│   │   ├── Preview: "Giọng Nam trầm ấm ♂ gentle 1.0x"
│   │   └── Default preselected
│   ├── Submit → POST /api/v1/audio/jobs
│   └── Progress display (segment completion: 5/8 segments done)
│
├── VoiceProfileManager (CRUD)
│   ├── List all profiles (table: name, gender, style, speed, status)
│   ├── CreateProfileForm
│   │   ├── Name, Gender (radio), Style (select)
│   │   ├── Speed slider (0.5x-2.0x)
│   │   ├── Pitch slider (0.5x-2.0x)
│   │   └── Reference Audio upload (3-30s, voice cloning)
│   ├── EditProfileForm (same as create, pre-filled)
│   ├── CloneProfileModal (upload reference audio + transcript)
│   └── SetDefaultButton
│
├── JobDetailModal
│   ├── Parent job status + voice profile used
│   ├── Segment list (table: index, text preview, status, duration, retry button)
│   ├── Progress bar: "Segment 5/8 completed"
│   └── Retry segment (individual)
│
├── Audio stats
│   ├── Total jobs, avg processing time, success rate
│   ├── Chart: jobs over time
│   └── Segment distribution (avg segments per chapter)
```

**VoiceProfileSelector component:**
```tsx
interface VoiceProfile {
  id: string;
  name: string;
  gender: 'MALE' | 'FEMALE';
  style: string;
  speed: number;
  isDefault: boolean;
  isActive: boolean;
}

interface VoiceProfileSelectorProps {
  value: string;     // profile ID
  onChange: (id: string) => void;
  disabled?: boolean;
}
```

**GenerateAudioForm:**
```tsx
interface GenerateAudioFormData {
  chapterId: number;
  voiceProfileId: string;
}

// POST /api/v1/audio/jobs
// Response:
interface CreateJobResponse {
  parentJobId: string;
  totalSegments: number;
  status: 'PROCESSING';
  segments: { index: number; status: string; }[];
}

// Polling: GET /api/v1/audio/jobs/{parentJobId}/progress
// Response:
interface JobProgress {
  parentJobId: string;
  status: string;
  totalSegments: number;
  completedSegments: number;
  progressPercent: number;
  segments: { index: number; status: string; durationMs: number | null; }[];
}
```

---

## 6. AudioPlayer — Chi Tiết

### 6.1 Kiến trúc

```
AudioManager (Zustand store)
├── howlerInstance: Howl | null
├── wavesurferInstance: WaveSurfer | null
├── queue: AudioQueueItem[]
├── currentIndex: number
├── state: 'idle' | 'loading' | 'playing' | 'paused' | 'error'
├── settings: { speed, volume, autoplayNext }
└── currentTime, duration, progress
```

### 6.2 Global Bar vs Inline Player

**Global** (sticky bottom bar, persistent xuyên trang):
- Hiển thị khi có audio đang play hoặc paused
- Controls thu gọn: now playing, play/pause, progress bar, speed, volume, close
- Tiếp tục phát khi user navigate giữa các trang (SPA navigation)

**Inline** (trong ChapterReaderPage):
- Full controls: waveform, play/pause, skip ±15s, speed, volume, download
- Text highlight sync (read-along mode)
- Minimize → global bar

### 6.3 Audio Flow

```
1. User click "Phát audio" trên chapter
2. Fetch audio URL từ API (signed MinIO URL, 1h expiry)
3. Khởi tạo Howl với src = audioUrl
4. Nếu read-along mode → init wavesurfer.js với cùng src
5. Load segments data (timing → text mapping)
6. Play audio:
   - howler.js handles playback (nhẹ, cross-browser tốt)
   - wavesurfer.js handles waveform display (optional, lazy)
   - setInterval 100ms update progress → store
7. Media Session API:
   - navigator.mediaSession.metadata = { title, artist, artwork }
   - navigator.mediaSession.setActionHandler('play', ...)
   - navigator.mediaSession.setActionHandler('pause', ...)
   - navigator.mediaSession.setActionHandler('nexttrack', ...)
   - navigator.mediaSession.setActionHandler('previoustrack', ...)
8. Auto-next: khi audio kết thúc → tự động chuyển chapter tiếp theo
9. Track listen progress → PUT /api/v1/reading-history/audio
```

### 6.4 Read-Along Sync

```
1. AudioSegment[] từ API: [{ start: 0, end: 5.2, text: "..." }, ...]
2. wavesurfer.js hiển thị waveform + regions cho mỗi segment
3. Text content highlight: segment hiện tại có class "audio-active"
4. Auto-scroll: text container scroll đến segment hiện tại (smooth)
5. Click vào text → howler.seek(segment.start) → wavesurfer seek
6. Kết thúc segment → highlight segment tiếp theo
```

### 6.5 Error Handling

| Error | Xử lý |
|-------|-------|
| Audio URL expired | Refresh signed URL → retry |
| Network error during play | Retry 3 lần, exponential backoff |
| Format not supported | Fallback message + text reading |
| WaveSurfer init fail | Fallback to howler-only (no waveform) |
| Media Session not supported | Silent ignore (non-critical) |

---

## 7. PWA

### 7.1 Cấu hình next-pwa

```js
// next.config.js
const withPWA = require('@ducanh2912/next-pwa').default({
  dest: 'public',
  register: true,
  skipWaiting: true,
  disable: process.env.NODE_ENV === 'development',
  runtimeCaching: [
    // CacheFirst cho audio files
    {
      urlPattern: /\/api\/v1\/audio\/stream\/.*/,
      handler: 'CacheFirst',
      options: {
        cacheName: 'audio-cache',
        expiration: { maxEntries: 50, maxAgeSeconds: 30 * 24 * 60 * 60 }, // 30 days
        rangeRequests: true,
      },
    },
    // StaleWhileRevalidate cho chapters
    {
      urlPattern: /\/truyen\/.*\/chuong-\d+/,
      handler: 'StaleWhileRevalidate',
      options: {
        cacheName: 'chapter-cache',
        expiration: { maxEntries: 100, maxAgeSeconds: 7 * 24 * 60 * 60 },
      },
    },
    // StaleWhileRevalidate cho static chapter content API
    {
      urlPattern: /\/api\/v1\/stories\/.*\/chapters\/\d+/,
      handler: 'StaleWhileRevalidate',
      options: {
        cacheName: 'chapter-api-cache',
        expiration: { maxEntries: 200, maxAgeSeconds: 24 * 60 * 60 },
      },
    },
    // NetworkFirst cho API calls
    {
      urlPattern: /\/api\/v1\/.*/,
      handler: 'NetworkFirst',
      options: {
        cacheName: 'api-cache',
        expiration: { maxEntries: 100, maxAgeSeconds: 5 * 60 },
        networkTimeoutSeconds: 10,
      },
    },
    // CacheFirst cho fonts và images
    {
      urlPattern: /\.(?:woff2?|eot|ttf|otf)$/,
      handler: 'CacheFirst',
      options: {
        cacheName: 'font-cache',
        expiration: { maxEntries: 30, maxAgeSeconds: 365 * 24 * 60 * 60 },
      },
    },
    {
      urlPattern: /\.(?:png|jpg|jpeg|webp|avif|svg)$/,
      handler: 'CacheFirst',
      options: {
        cacheName: 'image-cache',
        expiration: { maxEntries: 200, maxAgeSeconds: 30 * 24 * 60 * 60 },
      },
    },
  ],
});
```

### 7.2 Service Worker Events

```typescript
// install → pre-cache static assets
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open('static-v1').then((cache) => cache.addAll([
      '/', '/offline', '/manifest.json',
      '/icons/icon-192x192.png', '/icons/icon-512x512.png',
    ]))
  );
});

// activate → cleanup old caches
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => !k.startsWith('static-v')).map((k) => caches.delete(k)))
    )
  );
});

// fetch → runtime caching (handled by workbox)
// message → skip waiting
self.addEventListener('message', (event) => {
  if (event.data === 'SKIP_WAITING') self.skipWaiting();
});

// sync → background sync cho offline actions
self.addEventListener('sync', (event) => {
  if (event.tag === 'sync-reading-history') {
    event.waitUntil(syncReadingHistory());
  }
  if (event.tag === 'sync-bookmarks') {
    event.waitUntil(syncBookmarks());
  }
  if (event.tag === 'sync-comments') {
    event.waitUntil(syncComments());
  }
});

// push → notification khi SW nhận push event
self.addEventListener('push', (event) => {
  const data = event.data?.json() ?? {};
  event.waitUntil(
    self.registration.showNotification(data.title, {
      body: data.body,
      icon: '/icons/icon-192x192.png',
      badge: '/icons/badge-72.png',
      data: { url: data.url },
      actions: data.actions,
    })
  );
});

// notificationclick → navigate
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(
    clients.openWindow(event.notification.data?.url ?? '/')
  );
});
```

### 7.3 IndexedDB Offline Storage

Dùng idb wrapper (small promise-based IndexedDB wrapper):

```typescript
// db.ts
import { openDB } from 'idb';

const db = await openDB('metruyenchu-offline', 1, {
  upgrade(db) {
    // Chapters đã cache offline
    db.createObjectStore('chapters', { keyPath: 'chapterId' });
    // Reading progress pending sync
    db.createObjectStore('pending-reading', { keyPath: 'id', autoIncrement: true });
    // Bookmarks pending sync
    db.createObjectStore('pending-bookmarks', { keyPath: 'id', autoIncrement: true });
    // Offline comments queue
    db.createObjectStore('pending-comments', { keyPath: 'id', autoIncrement: true });
    // Downloaded audio files (blob)
    db.createObjectStore('audio-files', { keyPath: 'chapterId' });
  },
});

// Save chapter for offline reading
async function saveChapterOffline(chapterId: number, content: string) {
  await db.put('chapters', { chapterId, content, savedAt: new Date().toISOString() });
}

// Queue comment for sync
async function queueComment(chapterId: number, text: string) {
  await db.add('pending-comments', { chapterId, text, createdAt: new Date().toISOString() });
}
```

### 7.4 Install Prompt

```typescript
// hooks/useInstallPrompt.ts
export function useInstallPrompt() {
  const [deferredPrompt, setDeferredPrompt] = useState<BeforeInstallPromptEvent | null>(null);
  const [isInstalled, setIsInstalled] = useState(false);

  useEffect(() => {
    const handler = (e: Event) => {
      e.preventDefault();
      setDeferredPrompt(e as BeforeInstallPromptEvent);
    };
    window.addEventListener('beforeinstallprompt', handler);
    window.addEventListener('appinstalled', () => setIsInstalled(true));
    return () => window.removeEventListener('beforeinstallprompt', handler);
  }, []);

  const promptInstall = async () => {
    if (!deferredPrompt) return;
    deferredPrompt.prompt();
    const result = await deferredPrompt.userChoice;
    setDeferredPrompt(null);
    return result.outcome; // 'accepted' | 'dismissed'
  };

  return { isInstallable: !!deferredPrompt && !isInstalled, promptInstall };
}
```

Hiển thị banner "Cài đặt ứng dụng" sau 2 lần visit hoặc 30s tương tác.

### 7.5 Offline Page

```tsx
// app/offline/page.tsx
export default function OfflinePage() {
  return (
    <div className="flex flex-col items-center justify-center min-h-screen p-8 text-center">
      <WifiOff className="w-16 h-16 text-gray-400 mb-4" />
      <h1 className="text-2xl font-bold mb-2">Mất kết nối mạng</h1>
      <p className="text-gray-500 mb-6">
        Bạn đang offline. Các chương đã tải vẫn có thể đọc được.
      </p>
      <Link href="/" className="text-primary underline">Về trang chủ</Link>
    </div>
  );
}
```

---

## 8. State Management

### 8.1 Zustand Stores

```typescript
// store/auth-store.ts — Authentication state
interface AuthState {
  user: User | null;
  accessToken: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (credentials: LoginRequest) => Promise<void>;
  logout: () => void;
  refreshToken: () => Promise<void>;
  updateProfile: (data: Partial<User>) => void;
}

// store/ui-store.ts — UI preferences
interface UIState {
  theme: 'light' | 'dark' | 'sepia' | 'dark-green';
  sidebarOpen: boolean;
  mobileMenuOpen: boolean;
  audioGlobalBarOpen: boolean;
  toasts: Toast[];
  addToast: (toast: Omit<Toast, 'id'>) => void;
  removeToast: (id: string) => void;
  setTheme: (theme: UIState['theme']) => void;
  toggleSidebar: () => void;
}

// store/reading-settings-store.ts — Reader preferences
interface ReadingSettingsState {
  fontSize: number;           // 14-32px
  fontFamily: string;         // 'noto-sans' | 'noto-serif' | ...
  lineHeight: number;         // 1.2-2.5
  marginWidth: number;        // 0-100 (px)
  theme: 'light' | 'dark' | 'sepia' | 'dark-green';
  setFontSize: (size: number) => void;
  setFontFamily: (font: string) => void;
  setLineHeight: (height: number) => void;
  setMarginWidth: (width: number) => void;
  setTheme: (theme: ReadingSettingsState['theme']) => void;
  reset: () => void;
}

// store/audio-store.ts — Audio player state
interface AudioState {
  currentTrack: AudioTrack | null;
  queue: AudioTrack[];
  state: 'idle' | 'loading' | 'playing' | 'paused' | 'error';
  currentTime: number;
  duration: number;
  volume: number;
  speed: number;
  isMinimized: boolean;
  play: (track: AudioTrack) => void;
  pause: () => void;
  resume: () => void;
  stop: () => void;
  seek: (time: number) => void;
  setSpeed: (speed: number) => void;
  setVolume: (volume: number) => void;
  nextTrack: () => void;
  prevTrack: () => void;
  minimize: () => void;
  expand: () => void;
}

// store/reading-progress-store.ts — Reading progress
interface ReadingProgressState {
  progress: Record<string, {     // key: `${storyId}-${chapterNumber}`
    chapterId: number;
    storyId: number;
    chapterNumber: number;
    scrollPosition: number;       // %
    readAt: string;               // ISO date
  }>;
  saveProgress: (storyId: number, chapterNumber: number, scrollPosition: number) => void;
  getProgress: (storyId: number, chapterNumber: number) => number | null;
  clearProgress: (storyId: number) => void;
}
```

### 8.2 TanStack Query Setup

```typescript
// lib/api-client.ts
const apiClient = ky.create({
  prefixUrl: process.env.NEXT_PUBLIC_API_URL,
  hooks: {
    beforeRequest: [
      async (request) => {
        const token = useAuthStore.getState().accessToken;
        if (token) request.headers.set('Authorization', `Bearer ${token}`);
      },
    ],
    afterResponse: [
      async (_request, _options, response) => {
        if (response.status === 401) {
          await useAuthStore.getState().refreshToken();
        }
      },
    ],
  },
});

// hooks/queries/useStories.ts
export function useStories(filters: StoryFilters) {
  return useQuery({
    queryKey: ['stories', filters],
    queryFn: () => apiClient.get('stories', { searchParams: filters }).json<PaginatedResponse<Story>>(),
    staleTime: 60_000,
    placeholderData: keepPreviousData,
  });
}

export function useStoryDetail(slug: string) {
  return useQuery({
    queryKey: ['story', slug],
    queryFn: () => apiClient.get(`stories/${slug}`).json<ApiResponse<StoryDetail>>(),
    staleTime: 30_000,
  });
}

export function useChapterContent(storySlug: string, chapterNumber: number) {
  return useQuery({
    queryKey: ['chapter', storySlug, chapterNumber],
    queryFn: () => apiClient.get(`stories/${storySlug}/chapters/${chapterNumber}`).json<ApiResponse<ChapterContent>>(),
    staleTime: 300_000, // 5 phút vì chapter ít thay đổi
  });
}

// Mutation example
export function useFollowStory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (storyId: number) => apiClient.post(`social/follows/stories/${storyId}`).json(),
    onSuccess: (_, storyId) => {
      queryClient.invalidateQueries({ queryKey: ['story', storyId] });
      queryClient.invalidateQueries({ queryKey: ['follows'] });
    },
  });
}
```

---

## 9. i18n

### 9.1 Cấu trúc file

```
frontend/
└── messages/
    ├── vi.json       # Vietnamese (default locale)
    ├── en.json       # English
    ├── ja.json       # Japanese
    ├── ko.json       # Korean
    └── zh.json       # Chinese (Simplified)
```

### 9.2 next-intl setup

```typescript
// i18n.ts
import { getRequestConfig } from 'next-intl/server';

export default getRequestConfig(async ({ locale }) => ({
  messages: (await import(`./messages/${locale}.json`)).default,
}));

// middleware.ts
import createMiddleware from 'next-intl/middleware';
export default createMiddleware({
  locales: ['vi', 'en', 'ja', 'ko', 'zh'],
  defaultLocale: 'vi',
  localePrefix: 'as-needed',  // /vi/truyen hoặc /truyen (default = vi)
});
```

### 9.3 Sử dụng

```tsx
// Component
import { useTranslations } from 'next-intl';

export default function HeroBanner() {
  const t = useTranslations('home');
  return <h1>{t('hero.title')}</h1>;
}

// Server Component
import { getTranslations } from 'next-intl/server';

export default async function StoryDetailPage() {
  const t = await getTranslations('story');
  return <h2>{t('chapters')}</h2>;
}
```

---

## 10. Font Loading

```tsx
// app/layout.tsx
import { Noto_Sans, Noto_Serif, Noto_Sans_Mono } from 'next/font/google';

const notoSans = Noto_Sans({
  subsets: ['vietnamese', 'latin', 'cyrillic'],
  display: 'swap',
  variable: '--font-noto-sans',
  weight: ['400', '500', '600', '700'],
});

const notoSerif = Noto_Serif({
  subsets: ['vietnamese', 'latin'],
  display: 'swap',
  variable: '--font-noto-serif',
  weight: ['400', '500', '600', '700'],
});

const notoSansMono = Noto_Sans_Mono({
  subsets: ['vietnamese', 'latin'],
  display: 'swap',
  variable: '--font-mono',
  weight: ['400', '500'],
});

export default function RootLayout({ children }) {
  return (
    <html className={`${notoSans.variable} ${notoSerif.variable} ${notoSansMono.variable}`}>
      <body>{children}</body>
    </html>
  );
}
```

Font cho reader:
- **Noto Sans**: UI elements, button, nav
- **Noto Serif**: Chapter content (serif dễ đọc văn bản dài)
- **Noto Sans Mono**: Code blocks trong chapter

---

## 11. SEO

### 11.1 JSON-LD

```tsx
// components/seo/StoryJsonLd.tsx
export function StoryJsonLd({ story }: { story: StoryDetail }) {
  const jsonLd = {
    '@context': 'https://schema.org',
    '@type': 'Book',
    name: story.title,
    alternateName: story.alternateTitles,
    url: `https://metruyenchu.com/truyen/${story.slug}`,
    image: story.coverUrl,
    author: {
      '@type': 'Person',
      name: story.authorName,
      url: `https://metruyenchu.com/tac-gia/${story.authorSlug}`,
    },
    genre: story.genres.map((g) => g.name),
    description: story.description?.substring(0, 500),
    numberOfPages: story.chapterCount,
    datePublished: story.createdAt,
    dateModified: story.updatedAt,
    aggregateRating: story.avgRating ? {
      '@type': 'AggregateRating',
      ratingValue: story.avgRating,
      ratingCount: story.totalRatings,
      bestRating: 5,
    } : undefined,
  };
  return <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }} />;
}

// Chapter JSON-LD
export function ChapterJsonLd({ story, chapter }: { story: StoryDetail; chapter: Chapter }) {
  const jsonLd = {
    '@context': 'https://schema.org',
    '@type': 'Chapter',
    name: `Chương ${chapter.chapterNumber}: ${chapter.title}`,
    url: `https://metruyenchu.com/truyen/${story.slug}/chuong-${chapter.chapterNumber}`,
    isPartOf: {
      '@type': 'Book',
      name: story.title,
      url: `https://metruyenchu.com/truyen/${story.slug}`,
    },
    position: chapter.chapterNumber,
    datePublished: chapter.publishedAt,
  };
  return <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }} />;
}
```

### 11.2 Dynamic Metadata

```tsx
// app/truyen/[slug]/page.tsx
export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const story = await getStory(params.slug);
  return {
    title: `${story.title} | MeTruyenChu`,
    description: story.description?.substring(0, 160),
    openGraph: {
      title: story.title,
      description: story.description?.substring(0, 200),
      type: 'website',
      images: [{ url: story.coverUrl, width: 300, height: 400 }],
    },
    twitter: {
      card: 'summary_large_image',
      title: story.title,
      description: story.description?.substring(0, 200),
      images: [story.coverUrl],
    },
    alternates: {
      canonical: `https://metruyenchu.com/truyen/${story.slug}`,
    },
  };
}
```

### 11.3 Dynamic Sitemap

```typescript
// app/sitemap.ts
import { MetadataRoute } from 'next';

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  const baseUrl = 'https://metruyenchu.com';

  const [stories, categories] = await Promise.all([
    getAllStoriesSlugs(),
    getAllCategories(),
  ]);

  const storyEntries = stories.map((slug) => ({
    url: `${baseUrl}/truyen/${slug}`,
    lastModified: new Date(),
    changeFrequency: 'daily' as const,
    priority: 0.8,
  }));

  const categoryEntries = categories.map((cat) => ({
    url: `${baseUrl}/truyen?genre=${cat.slug}`,
    changeFrequency: 'weekly' as const,
    priority: 0.5,
  }));

  const staticEntries: MetadataRoute.Sitemap = [
    { url: baseUrl, lastModified: new Date(), changeFrequency: 'hourly', priority: 1.0 },
    { url: `${baseUrl}/truyen`, lastModified: new Date(), changeFrequency: 'hourly', priority: 0.9 },
    { url: `${baseUrl}/bang-xep-hang`, lastModified: new Date(), changeFrequency: 'daily', priority: 0.7 },
    { url: `${baseUrl}/tim-kiem`, lastModified: new Date(), changeFrequency: 'weekly', priority: 0.5 },
  ];

  return [...staticEntries, ...storyEntries, ...categoryEntries];
}
```

### 11.4 Breadcrumbs

```tsx
// components/seo/Breadcrumbs.tsx
interface BreadcrumbItem {
  label: string;
  href?: string;
}

export function Breadcrumbs({ items }: { items: BreadcrumbItem[] }) {
  const jsonLd = {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: items.map((item, i) => ({
      '@type': 'ListItem',
      position: i + 1,
      name: item.label,
      item: item.href ? `https://metruyenchu.com${item.href}` : undefined,
    })),
  };

  return (
    <>
      <nav aria-label="Breadcrumb" className="text-sm text-gray-500">
        <ol className="flex items-center gap-2">
          {items.map((item, i) => (
            <li key={i} className="flex items-center gap-2">
              {i > 0 && <ChevronRight className="w-4 h-4" />}
              {item.href ? (
                <Link href={item.href} className="hover:text-primary">{item.label}</Link>
              ) : (
                <span className="text-gray-900 font-medium">{item.label}</span>
              )}
            </li>
          ))}
        </ol>
      </nav>
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }} />
    </>
  );
}
```

---

## 12. Accessibility

### 12.1 Keyboard Shortcuts

```typescript
// hooks/useReaderKeyboard.ts
export function useReaderKeyboard(actions: ReaderKeyboardActions) {
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      // Không trigger khi đang focus input/textarea
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;

      switch (e.key) {
        case 'ArrowLeft':
          e.preventDefault();
          actions.prevChapter?.();
          break;
        case 'ArrowRight':
          e.preventDefault();
          actions.nextChapter?.();
          break;
        case 'f':
        case 'F':
          e.preventDefault();
          actions.toggleFullscreen?.();
          break;
        case 't':
        case 'T':
          e.preventDefault();
          actions.cycleTheme?.();
          break;
        case 'm':
        case 'M':
          e.preventDefault();
          actions.toggleAudio?.();
          break;
        case ' ':
          e.preventDefault();
          actions.togglePlay?.();
          break;
        case '?':
          e.preventDefault();
          actions.showShortcuts?.();
          break;
      }
    }
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [actions]);
}
```

### 12.2 ARIA & Semantic HTML

```tsx
// Navbar
<nav aria-label="Điều hướng chính" role="navigation">
  <button aria-label="Mở menu" aria-expanded={isOpen}>
    <MenuIcon aria-hidden="true" />
  </button>
</nav>

// Hero Banner
<section aria-label="Truyện nổi bật" role="region">
  <div role="tablist" aria-label="Slide indicators">
    {slides.map((_, i) => (
      <button key={i} role="tab" aria-selected={i === current} aria-label={`Slide ${i + 1}`} />
    ))}
  </div>
</section>

// Story Card
<article aria-label={story.title}>
  <Link href={`/truyen/${story.slug}`} aria-labelledby={`story-title-${story.id}`}>
    <Image alt={`Bìa truyện ${story.title}`} src={story.coverUrl} />
    <h2 id={`story-title-${story.id}`}>{story.title}</h2>
  </Link>
</article>

// Reader
<main role="main" aria-label="Nội dung chương">
  <article aria-labelledby="chapter-title">
    <h1 id="chapter-title" className="sr-only">
      Chương {chapterNumber}: {title}
    </h1>
    <div role="document" aria-label="Nội dung chương">
      {content}
    </div>
  </article>
</main>

// Audio Player
<div role="application" aria-label="Trình phát audio" aria-busy={isLoading}>
  <button aria-label={isPlaying ? 'Tạm dừng' : 'Phát'} />
  <input type="range" aria-label="Tiến trình" aria-valuenow={currentTime} aria-valuemax={duration} />
</div>

// Pagination
<nav aria-label="Phân trang">
  <button aria-label="Trang trước" disabled={page === 1} />
  <button aria-label={`Trang ${page}`} aria-current="page">{page}</button>
  <button aria-label="Trang sau" disabled={page === totalPages} />
</nav>
```

### 12.3 Focus Management

```typescript
// Chapter navigation: focus management khi chuyển chapter
function ChapterNavigation({ onPrev, onNext }: Props) {
  const prevRef = useRef<HTMLButtonElement>(null);
  const nextRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    // Sau khi chapter load, focus vào content area
    document.getElementById('chapter-content')?.focus();
  }, []);

  return (
    <div className="flex justify-between">
      <button ref={prevRef} onClick={onPrev} aria-label="Chương trước">
        ← Chương trước
      </button>
      <button ref={nextRef} onClick={onNext} aria-label="Chương tiếp theo">
        Chương tiếp theo →
      </button>
    </div>
  );
}

// Modal focus trap
function ConfirmModal({ open, onConfirm, onCancel }: Props) {
  const modalRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (open) {
      modalRef.current?.focus();
      // Trap focus inside modal
      const handleTab = (e: KeyboardEvent) => {
        if (e.key === 'Tab') {
          const elements = modalRef.current?.querySelectorAll('button, [href], input, select, textarea');
          if (!elements?.length) return;
          const first = elements[0] as HTMLElement;
          const last = elements[elements.length - 1] as HTMLElement;
          if (e.shiftKey && document.activeElement === first) {
            e.preventDefault();
            last.focus();
          } else if (!e.shiftKey && document.activeElement === last) {
            e.preventDefault();
            first.focus();
          }
        }
      };
      window.addEventListener('keydown', handleTab);
      return () => window.removeEventListener('keydown', handleTab);
    }
  }, [open]);

  return (
    <div role="dialog" aria-modal="true" aria-labelledby="modal-title" ref={modalRef} tabIndex={-1}>
      <h2 id="modal-title">{title}</h2>
    </div>
  );
}
```

---

## 13. Performance Budget

| Metric | Target | Tool |
|--------|--------|------|
| LCP | < 2.5s | Lighthouse |
| FID | < 100ms | Lighthouse |
| CLS | < 0.1 | Lighthouse |
| First Load JS | < 200KB | next/bundle-analyzer |
| Time to Interactive | < 3.5s | Lighthouse |
| Lighthouse Performance | > 90 | CI check |
| PWA Score | > 90 | Lighthouse |
| Accessibility Score | > 95 | Lighthouse |
| SEO Score | 100 | Lighthouse |

### 13.1 Code Splitting

```typescript
// Dynamic imports cho component nặng
const AudioPlayer = dynamic(() => import('@/components/AudioPlayer'), {
  loading: () => <AudioPlayerSkeleton />,
  ssr: false, // Audio player only client-side
});

const Waveform = dynamic(() => import('@/components/Waveform'), {
  ssr: false,
});

const CommentSection = dynamic(() => import('@/components/CommentSection'), {
  loading: () => <CommentSectionSkeleton />,
});
```

---

## 14. Error Boundaries

```tsx
// components/ErrorBoundary.tsx
'use client';

interface Props {
  fallback?: ReactNode;
  children: ReactNode;
}

export class ErrorBoundary extends React.Component<Props, { hasError: boolean; error: Error | null }> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error) {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
    console.error('ErrorBoundary caught:', error, errorInfo);
    // TODO: send to error tracking service
  }

  render() {
    if (this.state.hasError) {
      return this.props.fallback || (
        <div className="flex flex-col items-center justify-center p-8">
          <AlertTriangle className="w-12 h-12 text-red-500 mb-4" />
          <h2 className="text-xl font-bold mb-2">Đã xảy ra lỗi</h2>
          <p className="text-gray-500 mb-4">Vui lòng thử lại sau</p>
          <button
            onClick={() => this.setState({ hasError: false, error: null })}
            className="px-4 py-2 bg-primary text-white rounded-lg"
          >
            Thử lại
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}
```

---

## 15. Security Headers

```typescript
// next.config.js
const securityHeaders = [
  { key: 'X-DNS-Prefetch-Control', value: 'on' },
  { key: 'Strict-Transport-Security', value: 'max-age=63072000; includeSubDomains; preload' },
  { key: 'X-Content-Type-Options', value: 'nosniff' },
  { key: 'X-Frame-Options', value: 'DENY' },
  { key: 'X-XSS-Protection', value: '1; mode=block' },
  { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
  { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=()' },
  {
    key: 'Content-Security-Policy',
    value: [
      "default-src 'self'",
      "script-src 'self' 'unsafe-eval' 'unsafe-inline'",
      "style-src 'self' 'unsafe-inline'",
      "img-src 'self' data: blob: https://*.metruyenchu.com https://*.minio.io",
      "font-src 'self' data:",
      "connect-src 'self' https://*.metruyenchu.com https://*.minio.io wss://*.metruyenchu.com",
      "media-src 'self' https://*.metruyenchu.com https://*.minio.io blob:",
      "frame-src 'none'",
      "object-src 'none'",
      "base-uri 'self'",
      "form-action 'self'",
    ].join('; '),
  },
];

module.exports = withPWA({
  async headers() {
    return [{ source: '/(.*)', headers: securityHeaders }];
  },
});
```

---

## End of Frontend Spec
