# Audio Service — Dịch vụ TTS & Audio

> **File:** 05-audio-service.md
> **Phần của:** metruyenchu rebuild spec series
> **Service:** `audio-service` | Port: `8084` | DB: `audio_db`

---

## 1. Tổng quan

Audio Service chịu trách nhiệm chuyển đổi nội dung chương truyện thành giọng nói (Text-to-Speech), lưu trữ file audio, phát trực tuyến (streaming), và tạo podcast RSS. Service giao tiếp với Story Service qua OpenFeign để lấy nội dung chapter, với LocalAI làm TTS engine, với MinIO làm storage, và publish event qua RabbitMQ.

**Key design decisions:**
- **Split-Merge:** Mỗi chapter được chia thành N segment jobs (500-2000 ký tự/segment). Các segment chạy song song trên nhiều worker/servers → merge thành file hoàn chỉnh.
- **Voice Profiles:** Admin định nghĩa giọng đọc (name, gender, style, speed, pitch, reference audio). Mỗi job chọn 1 voice profile từ dropdown.
- **Parallel workers:** Worker pool mở rộng ngang (horizontal scaling). Queue đảm bảo mỗi segment được xử lý bởi worker đầu tiên rảnh.

---

## 2. Kiến trúc

```
  ┌──────────────────────────────────────────────────────────────────┐
  │                      Audio Service                               │
  │  ┌──────────────┐  ┌───────────────────┐  ┌───────────────────┐ │
  │  │ REST Con-    │  │ VoiceProfile      │  │ Podcast RSS       │ │
  │  │ troller      │  │ Controller        │  │ Generator         │ │
  │  └──────┬───────┘  └────────┬──────────┘  └───────────────────┘ │
  │         │                   │                                    │
  │  ┌──────▼───────────────────▼────────────────────────────────┐   │
  │  │                    AudioJobService                         │   │
  │  │  • createParentJob(chapterId, voiceProfileId)             │   │
  │  │  • splitContent(content) → segments[]                    │   │
  │  │  • createSegmentJobs(segments, parentJobId)              │   │
  │  │  • publishSegments(segmentIds)                           │   │
  │  │  • checkAllSegmentsDone(parentJobId)                     │   │
  │  │  • triggerMerge(parentJobId)                             │   │
  │  └──────────────────────┬────────────────────────────────────┘   │
  │         │               │                │                       │
  │  ┌──────▼────┐  ┌──────▼───────┐  ┌──────▼───────┐             │
  │  │TtsSegments│  │ MergeWorker  │  │ MinIO        │             │
  │  │ Workers   │  │ (FFmpeg      │  │ Storage      │             │
  │  │ (N cons.) │  │  concat)     │  │ Service      │             │
  │  └──────┬────┘  └──────┬───────┘  └──────┬───────┘             │
  │         │              │                 │                       │
  │  ┌──────▼──────────────▼─────────────────▼──────────────────┐  │
  │  │  Feign Clients: StoryService | LocalAI | SocialService   │  │
  │  └──────────────────────────────────────────────────────────┘  │
  └──────────────────────┬────────────────────────────────┬─────────┘
                         │                                │
               ┌─────────▼──────────┐          ┌─────────▼────────┐
               │  RabbitMQ          │          │  MinIO Bucket    │
               │  audio.segments    │          │  audio/stories/  │
               │  audio.merge       │          │  segments/       │
               └────────────────────┘          └──────────────────┘
```

---

## 3. Luồng xử lý TTS Job (Split-Merge)

### 3.1 Tổng quan Split-Merge

Mỗi chapter được **chia nhỏ thành N segment jobs** (500-2000 ký tự/segment). 
Các segment chạy **song song** trên nhiều worker (horizontal scaling). 
Khi tất cả segment hoàn thành → **Merge Worker** ghép các file segment thành 1 file hoàn chỉnh.

```
Chapter content (5000 từ) ──→ Splitter ──→ Segments (mỗi segment ~500-2000 ký tự)
                                                   │
                     ┌────────────────┬────────────┼────────────┬────────────────┐
                     ▼                ▼            ▼            ▼                ▼
               Seg-1(800)       Seg-2(1200)   Seg-3(950)   Seg-4(1100)    Seg-5(950)
                     │                │            │            │                │
               ┌─────┴─────┐   ┌─────┴─────┐ ┌────┴────┐ ┌────┴──────┐  ┌────┴──────┐
               │ Worker A  │   │ Worker B  │ │ Worker A│ │ Worker C  │  │ Worker B  │
               └─────┬─────┘   └─────┬─────┘ └────┬────┘ └────┬──────┘  └────┬──────┘
                     ▼                ▼            ▼            ▼              ▼
                MinIO seg 1     MinIO seg 2   MinIO seg 3  MinIO seg 4    MinIO seg 5
                     │                │            │            │              │
                     └────────────────┴────────────┴────────────┴──────────────┘
                                                   │
                                              Merge Worker (FFmpeg concat)
                                                   │
                                              MinIO merged file
                                              stories/{id}/chapters/{id}/v{version}.mp3
```

### 3.2 Chi tiết các bước

```
Admin chọn chapter + voice profile
              │
              ▼
      ┌──────────────────────┐
      │ POST /audio/jobs     │
      │ {chapterId,          │
      │  voiceProfileId}     │
      └──────────┬───────────┘
                 │
                 ▼
      ┌──────────────────────┐
      │ 1. Fetch chapter     │
      │    content (Feign)   │
      └──────────┬───────────┘
                 │
                 ▼
      ┌──────────────────────────────────────────┐
      │ 2. Split content → N segments            │
      │    • Theo paragraph (tối ưu cho TTS)     │
      │    • Mỗi segment: 500-2000 ký tự         │
      │    • Gộp paragraph nhỏ để đủ kích thước  │
      └──────────┬───────────────────────────────┘
                 │
                 ▼
      ┌──────────────────────────────────────────┐
      │ 3. CREATE parent_audio_job (status=      │
      │    PROCESSING)                            │
      │ 4. CREATE N segment_audio_job rows       │
      │    (status=PENDING, parent_job_id=...)   │
      └──────────┬───────────────────────────────┘
                 │
                 ▼
      ┌──────────────────────────────────────────┐
      │ 5. Publish N messages to RabbitMQ        │
      │    Queue: audio.segments                 │
      │    Mỗi message: {segmentJobId, chapterId,│
      │     segmentIndex, text, voiceProfileId}  │
      └──────────┬───────────────────────────────┘
                 │
                 ▼
      ┌──────────────────────────────────────────┐
      │ 6. TTS Segment Workers (N consumers)     │
      │    consume song song:                    │
      │    • UPDATE segment job → PROCESSING     │
      │    • Gọi LocalAI TTS với text + voice    │
      │    • Upload MP3 → MinIO segments/        │
      │    • UPDATE segment job → COMPLETED      │
      │    • Publish "segment.completed"         │
      └──────────┬───────────────────────────────┘
                 │
                 ▼
      ┌──────────────────────────────────────────┐
      │ 7. Check: tất cả segment done?           │
      │    AudioJobService lắng nghe             │
      │    "segment.completed" events            │
      │    Khi count(COMPLETED) == total segments│
      │    → trigger merge                       │
      └──────────┬───────────────────────────────┘
                 │
                 ▼
      ┌──────────────────────────────────────────┐
      │ 8. Merge Worker (RabbitMQ consumer):     │
      │    • Queue: audio.merge                  │
      │    • Download all segments từ MinIO      │
      │    • FFmpeg concat segments → merged.mp3 │
      │    • Upload merged → MinIO chapters/     │
      │    • CREATE audio_file record            │
      │    • UPDATE parent_job → COMPLETED       │
      │    • UPDATE chapter.has_audio = true     │
      │    • Publish "audio.job.completed"       │
      └──────────┬───────────────────────────────┘
                 │
                 ▼
      ┌──────────────────────┐
      │ ✅ Audio sẵn sàng!   │
      │ Frontend poll/comet  │
      └──────────────────────┘
```

### 3.3 Content Splitter Strategy

```java
public List<Segment> splitContent(String content) {
    // B1: Tách paragraphs (theo \n\n)
    String[] paragraphs = content.split("\\n\\n+");
    
    List<Segment> segments = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int segIndex = 0;
    int charCount = 0;
    
    for (String para : paragraphs) {
        para = para.trim();
        if (para.isEmpty()) continue;
        
        // Paragraph quá dài → tự split
        if (para.length() > MAX_SEGMENT_SIZE) {
            if (current.length() > 0) {
                segments.add(new Segment(segIndex++, current.toString()));
                current = new StringBuilder();
                charCount = 0;
            }
            // Split paragraph thành sentences
            for (String sentence : splitSentences(para)) {
                if (charCount + sentence.length() > MAX_SEGMENT_SIZE && charCount > MIN_SEGMENT_SIZE) {
                    segments.add(new Segment(segIndex++, current.toString()));
                    current = new StringBuilder();
                    charCount = 0;
                }
                current.append(sentence);
                charCount += sentence.length();
            }
            continue;
        }
        
        // Paragraph bình thường
        if (charCount + para.length() > MAX_SEGMENT_SIZE && charCount >= MIN_SEGMENT_SIZE) {
            segments.add(new Segment(segIndex++, current.toString()));
            current = new StringBuilder();
            charCount = 0;
        }
        current.append(para).append("\n\n");
        charCount += para.length() + 2;
    }
    
    if (current.length() > 0) {
        segments.add(new Segment(segIndex, current.toString()));
    }
    
    return segments;
}

private static final int MIN_SEGMENT_SIZE = 500;
private static final int MAX_SEGMENT_SIZE = 2000;
```

**Segment size rationale:**

| Context | Ký tự/segment | Số segment cho chapter 3000 từ |
|---------|---------------|-------------------------------|
| GPU mạnh (RTX 4090) | 2000-3000 | ~3-4 segments |
| GPU trung bình (RTX 3060) | 1000-2000 | ~5-8 segments |
| GPU thấp (GTX 1650 4GB) | 500-1000 | ~10-15 segments |

> Config qua: `audio.segment.max-chars: 2000` và `audio.segment.min-chars: 500`

### 3.4 Segment Job State Machine

```
              ┌──────────┐
              │ PENDING  │ ────── consume ──────► ┌──────────┐
              └──────────┘                         │PROCESSING│
                    │                              └────┬─────┘
                    │                                   │
                    │                          ┌────────┴────────┐
                    │                          ▼                 ▼
                    │                    ┌──────────┐    ┌──────────┐
                    └──── timeout ──────►│ FAILED   │    │COMPLETED │
                                         └──────────┘    └────┬─────┘
                                            │    ↑            │
                                            │    │            │
                                            ▼    │            ▼
                                     (retry < max)      ┌──────────────┐
                                                         │ All segments │
                                                         │ done?        │
                                                         └──────┬───────┘
                                                            yes │
                                                                ▼
                                                        ┌──────────────┐
                                                        │ Merge Worker │
                                                        │  triggered   │
                                                        └──────────────┘
```

### 3.5 Merge Worker (FFmpeg Concat)

Khi tất cả segment completed → message gửi đến queue `audio.merge`:

```java
@Component
public class MergeWorker {
    
    @RabbitListener(queues = "audio.merge")
    public void handleMerge(MergeEvent event) {
        UUID parentJobId = event.getParentJobId();
        
        // 1. Download all segments từ MinIO
        List<Path> segmentFiles = downloadSegments(event.getStoryId(), event.getChapterId(), event.getSegmentCount());
        
        // 2. Tạo file list cho FFmpeg
        Path fileList = writeFileList(segmentFiles);
        
        // 3. FFmpeg concat
        Path outputPath = Files.createTempFile("merged-", ".mp3");
        ProcessBuilder pb = new ProcessBuilder(
            "ffmpeg", "-f", "concat", "-safe", "0",
            "-i", fileList.toString(),
            "-c", "copy",
            outputPath.toString()
        );
        pb.start().waitFor(5, TimeUnit.MINUTES);
        
        // 4. Upload merged file
        String objectKey = String.format("stories/%d/chapters/%d/v%d.mp3",
            event.getStoryId(), event.getChapterId(), event.getVersion());
        minioService.upload(objectKey, outputPath);
        
        // 5. Cleanup temp files
        for (Path p : segmentFiles) Files.deleteIfExists(p);
        Files.deleteIfExists(fileList);
        Files.deleteIfExists(outputPath);
        
        // 6. Update DB
        audioJobRepository.updateStatus(parentJobId, JobStatus.COMPLETED);
        audioFileRepository.create(event.getStoryId(), event.getChapterId(), objectKey);
        storyServiceClient.updateAudioStatus(event.getStoryId(), event.getChapterId(), true);
        
        // 7. Publish event
        publishJobCompleted(parentJobId);
    }
}
```

**FFmpeg concat protocol:**
```
# file_list.txt
file '/tmp/segments/seg_0.mp3'
file '/tmp/segments/seg_1.mp3'
file '/tmp/segments/seg_2.mp3'
```

### 3.6 Worker Pool & Horizontal Scaling

```java
@Bean("ttsTaskExecutor")
public Executor ttsTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(5);
    executor.setMaxPoolSize(10);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("tts-segment-");
    return executor;
}
```

| Config | Value | Ghi chú |
|--------|-------|---------|
| `spring.rabbitmq.listener.simple.prefetch` | `5` | Mỗi worker nhận tối đa 5 segment cùng lúc |
| `audio.worker.max-concurrent` | `10` | Số segment xử lý đồng thời tối đa |
| `audio.worker.scaling` | `HORIZONTAL` | Thêm instance audio-service để tăng throughput |
| `audio.rabbitmq.queue.segments.durable` | `true` | Segment queue persist, không mất message khi restart |

**Horizontal scaling:** Khi deploy thêm instance audio-service:
- Tất cả instance consume từ cùng queue `audio.segments`
- RabbitMQ tự động phân phối segment message round-robin
- Không cần leader election hay coordination
- Merge worker chỉ chạy trên 1 instance (hoặc dùng lock Redis)

### 3.7 Re-generate Audio

- **Re-generate:** Admin gọi `POST /api/v1/audio/jobs/re-generate/{chapterId}?voiceProfileId=...`
- Tạo parent job mới, split content lại từ đầu
- Worker tạo version mới: đọc `currentVersion` từ AudioFile cũ, increment lên 1
- File mới lưu tại: `stories/{storyId}/chapters/{chapterId}/v{version}.mp3`
- AudioFile cũ chuyển status = `DEPRECATED` (không xóa file)
- Segment files cũ tự động cleanup sau 7 ngày

### 3.8 Retry & Timeout Config

```yaml
audio:
  job:
    max-retries: 3
    retry-delays:
      - 30000    # Lần 1: 30 giây
      - 120000   # Lần 2: 2 phút
      - 300000   # Lần 3: 5 phút
    timeout: 1800000     # 30 phút (parent job)
  segment:
    max-retries: 2
    retry-delay: 60000  # 1 phút
    timeout: 600000     # 10 phút (mỗi segment)
    min-chars: 500
    max-chars: 2000
  batch:
    max-chapters: 20    # Số chapter tối đa mỗi batch
```

**Job timeout validation:**
- Segment job timeout: 10 phút (mỗi segment ~60s TTS + overhead)
- Parent job timeout: 30 phút (cho phép tất cả segment + merge)
- Nếu 1 segment timeout → retry tối đa 2 lần
- Hết retry → parent job FAILED (các segment khác vẫn chạy xong, nhưng merge không được trigger)

### 3.9 Batch Generation (Entire Story)

- **Single chapter:** Admin chọn chapter + voice profile → 1 parent job → N segment jobs
- **Entire story:** Admin chọn story → service tạo M parent jobs (1 per chapter), mỗi parent có N segment jobs
- **Parallel processing:** Các segment từ các chapter khác nhau cũng chạy song song

```json
// POST /api/v1/audio/jobs/batch
{
  "storyId": 42,
  "type": "ALL_CHAPTERS",
  "voiceProfileId": "uuid-voi-1"
}
// Response:
{
  "parentJobs": [
    { "chapterId": 128, "totalSegments": 5, "status": "PROCESSING" },
    { "chapterId": 129, "totalSegments": 7, "status": "PENDING" },
    ...
  ],
  "totalChapters": 20,
  "totalSegments": 145
}
```

---

## 4. LocalAI & OmniVoice

### 4.1 Docker Compose

```yaml
# docker-compose/localai.yml
services:
  localai:
    image: localai/localai:latest-gpu-nvidia-cuda-12
    container_name: metruyenchu-localai
    ports:
      - "9090:8080"
    environment:
      - DEBUG=true
      - MODELS_PATH=/models
      - THREADS=8
      - CONTEXT_SIZE=2048
    volumes:
      - ./models:/models
      - ./localai/voice.yaml:/models/voice.yaml
      - ./images:/tmp/generated/images
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: 1
              capabilities: [gpu]
    shm_size: 2gb
    restart: unless-stopped

  # GPU không available → fallback CPU-only
  localai-cpu:
    image: localai/localai:latest-cpu
    profiles: ["cpu-only"]
    container_name: metruyenchu-localai-cpu
    ports:
      - "9091:8080"
    environment:
      - DEBUG=true
      - MODELS_PATH=/models
      - THREADS=8
    volumes:
      - ./models:/models
      - ./localai/voice.yaml:/models/voice.yaml
    restart: unless-stopped
```

### 4.2 Model Config

```yaml
# localai/voice.yaml
name: omnivoice-cpp
backend: transformers
parameters:
  model: omnivoice-Q8_0.gguf
  context_size: 2048
  threads: 8
gpu: true
f16: true
```

**Model info:**
- Model: **OmniVoice Q8_0** (GGUF quantization)
- File size: ~945 MB
- GPU memory: tối thiểu 4 GB VRAM
- Tốc độ: ~20-30 giây cho chapter ~3000 từ (phụ thuộc GPU)
- Download: `localai models install omnivoice-cpp` hoặc tải thủ công

### 4.3 LocalAI REST API

**Request:**
```
POST /v1/audio/speech
Content-Type: application/json

{
  "model": "omnivoice-cpp",
  "input": "Nội dung segment cần chuyển đổi...",
  "voice": "default",
  "response_format": "mp3",
  "speed": 1.0
}
```

**Response:**
```
Content-Type: audio/mpeg
Content-Length: {size_in_bytes}

[MP3 binary data]
```

**Error response:**
```json
{
  "error": {
    "code": 500,
    "message": "TTS generation failed",
    "details": "Model not loaded or OOM"
  }
}
```

---

## 5. Voice Profiles

### 5.1 Khái niệm

Voice Profile là cấu hình giọng đọc cho TTS. Admin tạo các profile với tham số giọng, tốc độ, cao độ, và reference audio cho voice cloning. Khi tạo job TTS, admin chọn 1 voice profile từ dropdown.

### 5.2 List mặc định (seed data)

| ID | Name | Gender | Style | Speed | Ghi chú |
|----|------|--------|-------|-------|---------|
| 1 | Giọng Nam trầm ấm | MALE | gentle | 1.0 | Default, phù hợp tiên hiệp, kiếm hiệp |
| 2 | Giọng Nữ thanh | FEMALE | gentle | 1.0 | Phù hợp ngôn tình, đô thị |
| 3 | Giọng Nam hành động | MALE | excited | 1.1 | Phù hợp hành động, chiến đấu |
| 4 | Giọng Nữ kể chuyện | FEMALE | narrative | 0.95 | Phù hợp mọi thể loại, chậm rãi |
| 5 | Giọng Nam trẻ | MALE | cheerful | 1.05 | Phù hợp main trẻ tuổi, hài hước |

### 5.3 Database Schema

```sql
CREATE TABLE voice_profiles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) NOT NULL,
    description     VARCHAR(500),
    gender          VARCHAR(10) NOT NULL CHECK (gender IN ('MALE','FEMALE')),
    style           VARCHAR(50) NOT NULL DEFAULT 'gentle',
        -- gentle | excited | narrative | cheerful | sad | whisper
    speed           FLOAT NOT NULL DEFAULT 1.0 CHECK (speed >= 0.5 AND speed <= 2.0),
    pitch           FLOAT NOT NULL DEFAULT 1.0 CHECK (pitch >= 0.5 AND pitch <= 2.0),
    reference_audio_url  VARCHAR(500),    -- URL file reference cho voice cloning
    reference_text       TEXT,             -- Transcript của reference audio
    is_default      BOOLEAN NOT NULL DEFAULT FALSE,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_by      VARCHAR(100) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_voice_profiles_default ON voice_profiles(is_default) WHERE is_default = TRUE;
```

### 5.4 API Endpoints

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `GET` | `/api/v1/audio/voice-profiles` | Danh sách voice profiles active | `ADMIN` |
| `GET` | `/api/v1/audio/voice-profiles/{id}` | Chi tiết profile | `ADMIN` |
| `POST` | `/api/v1/audio/voice-profiles` | Tạo profile mới | `ADMIN` |
| `PUT` | `/api/v1/audio/voice-profiles/{id}` | Cập nhật profile | `ADMIN` |
| `DELETE` | `/api/v1/audio/voice-profiles/{id}` | Xóa profile (soft) | `ADMIN` |
| `POST` | `/api/v1/audio/voice-profiles/{id}/clone` | Clone profile từ reference audio | `ADMIN` |

**POST /api/v1/audio/voice-profiles — Request:**
```json
{
  "name": "Giọng Nam trầm ấm",
  "description": "Phù hợp tiên hiệp, kiếm hiệp",
  "gender": "MALE",
  "style": "gentle",
  "speed": 1.0,
  "pitch": 1.0,
  "referenceAudioUrl": null,
  "referenceText": null
}
```

**Response:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": "uuid",
    "name": "Giọng Nam trầm ấm",
    "gender": "MALE",
    "style": "gentle",
    "speed": 1.0,
    "isDefault": false,
    "isActive": true,
    "createdAt": "2026-06-26T10:00:00Z"
  }
}
```

### 5.5 Voice Cloning Flow

```
POST /api/v1/audio/voice-profiles/{id}/clone
Content-Type: multipart/form-data

Fields:
  - audio: reference_audio.mp3 (3-30 giây, WAV/MP3, 16kHz mono)
  - transcript: "Transcript của đoạn audio" (bắt buộc cho voice cloning)
```

**LocalAI voice cloning payload:**
```json
{
  "model": "omnivoice-cpp",
  "input": "Nội dung cần chuyển đổi",
  "voice": {
    "mode": "clone",
    "reference_audio": "base64_encoded_audio",
    "reference_text": "Transcript of reference"
  },
  "response_format": "mp3",
  "speed": 1.0
}
```

### 5.6 TTS Request với Voice Profile

Khi Segment Worker gọi LocalAI, nó map voice profile → TTS parameters:

```java
public TtsRequest buildTtsRequest(String text, VoiceProfile profile, byte[] referenceAudio) {
    TtsRequest request = new TtsRequest();
    request.setModel("omnivoice-cpp");
    request.setInput(text);
    request.setResponseFormat("mp3");
    
    if (profile.getReferenceAudioUrl() != null) {
        // Voice cloning mode
        VoiceConfig voice = new VoiceConfig();
        voice.setMode("clone");
        voice.setReferenceAudio(Base64.getEncoder().encodeToString(referenceAudio));
        voice.setReferenceText(profile.getReferenceText());
        request.setVoice(voice);
    } else {
        // Standard mode
        request.setVoice(profile.getStyle());
    }
    
    request.setSpeed(profile.getSpeed());
    return request;
}
```

---

## 6. MinIO Storage

### 6.1 Bucket Structure

```
Bucket: audio
└── stories/
    ├── {storyId}/
    │   ├── chapters/
    │   │   ├── {chapterId}/
    │   │   │   ├── v1.mp3          # Bản gốc (merged từ segments)
    │   │   │   └── v2.mp3          # Bản re-generate
    │   │   └── ...
    │   ├── segments/
    │   │   ├── {chapterId}/
    │   │   │   ├── v1/
    │   │   │   │   ├── seg_0.mp3   # Segment 0
    │   │   │   │   ├── seg_1.mp3   # Segment 1
    │   │   │   │   └── ...
    │   │   │   └── v2/             # Version mới (re-generate)
    │   │   └── ...
    │   └── podcast/
    │       └── rss.xml             # Podcast RSS feed (cached)
    └── ...
```

### 6.2 File Naming Convention

| Field | Format | Example |
|-------|--------|---------|
| Merged file | `stories/{storyId}/chapters/{chapterId}/v{version}.mp3` | `stories/42/chapters/128/v1.mp3` |
| Segment file | `stories/{storyId}/segments/{chapterId}/v{version}/seg_{index}.mp3` | `stories/42/segments/128/v1/seg_0.mp3` |
| Episode | `stories/{storyId}/chapters/{chapterId}/episode-{chapterNumber}.mp3` | `stories/42/chapters/128/episode-5.mp3` |
| Podcast feed | `stories/{storyId}/podcast/rss.xml` | `stories/42/podcast/rss.xml` |

### 6.3 Segment Cleanup Policy

- Segment files được xóa sau 7 ngày kể từ khi merge thành công
- Cron job chạy daily: `DELETE /api/v1/audio/admin/cleanup-segments`
- Chỉ xóa segment files, không xóa merged file
- Nếu re-generate, segment version cũ cũng được cleanup

### 6.4 Presigned URL (Streaming)

```java
// Generate presigned URL with 1-hour expiry
public String getStreamUrl(String storyId, String chapterId, int version) {
    String objectKey = String.format("stories/%s/chapters/%d/v%d.mp3", storyId, chapterId, version);
    return minioClient.getPresignedObjectUrl(
        GetPresignedObjectUrlArgs.builder()
            .bucket("audio")
            .object(objectKey)
            .method(Method.GET)
            .expiry(1, TimeUnit.HOURS)
            .build()
    );
}
```

**Range request support:** MinIO hỗ trợ HTTP Range requests mặc định. Client gửi `Range: bytes=0-1023` → nhận partial content (206).

### 6.5 Docker Compose (MinIO)

```yaml
services:
  minio:
    image: minio/minio:latest
    container_name: metruyenchu-minio
    ports:
      - "9000:9000"   # API
      - "9001:9001"   # Console
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER:-minioadmin}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:-minioadmin}
      MINIO_DOMAIN: localhost
    volumes:
      - minio-data:/data
    command: server /data --console-address ":9001"
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 30s
      timeout: 5s
      retries: 3

  createbuckets:
    image: minio/mc:latest
    depends_on:
      minio:
        condition: service_healthy
    entrypoint: >
      /bin/sh -c "
      mc alias set local http://minio:9000 minioadmin minioadmin;
      mc mb --ignore-existing local/audio;
      mc anonymous set download local/audio;
      exit 0;
      "
```

---

## 7. Database Schema

### 7.1 Table: `voice_profiles`

```sql
CREATE TABLE voice_profiles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) NOT NULL,
    description     VARCHAR(500),
    gender          VARCHAR(10) NOT NULL CHECK (gender IN ('MALE','FEMALE')),
    style           VARCHAR(50) NOT NULL DEFAULT 'gentle',
    speed           FLOAT NOT NULL DEFAULT 1.0 CHECK (speed >= 0.5 AND speed <= 2.0),
    pitch           FLOAT NOT NULL DEFAULT 1.0 CHECK (pitch >= 0.5 AND pitch <= 2.0),
    reference_audio_url  VARCHAR(500),
    reference_text       TEXT,
    is_default      BOOLEAN NOT NULL DEFAULT FALSE,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_by      VARCHAR(100) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_vp_default ON voice_profiles(is_default) WHERE is_default = TRUE;
```

### 7.2 Table: `audio_jobs` (Parent Jobs)

```sql
CREATE TABLE audio_jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    story_id        BIGINT NOT NULL,
    chapter_id      BIGINT NOT NULL,
    job_type        VARCHAR(20) NOT NULL DEFAULT 'TTS',   -- TTS | RE_GENERATE
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
        -- PENDING | PROCESSING | COMPLETED | FAILED
    voice_profile_id UUID,
    total_segments  INT NOT NULL DEFAULT 0,
    completed_segments INT NOT NULL DEFAULT 0,
    retry_count     INT NOT NULL DEFAULT 0,
    max_retries     INT NOT NULL DEFAULT 3,
    next_retry_at   TIMESTAMP WITH TIME ZONE,
    timeout_at      TIMESTAMP WITH TIME ZONE,
    version         INT NOT NULL DEFAULT 1,
    error_message   TEXT,
    metadata        JSONB,
    created_by      VARCHAR(100) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMP WITH TIME ZONE,

    CONSTRAINT fk_aj_story FOREIGN KEY (story_id) REFERENCES stories(id),
    CONSTRAINT fk_aj_chapter FOREIGN KEY (chapter_id) REFERENCES chapters(id),
    CONSTRAINT fk_aj_voice_profile FOREIGN KEY (voice_profile_id) REFERENCES voice_profiles(id)
);

CREATE INDEX idx_aj_status ON audio_jobs(status);
CREATE INDEX idx_aj_story_chapter ON audio_jobs(story_id, chapter_id);
CREATE INDEX idx_aj_created_at ON audio_jobs(created_at DESC);
```

### 7.3 Table: `segment_audio_jobs` (Segment Jobs)

```sql
CREATE TABLE segment_audio_jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_job_id   UUID NOT NULL,
    story_id        BIGINT NOT NULL,
    chapter_id      BIGINT NOT NULL,
    segment_index   INT NOT NULL,                          -- 0-based index
    segment_text    TEXT NOT NULL,                          -- Nội dung của segment này
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
        -- PENDING | PROCESSING | COMPLETED | FAILED
    retry_count     INT NOT NULL DEFAULT 0,
    max_retries     INT NOT NULL DEFAULT 2,
    error_message   TEXT,
    file_path       VARCHAR(500),                           -- MinIO path to segment mp3
    file_size       BIGINT,
    duration_ms     BIGINT,
    timeout_at      TIMESTAMP WITH TIME ZONE,
    started_at      TIMESTAMP WITH TIME ZONE,
    completed_at    TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_saj_parent FOREIGN KEY (parent_job_id) REFERENCES audio_jobs(id)
);

CREATE INDEX idx_saj_parent ON segment_audio_jobs(parent_job_id);
CREATE INDEX idx_saj_status ON segment_audio_jobs(status);
CREATE UNIQUE INDEX idx_saj_parent_segment ON segment_audio_jobs(parent_job_id, segment_index);
```

### 7.4 Table: `audio_files`

```sql
CREATE TABLE audio_files (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    story_id        BIGINT NOT NULL,
    chapter_id      BIGINT NOT NULL,
    parent_job_id   UUID NOT NULL,                          -- Link to parent job
    voice_profile_id UUID,
    version         INT NOT NULL DEFAULT 1,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | DEPRECATED
    file_path       VARCHAR(500) NOT NULL,
    file_size       BIGINT,
    duration_ms     BIGINT,
    format          VARCHAR(10) NOT NULL DEFAULT 'mp3',
    sample_rate     INT DEFAULT 24000,
    total_segments  INT NOT NULL DEFAULT 0,
    segment_durations JSONB,                                -- [1234, 2345, 1890, ...] ms per segment
    audio_metadata  JSONB,                                  -- { "channels": 1, "bitrate": 128 }
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_af_parent_job FOREIGN KEY (parent_job_id) REFERENCES audio_jobs(id),
    CONSTRAINT fk_af_voice_profile FOREIGN KEY (voice_profile_id) REFERENCES voice_profiles(id)
);

CREATE UNIQUE INDEX idx_af_active
    ON audio_files(story_id, chapter_id, version)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_af_story_chapter ON audio_files(story_id, chapter_id);
```

### 7.5 Flyway Migration

```sql
-- V1__create_audio_schema.sql
-- V1_1__add_voice_profiles.sql
-- V1_2__add_segment_jobs.sql
-- File đặt tại: audio-service/src/main/resources/db/migration/
```

---

## 8. API Endpoints

### 8.1 Voice Profiles

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `GET` | `/api/v1/audio/voice-profiles` | Danh sách voice profiles active | `ADMIN` |
| `GET` | `/api/v1/audio/voice-profiles/{id}` | Chi tiết profile | `ADMIN` |
| `POST` | `/api/v1/audio/voice-profiles` | Tạo profile mới | `ADMIN` |
| `PUT` | `/api/v1/audio/voice-profiles/{id}` | Cập nhật profile | `ADMIN` |
| `DELETE` | `/api/v1/audio/voice-profiles/{id}` | Xóa profile (soft) | `ADMIN` |
| `POST` | `/api/v1/audio/voice-profiles/{id}/clone` | Clone từ reference audio | `ADMIN` |

### 8.2 Job Management (với Split-Merge)

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `POST` | `/api/v1/audio/jobs` | Tạo job TTS cho 1 chapter (parent + N segments) | `ADMIN` |
| `POST` | `/api/v1/audio/jobs/batch` | Tạo job cho nhiều chapter / cả story | `ADMIN` |
| `POST` | `/api/v1/audio/jobs/re-generate/{chapterId}` | Re-generate với voice profile mới | `ADMIN` |
| `GET` | `/api/v1/audio/jobs` | Danh sách parent jobs (filter: status, storyId) | `ADMIN` |
| `GET` | `/api/v1/audio/jobs/{jobId}` | Chi tiết parent job + segment progress | `ADMIN` |
| `GET` | `/api/v1/audio/jobs/{jobId}/segments` | Danh sách segment jobs của parent | `ADMIN` |
| `POST` | `/api/v1/audio/jobs/{jobId}/cancel` | Hủy parent job (cancel all PENDING segments) | `ADMIN` |
| `POST` | `/api/v1/audio/jobs/{jobId}/retry` | Retry failed segments | `ADMIN` |
| `POST` | `/api/v1/audio/jobs/{jobId}/retry-segment/{segIndex}` | Retry 1 segment cụ thể | `ADMIN` |
| `GET` | `/api/v1/audio/admin/stats` | Thống kê: jobs hôm nay, processing, failed | `ADMIN` |
| `POST` | `/api/v1/audio/admin/cleanup-segments` | Cleanup segment files cũ (>7 ngày) | `ADMIN` |

**Request — POST /api/v1/audio/jobs:**
```json
{
  "storyId": 42,
  "chapterId": 128,
  "voiceProfileId": "uuid-cua-giong-nam",
  "type": "TTS"
}
```

**Response:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "parentJobId": "uuid-parent",
    "chapterId": 128,
    "voiceProfileId": "uuid",
    "voiceProfileName": "Giọng Nam trầm ấm",
    "totalSegments": 5,
    "status": "PROCESSING",
    "segments": [
      { "id": "uuid-seg-0", "index": 0, "charCount": 850, "status": "PENDING" },
      { "id": "uuid-seg-1", "index": 1, "charCount": 1200, "status": "PENDING" },
      { "id": "uuid-seg-2", "index": 2, "charCount": 950, "status": "PENDING" },
      { "id": "uuid-seg-3", "index": 3, "charCount": 1100, "status": "PENDING" },
      { "id": "uuid-seg-4", "index": 4, "charCount": 900, "status": "PENDING" }
    ]
  }
}
```

### 8.3 Audio Metadata

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `GET` | `/api/v1/audio/chapters/{chapterId}` | Thông tin audio của chapter | `USER` |
| `GET` | `/api/v1/audio/stories/{storyId}/chapters` | Danh sách chapter có audio | `USER` |
| `GET` | `/api/v1/audio/stories/{storyId}/chapters/{chapterId}/segments` | Segments metadata (read-along) | `USER` |
| `GET` | `/api/v1/audio/jobs/{jobId}/progress` | Progress: completed/total segments | `ADMIN` |

**Response — Chapter audio info:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "chapterId": 128,
    "hasAudio": true,
    "version": 1,
    "voiceProfileId": "uuid",
    "voiceProfileName": "Giọng Nam trầm ấm",
    "durationMs": 245000,
    "fileSize": 5893120,
    "totalSegments": 5,
    "segmentDurations": [4500, 6200, 5100, 5800, 4900],
    "streamUrl": "https://minio/presigned-url...",
    "podcastEpisodeUrl": "https://minio/presigned-url...",
    "createdAt": "2025-06-01T10:00:00Z"
  }
}
```

**Response — Job progress (polling):**
```json
{
  "code": 200,
  "data": {
    "parentJobId": "uuid",
    "status": "PROCESSING",
    "totalSegments": 5,
    "completedSegments": 3,
    "failedSegments": 0,
    "progressPercent": 60,
    "segments": [
      { "index": 0, "status": "COMPLETED", "durationMs": 4500 },
      { "index": 1, "status": "COMPLETED", "durationMs": 6200 },
      { "index": 2, "status": "COMPLETED", "durationMs": 5100 },
      { "index": 3, "status": "PROCESSING", "durationMs": null },
      { "index": 4, "status": "PENDING", "durationMs": null }
    ]
  }
}
```

### 8.4 Streaming

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `GET` | `/api/v1/audio/stream/{chapterId}` | Redirect → presigned URL merged file | `USER` |
| `GET` | `/api/v1/audio/stream/{chapterId}?version=2` | Stream version cụ thể | `USER` |
| `GET` | `/api/v1/audio/stream/{chapterId}/segment/{segIndex}` | Stream 1 segment riêng lẻ | `USER` |

**Streaming response:**
```
HTTP/1.1 302 Found
Location: https://minio.metruyenchu.dev/audio/stories/42/chapters/128/v1.mp3?
         X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=...
         &X-Amz-Expires=3600&X-Amz-Signature=...
```

### 8.5 Podcast RSS

| Method | Endpoint | Mô tả | Auth |
|--------|----------|-------|------|
| `GET` | `/api/v1/audio/podcast/{storyId}/feed.xml` | Podcast RSS feed cho story | `PUBLIC` |
| `POST` | `/api/v1/audio/podcast/{storyId}/regenerate` | Force regenerate RSS feed | `ADMIN` |

---

## 9. Podcast RSS Feed

### 9.1 XML Template

```xml
<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"
     xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
     xmlns:content="http://purl.org/rss/1.0/modules/content/"
     xmlns:atom="http://www.w3.org/2005/Atom">
  <channel>
    <title>Metruyenchu - {storyTitle}</title>
    <link>https://metruyenchu.dev/truyen/{storySlug}</link>
    <language>vi</language>
    <description>{storyDescription}</description>
    <itunes:author>{authorName}</itunes:author>
    <itunes:category text="Fiction"/>
    <itunes:image href="{storyCoverUrl}"/>
    <atom:link href="https://api.metruyenchu.dev/api/v1/audio/podcast/{storyId}/feed.xml"
              rel="self" type="application/rss+xml"/>
    <itunes:explicit>false</itunes:explicit>

    {chapters.map(chapter => `
    <item>
      <title>Chương {chapter.number}: {chapter.title}</title>
      <guid isPermaLink="false">audio-{storyId}-{chapter.id}-v{chapter.version}</guid>
      <enclosure url="{chapter.streamUrl}"
                 length="{chapter.fileSize}"
                 type="audio/mpeg"/>
      <pubDate>{chapter.publishedAt}</pubDate>
      <duration>{chapter.durationFormatted}</duration>
      <itunes:duration>{chapter.durationSeconds}</itunes:duration>
      <itunes:summary>{chapter.description}</itunes:summary>
    </item>
    `)}
  </channel>
</rss>
```

### 9.2 Caching

- RSS feed cached tại MinIO: `stories/{storyId}/podcast/rss.xml`
- Cache TTL: 1 giờ
- Force regenerate: admin gọi `POST /api/v1/audio/podcast/{storyId}/regenerate`
- Auto regenerate: khi có chapter mới được audio COMPLETED

---

## 10. Read-Along Support

### 10.1 Segments Metadata

Audio Service cung cấp segments metadata cho wavesurfer.js regions sync. Mỗi segment ánh xạ 1 đoạn text → 1 khoảng thời gian trong audio.

**Endpoint:** `GET /api/v1/audio/stories/{storyId}/chapters/{chapterId}/segments`

**Response:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "chapterId": 128,
    "durationMs": 245000,
    "segments": [
      {
        "id": "seg-1",
        "startMs": 0,
        "endMs": 4500,
        "text": "Trời đã về khuya, ánh trăng chiếu sáng khắp khu rừng...",
        "highlightIndex": [0, 50]
      },
      {
        "id": "seg-2",
        "startMs": 4501,
        "endMs": 9200,
        "text": "Chàng thanh niên thở dài, nhìn về phía chân trời xa thẳm...",
        "highlightIndex": [0, 45]
      }
    ]
  }
}
```

### 10.2 Tích hợp Frontend

```typescript
// frontend/hooks/useReadAlong.ts
interface ReadAlongState {
  currentSegment: Segment | null;
  isPlaying: boolean;
  currentTime: number;
}

// wavesurfer.js regions
useEffect(() => {
  if (!wavesurfer) return;

  segments.forEach((seg) => {
    wavesurfer.addRegion({
      id: seg.id,
      start: seg.startMs / 1000,
      end: seg.endMs / 1000,
      color: 'rgba(0, 0, 0, 0.1)',
      drag: false,
      resize: false,
    });
  });

  wavesurfer.on('timeupdate', (currentTime) => {
    const ms = currentTime * 1000;
    const segment = segments.find(
      (s) => ms >= s.startMs && ms <= s.endMs
    );
    if (segment) {
      setCurrentSegment(segment);
      // Highlight text trong chapter content
      highlightText(segment);
    }
  });
}, [wavesurfer, segments]);
```

### 10.3 Segment Generation Strategy

- **Sentence-based:** Tách chapter text thành sentences, mỗi sentence = 1 segment
- **Sentence embedding:** Không cần AI alignment — dùng heuristic: tổng thời gian / số câu
- **Future optimization:** Dùng alignment model (wav2vec2) cho accuracy cao hơn

---

## 11. RabbitMQ Config

### 11.1 Queues & Exchanges

```yaml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}
    listener:
      simple:
        retry:
          enabled: false    # Tắt retry mặc định — tự quản lý retry
        prefetch: 3         # Worker nhận tối đa 3 message đồng thời

audio:
  rabbitmq:
    exchange: audio.topic
    queue:
      jobs: audio.jobs
      jobs-dlx: audio.jobs.dlx
      jobs-dlq: audio.jobs.dlq
    routing:
      job-created: audio.job.created
      job-completed: audio.job.completed
      job-failed: audio.job.failed
```

**Dead Letter config:**
```java
@Bean
public Queue jobsQueue() {
    return QueueBuilder.durable("audio.jobs")
        .deadLetterExchange("audio.topic")
        .deadLetterRoutingKey("audio.job.failed")
        .ttl(1800000) // 30 phút timeout
        .build();
}

@Bean
public Queue dlq() {
    return QueueBuilder.durable("audio.jobs.dlq")
        .build();
}

@Bean
public DirectExchange retryExchange() {
    return new DirectExchange("audio.retry");
}
```

### 11.2 Event Publishing

```java
// Audio job completed → Notification Service consume
public void publishJobCompleted(AudioJob job, AudioFile file) {
    JobCompletedEvent event = new JobCompletedEvent(
        job.getId(),
        job.getStoryId(),
        job.getChapterId(),
        file.getDurationMs()
    );
    rabbitTemplate.convertAndSend(
        "audio.topic",
        "audio.job.completed",
        event
    );
}
```

---

## 12. Feign Client

### 12.1 Story Service Client

```java
@FeignClient(name = "story-service", url = "${story-service.url:http://localhost:8082}")
public interface StoryServiceClient {

    @GetMapping("/api/v1/stories/{storyId}/chapters/{chapterId}/content")
    ChapterContentResponse getChapterContent(
        @PathVariable Long storyId,
        @PathVariable Long chapterId,
        @RequestHeader("X-User-Id") String userId
    );

    @PutMapping("/api/v1/stories/{storyId}/chapters/{chapterId}/audio-status")
    void updateAudioStatus(
        @PathVariable Long storyId,
        @PathVariable Long chapterId,
        @RequestBody AudioStatusRequest request
    );

    @GetMapping("/api/v1/stories/{storyId}")
    StoryResponse getStory(@PathVariable Long storyId);
}
```

### 12.2 LocalAI Client

```java
@FeignClient(name = "localai", url = "${localai.url:http://localhost:9090}")
public interface LocalAIClient {

    @PostMapping(value = "/v1/audio/speech",
                 consumes = "application/json",
                 produces = "audio/mpeg")
    byte[] generateSpeech(@RequestBody TtsRequest request);

    @GetMapping("/v1/models")
    ModelsResponse getModels();
}
```

---

## 13. Security

| Endpoint | Auth | Ghi chú |
|----------|------|---------|
| `/api/v1/audio/jobs/**` | `ADMIN` | Quản lý job |
| `/api/v1/audio/stream/**` | `USER` | Stream audio (yêu cầu đăng nhập) |
| `/api/v1/audio/chapters/**` | `USER` | Metadata audio |
| `/api/v1/audio/podcast/**/feed.xml` | `PUBLIC` | RSS feed public |
| `/api/v1/audio/admin/**` | `ADMIN` | Thống kê |

---

## 14. Error Codes

| Code | HTTP | Ý nghĩa |
|------|------|---------|
| `AUDIO_JOB_NOT_FOUND` | 404 | Job ID không tồn tại |
| `CHAPTER_ALREADY_HAS_AUDIO` | 409 | Chapter đã có audio active |
| `JOB_NOT_CANCELLABLE` | 422 | Job không ở trạng thái PENDING |
| `TTS_GENERATION_FAILED` | 502 | LocalAI trả lỗi |
| `STORY_SERVICE_ERROR` | 502 | Không lấy được chapter content |
| `MINIO_STORAGE_ERROR` | 500 | Lỗi upload/download MinIO |
| `JOB_TIMEOUT` | 408 | Job quá 30 phút |

---

## 15. Environment Variables

| Variable | Default | Mô tả |
|----------|---------|-------|
| `STORY_SERVICE_URL` | `http://localhost:8082` | Story Service URL |
| `LOCALAI_URL` | `http://localhost:9090` | LocalAI URL |
| `LOCALAI_API_KEY` | — | API key (nếu LocalAI có auth) |
| `MINIO_URL` | `http://localhost:9000` | MinIO endpoint |
| `MINIO_ACCESS_KEY` | `minioadmin` | MinIO access key |
| `MINIO_SECRET_KEY` | `minioadmin` | MinIO secret key |
| `AUDIO_BUCKET` | `audio` | MinIO bucket name |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ host |
| `RABBITMQ_PORT` | `5672` | RabbitMQ port |
| `AUDIO_JOB_TIMEOUT_MS` | `1800000` | Job timeout (ms) |
| `AUDIO_JOB_MAX_RETRIES` | `3` | Max retry attempts |

---

## 16. Gradle Dependencies

```toml
# gradle/libs.versions.toml
[versions]
spring-cloud = "2024.0.0"
minio = "8.5.10"
resilience4j = "2.2.0"

[libraries]
spring-cloud-starter-openfeign = { module = "org.springframework.cloud:spring-cloud-starter-openfeign" }
spring-boot-starter-amqp = { module = "org.springframework.boot:spring-boot-starter-amqp" }
minio = { module = "io.minio:minio", version.ref = "minio" }
resilience4j-spring-boot3 = { module = "io.github.resilience4j:resilience4j-spring-boot3", version.ref = "resilience4j" }
```

```groovy
// audio-service/build.gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.cloud:spring-cloud-starter-openfeign'
    implementation 'org.springframework.boot:spring-boot-starter-amqp'
    implementation 'io.minio:minio'
    implementation 'io.github.resilience4j:resilience4j-spring-boot3'
    runtimeOnly 'org.postgresql:postgresql'
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-postgresql'
}
```

---

## End of Audio Service Spec
