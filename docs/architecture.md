# SeniorShield 백엔드 아키텍처

## 1. 전체 구조

```
Android 앱
    │  HTTPS (JSON)  ← SSL 사용 시
    │  HTTP  (JSON)  ← 로컬/개발 환경
    ▼
┌─────────────────────────────────────────┐
│   nginx  (선택 / --profile ssl)         │
│   :80 → 443 리디렉트                    │
│   :443 TLS 종단 → gateway:8080 프록시   │
└──────────────────┬──────────────────────┘
                   │ HTTP (내부)
                   ▼
┌─────────────────────────────────────────────────────────┐
│              Gateway  (Tomcat 9.0 / Java 11)            │
│                        :8080                            │
│                                                         │
│  AnalyzeServlet ──► AnalysisPipeline ──► ResultAggregator│
│  SuspicionServlet     CacheDao / SuspicionDao           │
│  HealthServlet        RateLimitFilter                   │
└──────────┬──────────────────────┬───────────────────────┘
           │ HTTP/1.1 (내부)      │ JDBC
           │                      ▼
    ┌──────┴──────┐         ┌──────────────┐
    │             │         │   MariaDB 11  │
    ▼             ▼         │  :3306        │
┌────────┐  ┌──────────┐   │ analysis_cache│
│extractor│  │ analyzer │   │ suspicion_*   │
│FastAPI  │  │ FastAPI  │   └──────────────┘
│ :8000   │  │ :8001    │
└────────┘  └──────────┘
                │ HTTP (내부)
                ▼
          ┌──────────────┐         ┌─────────────────────┐
          │  factcheck   │         │  Google Gemini API  │
          │  FastAPI     │────────►│  (외부)             │
          │  :8002       │         └─────────────────────┘
          └──────┬───────┘
                 │ HTTP
                 ▼
        ┌─────────────────────┐
        │ Google Fact Check   │
        │ Tools API (외부)    │
        └─────────────────────┘
```

모든 서비스는 `docker compose`로 `ss-net` 브리지 네트워크 위에서 실행됩니다.  
extractor / analyzer / factcheck는 내부 포트만 노출 (`expose`)하고, 외부에서는 gateway(8080) 또는 nginx(443)를 통해서만 접근합니다.  
nginx는 `--profile ssl` 옵션을 붙일 때만 실행되는 선택적 서비스입니다.

---

## 2. 서비스별 역할

### 2.0 nginx (선택 / `--profile ssl`)

Let's Encrypt 인증서를 사용한 TLS 종단 처리를 담당합니다.

- `:80` → `:443` 영구 리디렉트
- `:443` SSL 종단 후 `gateway:8080`으로 역방향 프록시
- 인증서는 `nginx/certs/` 폴더에 복사해서 사용 (`.gitignore` 적용)
- `docker compose up -d` 만으로는 실행되지 않으며, `--profile ssl`을 명시해야 활성화됨

### 2.1 gateway (Tomcat 9.0 / Java 11)

전체 시스템의 진입점. 다음 책임을 가집니다.

- **라우팅**: URL 정규화 → 각 파이썬 서비스 호출
- **파이프라인 오케스트레이션**: `AnalysisPipeline`에서 단계별 호출 및 병렬 처리
- **캐싱**: MariaDB `analysis_cache` 테이블에 SHA-256 URL 해시로 결과 저장
- **집단지성**: `SuspicionDao`로 의심 신고 수 집계, `ResultAggregator`에서 가중치 반영
- **Rate limit**: `RateLimitFilter`로 client_id 기준 분당 10건 제한

HTTP 통신 시 모든 Python 서비스에 `.version(HTTP_1_1)`을 명시합니다.  
(Java 11 `HttpClient` 기본값인 HTTP/2 업그레이드 요청이 uvicorn에서 바디를 유실시키는 문제 방지)

### 2.2 extractor (Python FastAPI / :8000)

YouTube에서 미디어를 추출합니다.

- **자막**: `yt-dlp`로 메타데이터 먼저 조회 → 실제 존재하는 언어만 확인 후 단일 언어 VTT 다운로드 → `webvtt-py`로 파싱, HTML 태그 제거, 중복 세그먼트 병합
  - 언어 우선순위: 요청 언어 수동 자막 → 요청 언어 자동 자막 → fallback 언어 수동 → fallback 언어 자동
  - 응답에 `available_langs`, `selection_reason`, `original_language` 포함
- **프레임**: `yt-dlp`로 최저화질 비디오 다운로드 → `ffmpeg`로 영상 길이를 `(count+1)` 등분한 타임스탬프에서 프레임 캡처
- 프레임은 base64로 인코딩하여 응답에 포함 후 즉시 삭제 (디스크 미보존)
- YouTube 봇 차단 방지를 위해 브라우저 쿠키 파일(`COOKIE_FILE`) 지원
- yt-dlp JS 런타임으로 Node.js 사용 (컨테이너에 포함)

### 2.3 analyzer (Python FastAPI / :8001)

Gemini API를 호출하는 분석 서비스. 3개의 엔드포인트를 제공합니다.

| 엔드포인트 | 모델 | 역할 |
|-----------|------|------|
| `POST /classify` | `MODEL_CLASSIFY` | 자막 → 정보성 여부 + 카테고리 분류 |
| `POST /info` | `MODEL_INFO` | 자막 → 주장 추출 + 사실 여부 예비 판단 (Grounding 지원) |
| `POST /image` | `MODEL_IMAGE` | 프레임 이미지 → AI 생성 여부 판별 (멀티모달) |

프롬프트 파일은 `lang`별 서브디렉터리(`prompts/ko/`, `prompts/en/`)로 분리되어 언어별 프롬프트를 독립 관리합니다.  
볼륨으로 마운트(`./prompts:/app/app/prompts:ro`)되어 **재빌드 없이** 교체 가능합니다.

### 2.4 factcheck (Python FastAPI / :8002)

Google Fact Check Tools API를 래핑합니다.

- `POST /factcheck` — 주장 텍스트 → 팩트체크 기관 판정 결과 반환
- `lang` 기준 fallback 순서로 재시도: `ko` → `en`, `en` → `ko`
- 결과에 `fallback_applied` 필드로 fallback 여부 표시
- API 키가 없거나 주장이 없으면 빈 결과 반환 (파이프라인은 계속 진행)

### 2.5 MariaDB 11

두 가지 목적으로 사용됩니다.

| 테이블 | 용도 |
|--------|------|
| `analysis_cache` | 분석 결과 캐싱. 복합 PK: `(url_hash, lang)` + 모델 버전으로 키 구성 |
| `suspicion_reports` | 개별 투표 로그 (1인 1표 보장: `UNIQUE KEY(url, client_id)`) |
| `suspicion_aggregate` | 영상별 투표 카운트 집계 (빠른 조회용) |

---

## 3. 분석 파이프라인 흐름

```
POST /api/analyze  { url, lang }
        │
        ▼
  LangValidator.normalize(lang)  →  "ko" 또는 "en"
        │
        ▼
  URL 정규화 (UrlValidator.normalize)
        │
        ▼
  캐시 조회 (url_hash, lang, 모델 버전 모두 일치 시 즉시 반환)
        │ miss
        ▼
  [1] extract (subtitle_lang=lang) ──────────────────── 순차
        │ subtitle 메타데이터 사전 조회 → 존재하는 언어만 다운로드
        │ subtitle_text, frames_base64, available_langs, selection_reason
        ▼
  [2] classify (lang) ───────────────────────────────── 순차
        │ 자막 없으면 notApplicable() 즉시 반환 (API 호출 없음)
        │ informational, category
        ▼
  [3] image (lang) ◄───────────────┐
  [4] info  (lang) ◄──── 병렬 ─────┤  (CompletableFuture)
        │              (informational=false이면 info 스킵)
        ▼
  [5] factcheck (lang, fallback 포함) — info.claims 순차 호출
        │
        ▼
  ResultAggregator.aggregate(lang)
        │
        ▼
  VoteDao.getCounts(url)
        │
        ▼
  ResultAggregator.applySuspicionWeight(lang)
        │
        ▼
  DB 캐시 저장 (url_hash, lang)
        │
        ▼
  응답 반환 (lang 필드 포함)
```

**단계별 실측 소요 시간** (네트워크·API 부하에 따라 변동)

| 단계 | 소요 | 비고 |
|------|------|------|
| extract | ~2–3초 | yt-dlp + ffmpeg |
| classify | ~1–6초 | `gemini-3.1-flash-lite` 기준 ~1초, `gemini-3.5-flash` 기준 ~4–6초 |
| info | ~2–4초 | `gemini-2.5-flash-lite` 기준 ~2–4초, image와 병렬 실행 |
| image | ~7–10초 | `gemini-3.5-flash` 기준, 병렬 블록의 병목 |
| factcheck | ~1–2초/주장 | 주장 없으면 스킵 |
| **합계 (/api/analyze)** | **~18–25초** | 캐시 히트 시 <1초 |

> image와 info가 병렬 처리되므로 전체 소요는 `extract + classify + max(image, info) + factcheck`로 결정됩니다.  
> classify 모델을 빠르게 교체해도 image가 병목이면 전체 시간 단축 효과가 제한됩니다.

---

## 4. 집단지성 (Community Suspicion)

사용자가 "의심돼요" 버튼을 누르면 `POST /api/suspicion`으로 신고가 기록됩니다.

```
신고 접수
  │
  ├─ RateLimitFilter: 같은 client_id가 1분에 10건 초과 시 429 반환
  │
  ├─ UrlValidator.canonicalize(): URL 정규화 (캐시 키와 동일)
  │
  └─ SuspicionDao.report() 트랜잭션:
       ① INSERT INTO suspicion_reports (url, client_id)
          → 중복이면 already_reported=true, 카운트 변경 없음
       ② INSERT INTO suspicion_aggregate ... ON DUPLICATE KEY UPDATE count+1
       ③ SELECT suspicion_count (응답용)
```

`/api/analyze` 응답에 포함되는 가중치 결합 규칙:

| 누적 신고 수 | threshold_reached | 효과 |
|------------|-------------------|------|
| 0–4 | `none` | 변화 없음 |
| 5–19 | `low` | `misinformation.confidence +0.10`, `uncertain → likely_false` |
| 20+ | `high` | `misinformation.confidence +0.20`, `ai_generated.confidence +0.10`, `uncertain → likely_false` |

`likely_true → likely_false` 전환은 의도적으로 막아 어뷰징(의심 폭탄)에 시스템이 휘둘리지 않도록 설계했습니다.

---

## 5. 캐싱 전략

```
캐시 키 = (SHA-256(canonical_url), lang) + MODEL_CLASSIFY + MODEL_INFO + MODEL_IMAGE
```

- `lang`이 다르면 별도 캐시로 저장됩니다 (`ko`와 `en` 결과 독립 보관).
- URL은 DB에 저장되지 않고 해시만 저장됩니다 (개인정보 보호).
- 모델 이름이 변경되면 캐시 키가 달라져 자동으로 재분석됩니다.
- `CACHE_TTL_DAYS` (기본 14일) 이후 만료됩니다.
- Tomcat 액세스 로그는 `%t %s %D ms` 패턴으로 URL/IP를 기록하지 않습니다.

---

## 6. 디렉터리 구조

```
seniorShield_back/
├── docker-compose.yml          # 6개 서비스 정의 (nginx는 --profile ssl 시에만 활성화)
├── .env                        # API 키 및 설정 (gitignore)
├── .env.example                # 설정 템플릿
├── cookies.txt                 # YouTube 쿠키 (gitignore)
│
├── gateway/                    # Tomcat 9.0 Java 서블릿
│   ├── Dockerfile              # Multi-stage: Maven build → Tomcat
│   ├── pom.xml
│   ├── server.xml              # 커스텀 액세스 로그 (URL 비기록)
│   └── src/main/java/com/seniorshield/
│       ├── cache/              # CacheDao, SuspicionDao
│       ├── client/             # ExtractorClient, AnalyzerClient, FactCheckClient
│       ├── filter/             # RateLimitFilter
│       ├── model/              # AnalyzeResponse, ClassifyResult, ...
│       ├── pipeline/           # AnalysisPipeline, ResultAggregator
│       ├── servlet/            # 7개 서블릿
│       └── util/               # UrlValidator, HashUtil, JsonUtil, LangValidator
│
├── extractor/                  # Python FastAPI (yt-dlp + ffmpeg)
│   ├── Dockerfile
│   ├── requirements.txt
│   └── app/
│       ├── main.py
│       ├── extractor.py        # yt-dlp + ffmpeg 로직
│       └── subtitle_parser.py  # VTT 파싱
│
├── analyzer/                   # Python FastAPI (Gemini API)
│   ├── Dockerfile
│   ├── requirements.txt
│   └── app/
│       ├── main.py
│       ├── gemini/             # client.py, classify.py, info.py, image.py
│       └── routers/            # classify.py, info.py, image.py
│
├── factcheck/                  # Python FastAPI (Google Fact Check Tools)
│   ├── Dockerfile
│   ├── requirements.txt
│   └── app/
│       ├── main.py
│       └── google_factcheck.py
│
├── db/
│   └── init/
│       ├── 01_schema.sql       # analysis_cache 테이블
│       ├── 02_suspicion.sql    # suspicion_reports, suspicion_aggregate 테이블
│       └── 04_lang.sql         # analysis_cache 에 lang 컬럼 추가 (복합 PK)
│
├── nginx/                      # HTTPS 설정 (--profile ssl 사용 시)
│   ├── nginx.conf              # 80→443 리디렉트 + 역방향 프록시 설정
│   └── certs/                  # Let's Encrypt 인증서 (gitignore)
│       ├── fullchain.pem
│       └── privkey.pem
│
├── prompts/                    # Gemini 프롬프트 (볼륨 마운트, 재빌드 없이 교체 가능)
│   ├── ko/                     # 한국어 프롬프트
│   │   ├── subtitle_classify.txt
│   │   ├── info_extract.txt
│   │   └── image_aigen.txt
│   └── en/                     # 영어 프롬프트
│       ├── subtitle_classify.txt
│       ├── info_extract.txt
│       └── image_aigen.txt
│
└── docs/
    └── architecture.md         # 이 문서
```

---

## 7. 주요 기술 선택 근거

| 결정 | 이유 |
|------|------|
| Tomcat 9.0 (Servlet API) | 경량 WAR 배포, Spring 없이 의존성 최소화 |
| Java `HttpClient` HTTP/1.1 강제 | uvicorn이 HTTP/2 업그레이드 요청 시 POST 바디를 유실하는 버그 방지 |
| Python FastAPI | async I/O로 Gemini API 병렬 호출, yt-dlp와 통합 용이 |
| 프롬프트 외부 파일 마운트 | LLM 프롬프트는 코드가 아닌 설정. 재빌드 없이 튜닝 가능 |
| URL → SHA-256 해시 캐싱 | DB에 원본 URL 미저장으로 개인정보 보호 |
| 두 개의 suspicion 테이블 | 원본 로그(reports)와 집계(aggregate) 분리로 조회 성능 최적화 |
| HikariCP `setDriverClassName` | Tomcat WebApp 클래스로더에서 `DriverManager`가 WAR 내 MariaDB 드라이버를 자동 탐지하지 못하는 문제 해결 |
| nginx Docker Compose profile | SSL이 선택사항이므로 `--profile ssl` 없이 실행하면 nginx가 기동되지 않아 인증서 없는 환경에서도 오류 없이 구동 |
| nginx에서 TLS 종단 | gateway(Tomcat)는 HTTP만 처리하고 SSL을 nginx에 위임하여 인증서 관리와 애플리케이션 로직을 분리 |
