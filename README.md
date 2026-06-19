# SeniorShield Backend

고령층을 위한 유튜브 쇼츠 위험성 분석 백엔드.  
유튜브 쇼츠 URL 하나를 받아 **AI 생성 여부**, **허위정보 여부**, **커뮤니티 의심 신호**를 반환합니다.

> 아키텍처 상세 → [`docs/architecture.md`](docs/architecture.md)

---

## 요구사항

| 항목 | 필수 | 비고 |
|------|------|------|
| Docker 24 이상 | ✅ | |
| Docker Compose v2 이상 | ✅ | |
| Gemini API 키 | ✅ | [Google AI Studio](https://aistudio.google.com/) 에서 발급 |
| Google Fact Check Tools API 키 | 선택 | 없으면 팩트체크 단계 스킵 |
| SSL 인증서 (Let's Encrypt) | 선택 | HTTPS 운영 시 필요. 로컬/개발 환경은 불필요 |

---

## 빠른 시작

### 1. 저장소 클론

```bash
git clone <repository-url>
cd seniorShield_back
```

### 2. 환경 변수 설정

```bash
cp .env.example .env
```

`.env` 파일을 열어 API 키를 입력합니다.

```env
GEMINI_API_KEY=your_gemini_api_key_here
GOOGLE_FACTCHECK_API_KEY=your_factcheck_api_key_here   # 선택사항
```

모델 이름은 기본값을 그대로 사용해도 됩니다. 변경 시 [지원 모델 목록](https://ai.google.dev/gemini-api/docs/models) 참고.

### 3. YouTube 쿠키 설정 (YouTube 접근 차단 방지)

Docker 컨테이너 IP는 YouTube에 의해 봇으로 차단될 수 있습니다.  
브라우저 쿠키를 내보내 인증된 요청을 보낼 수 있습니다.

```bash
# yt-dlp 설치 후 Chrome 쿠키 내보내기
pip install yt-dlp
yt-dlp --cookies-from-browser chrome --cookies cookies.txt "https://www.youtube.com/shorts/dQw4w9WgXcQ"
```

`cookies.txt` 파일이 프로젝트 루트에 생성됩니다.  
이 파일은 `.gitignore`에 포함되어 있으므로 커밋되지 않습니다.

### 4. 빌드 및 실행

```bash
docker compose build
docker compose up -d
```

첫 빌드는 Maven 의존성 다운로드로 인해 5~10분이 소요됩니다.

### 5. 동작 확인

```bash
curl http://localhost:8080/api/health
```

---

## HTTPS 설정 (선택)

도메인과 SSL 인증서가 있을 때 HTTPS로 운영할 수 있습니다.  
인증서 없이도 HTTP(`localhost:8080`)로 정상 동작합니다.

### 1. Let's Encrypt 인증서 발급

```bash
sudo certbot certonly --standalone -d your-domain.com
```

### 2. 인증서를 프로젝트에 복사

```bash
sudo mkdir -p ./nginx/certs
sudo cp /etc/letsencrypt/live/your-domain.com/*.pem ./nginx/certs/
sudo chown $USER:$USER ./nginx/certs/*.pem
```

### 3. nginx.conf 도메인 수정

[`nginx/nginx.conf`](nginx/nginx.conf) 에서 `server_name`을 자신의 도메인으로 변경합니다.

### 4. SSL 프로필로 실행

```bash
docker compose --profile ssl up -d
```

nginx 컨테이너가 추가로 실행되며 80→443 리디렉트와 HTTPS 프록시가 활성화됩니다.

> 인증서 갱신 방법은 [`../SSL 인증서 갱신.md`](../SSL%20인증서%20갱신.md) 참고.

```json
{
  "gateway": "ok",
  "extractor": "ok",
  "analyzer": "ok",
  "factcheck": "ok",
  "db": "ok"
}
```

5개 컴포넌트가 모두 `ok`이면 정상입니다.

---

## API

모든 요청은 `http://localhost:8080`으로 보냅니다.

### `POST /api/analyze` — 전체 분석 (메인)

```bash
curl -X POST http://localhost:8080/api/analyze \
  -H "Content-Type: application/json" \
  -d '{"url": "https://www.youtube.com/shorts/XXXXXXXXXXX"}'
```

**응답 예시**

```json
{
  "job_id": "3a9f1c2b",
  "video_id": "XXXXXXXXXXX",
  "cached": false,
  "verdict": {
    "ai_generated": {
      "label": "likely_ai",
      "confidence": 0.95,
      "evidence": "..."
    },
    "misinformation": {
      "applicable": true,
      "label": "likely_false",
      "confidence": 0.82,
      "claims": [...]
    },
    "advertisement": { "applicable": false },
    "community_signal": {
      "suspicion_count": 7,
      "threshold_reached": "low"
    },
    "display_message": "기계가 만든 영상이에요"
  },
  "stages": {
    "extract":   { "ok": true,  "elapsed_ms": 2900 },
    "classify":  { "ok": true,  "elapsed_ms": 5300, "informational": "false" },
    "info":      { "ok": true,  "elapsed_ms": 0 },
    "image":     { "ok": true,  "elapsed_ms": 7500 },
    "factcheck": { "ok": true,  "elapsed_ms": 0 }
  },
  "elapsed_ms_total": 15600
}
```

| `verdict` 필드 | 설명 |
|----------------|------|
| `ai_generated.label` | `real` / `likely_ai` / `ai` / `uncertain` |
| `misinformation.label` | `true` / `likely_true` / `uncertain` / `likely_false` / `false` |
| `community_signal.threshold_reached` | `none` / `low` (≥5) / `high` (≥20) |
| `display_message` | 고령층 표시용 한국어 한 줄 메시지 |

---

### `POST /api/suspicion` — 의심 신고

```bash
curl -X POST http://localhost:8080/api/suspicion \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://www.youtube.com/shorts/XXXXXXXXXXX",
    "client_id": "64자_hex_문자열"
  }'
```

`client_id`는 앱 설치 시 생성한 UUID를 SHA-256 해시한 64자 hex 문자열입니다.

**응답**

```json
{
  "ok": true,
  "url": "https://www.youtube.com/watch?v=XXXXXXXXXXX",
  "suspicion_count": 17,
  "already_reported": false
}
```

같은 `client_id`로 같은 URL을 재신고하면 `already_reported: true`를 반환하고 카운트는 늘리지 않습니다.

---

### `GET /api/suspicion?url=...` — 의심 카운트 조회

```bash
curl "http://localhost:8080/api/suspicion?url=https%3A%2F%2Fwww.youtube.com%2Fshorts%2FXXXXXXXXXXX"
```

---

### `GET /api/health` — 상태 확인

```bash
curl http://localhost:8080/api/health
```

---

### 개별 단계 API (디버깅용)

| 메서드 | 경로 | 입력 |
|--------|------|------|
| `POST` | `/api/extract` | `{"url": "...", "frame_count": 3}` |
| `POST` | `/api/classify` | `{"url": "..."}` 또는 `{"subtitle_text": "..."}` |
| `POST` | `/api/info` | `{"subtitle_text": "...", "category": "health"}` |
| `POST` | `/api/image` | `{"frames_base64": ["...", "...", "..."]}` |
| `POST` | `/api/factcheck` | `{"claim": "...", "language": "ko"}` |

---

## 환경 변수 전체 목록

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `GEMINI_API_KEY` | — | Gemini API 키 (필수) |
| `MODEL_CLASSIFY` | `gemini-2.0-flash` | 자막 분류 모델 |
| `MODEL_INFO` | `gemini-2.5-flash` | 허위정보 분석 모델 |
| `MODEL_IMAGE` | `gemini-2.0-flash` | AI 이미지 감지 모델 |
| `GOOGLE_FACTCHECK_API_KEY` | — | Fact Check Tools API 키 (없으면 팩트체크 스킵) |
| `ANALYZER_TIMEOUT_MS` | `60000` | analyzer 호출 타임아웃 (ms) |
| `EXTRACTOR_TIMEOUT_MS` | `30000` | extractor 호출 타임아웃 (ms) |
| `FACTCHECK_TIMEOUT_MS` | `10000` | factcheck 호출 타임아웃 (ms) |
| `CACHE_TTL_DAYS` | `14` | 분석 결과 캐시 유지 기간 (일) |
| `FRAME_COUNT` | `3` | 추출 프레임 수 |
| `MAX_DURATION_SEC` | `120` | 허용 최대 영상 길이 (초) |

---

## 운영

### 로그 확인

```bash
docker compose logs -f gateway     # Java 서블릿 로그
docker compose logs -f analyzer    # Gemini API 호출 로그
docker compose logs -f extractor   # yt-dlp / ffmpeg 로그
```

### 컨테이너 재시작 (환경 변수 변경 후)

```bash
docker compose up -d
```

### 전체 종료 (데이터 유지)

```bash
docker compose down
```

### 전체 초기화 (DB, 볼륨 포함 삭제)

```bash
docker compose down -v
```

### DB 스키마 마이그레이션 (컨테이너 재시작 없이)

```bash
docker compose exec mariadb mariadb -u root -p${MARIADB_ROOT_PASSWORD} ${MARIADB_DATABASE} < db/init/02_suspicion.sql
```

---

## 트러블슈팅

| 증상 | 원인 | 조치 |
|------|------|------|
| `extractor_timeout` 오류 | YouTube 봇 차단 (429) | `cookies.txt` 생성 후 `docker compose up -d extractor` |
| `analyzer` 502 오류 | Gemini API 키 오류 또는 모델 미지원 | `.env`의 `GEMINI_API_KEY`, `MODEL_*` 확인 |
| `image` 단계 타임아웃 | Gemini 응답 지연 | `ANALYZER_TIMEOUT_MS=60000`으로 증가 |
| `db: unavailable` | MariaDB 연결 실패 | `docker compose logs mariadb` 확인 |
| 같은 URL 재분석 시 캐시 반환 | 정상 동작 | 강제 재분석 필요 시 DB에서 `DELETE FROM analysis_cache WHERE url_hash = ?` |
| HTTPS 접속 안 됨 | nginx가 실행되지 않음 | `docker compose --profile ssl up -d` 로 실행했는지 확인 |
| SSL 인증서 오류 | 인증서 만료 또는 경로 불일치 | `nginx/certs/` 재복사 후 `docker compose restart nginx` |
