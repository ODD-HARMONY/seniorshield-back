# SeniorShield API 명세서

**Base URL**
- HTTP (로컬/개발): `http://localhost:8080`
- HTTPS (운영): `https://kimanyfootcleaner.asuscomm.com`

**공통 사항**
- 모든 요청/응답 본문: `Content-Type: application/json; charset=UTF-8`
- 타임아웃: 분석 API는 최대 60초 소요 — 클라이언트 타임아웃을 60초 이상으로 설정

---

## 목차

1. [POST /api/analyze](#1-post-apianalyze--전체-분석-메인)
2. [POST /api/vote](#2-post-apivote--투표-제출)
3. [DELETE /api/vote](#3-delete-apivote--투표-취소)
4. [GET /api/vote](#4-get-apivote--투표-결과-조회)
5. [GET /api/health](#5-get-apihealth--상태-확인)
6. [POST /api/extract](#6-post-apiextract--자막--프레임-추출-디버그용)
7. [POST /api/classify](#7-post-apiclassify--자막-분류-디버그용)
8. [POST /api/info](#8-post-apiinfo--허위정보-분석-디버그용)
9. [POST /api/image](#9-post-apiimage--ai-이미지-판별-디버그용)
10. [POST /api/factcheck](#10-post-apifactcheck--팩트체크-디버그용)
11. [공통 오류 코드](#11-공통-오류-코드)

---

## 1. POST /api/analyze — 전체 분석 (메인)

유튜브 쇼츠 URL을 분석해 AI 생성 여부, 허위정보 여부, 커뮤니티 의심 신호를 반환합니다.  
내부적으로 extract → classify → (image ‖ info) → factcheck 순서로 파이프라인을 실행합니다.

### 요청

```
POST /api/analyze
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `url` | string | ✅ | 유튜브 쇼츠 URL |
| `lang` | string | 선택 | 응답 언어. `"ko"` (한국어, 기본값) 또는 `"en"` (영어). 미지원 값은 `"ko"`로 자동 처리 |

```json
{
  "url": "https://www.youtube.com/shorts/h5W4XNHR8BU",
  "lang": "ko"
}
```

### 응답 (200)

```json
{
  "job_id": "3a9f1c2b",
  "video_id": "h5W4XNHR8BU",
  "lang": "ko",
  "cached": false,
  "verdict": {
    "ai_generated": {
      "label": "real",
      "confidence": 0.99,
      "evidence": "자연스러운 피부 텍스처, 안경 굴절 등 실제 영상 특징 확인"
    },
    "misinformation": {
      "applicable": true,
      "label": "true",
      "confidence": 0.7,
      "claims": [
        {
          "text": "TFCC는 손목 안쪽에서 충격을 흡수하는 구조물이다.",
          "llm_judgement": "true",
          "factcheck_matches": []
        }
      ]
    },
    "advertisement": {
      "applicable": false,
      "confidence": null,
      "reason": null
    },
    "community_signal": {
      "ok_count": 10,
      "suspicious_count": 3,
      "threshold_reached": "none"
    },
    "display_message": "특별한 위험 신호가 없어요"
  },
  "stages": {
    "extract":   { "ok": true, "elapsed_ms": 2900 },
    "classify":  { "ok": true, "elapsed_ms": 1200, "informational": "true" },
    "info":      { "ok": true, "elapsed_ms": 2000 },
    "image":     { "ok": true, "elapsed_ms": 8800 },
    "factcheck": { "ok": true, "elapsed_ms": 4600 }
  },
  "elapsed_ms_total": 19400
}
```

#### verdict 필드 상세

**ai_generated**

| 필드 | 타입 | 설명 |
|------|------|------|
| `label` | string | `real` / `likely_ai` / `ai` / `uncertain` |
| `confidence` | float | 0.0 ~ 1.0 |
| `evidence` | string | 판단 근거 (`lang`에 따라 한국어 또는 영어) |

**misinformation**

| 필드 | 타입 | 설명 |
|------|------|------|
| `applicable` | bool | 정보성 영상인지 여부. `false`이면 나머지 필드 없음 |
| `label` | string | `true` / `likely_true` / `uncertain` / `likely_false` / `false` |
| `confidence` | float | 0.0 ~ 1.0. 팩트체크 기관 매칭 없으면 최대 0.7 캡 |
| `claims` | array | 주장별 판정 목록 |

**advertisement**

| 필드 | 타입 | 설명 |
|------|------|------|
| `applicable` | bool | 광고/홍보성 영상 여부 |
| `confidence` | float \| null | 광고 판정 신뢰도 (applicable=true일 때만 값 존재) |
| `reason` | string \| null | 광고 판정 근거 (applicable=true일 때만 값 존재) |

**community_signal**

| 필드 | 타입 | 설명 |
|------|------|------|
| `ok_count` | int | "괜찮아요" 투표 수 |
| `suspicious_count` | int | "이상해요" 투표 수 |
| `threshold_reached` | string | `none` / `low` (이상해요 ≥5) / `high` (이상해요 ≥20) |

**display_message** (고령층 표시용 한 줄 메시지, `lang`에 따라 언어 선택)

| 조건 | `lang=ko` | `lang=en` |
|------|-----------|-----------|
| AI 생성 + 허위정보 | `기계가 만든 영상이고, 사실과 다를 수 있어요` | `This appears to be AI-generated and may contain false information` |
| AI 생성만 | `기계가 만든 영상이에요` | `This appears to be AI-generated content` |
| 허위정보만 | `사실과 다를 수 있어요` | `This content may contain false information` |
| 이상 없음 | `특별한 위험 신호가 없어요` | `No significant warning signs detected` |
| + 커뮤니티 high | 위 메시지 + ` · 많은 분들이 의심하고 있어요` | 위 메시지 + ` · Many users have flagged this as suspicious` |
| **분석 불완전** | `분석을 완료하지 못했어요. 잠시 후 다시 시도해주세요` | `Analysis could not be completed. Please try again later.` |

> **분석 불완전** 조건: `stages.classify.ok = false` 또는 `stages.image.ok = false` — AI 이미지 판별·자막 분류 중 하나라도 실패한 경우.  
> 주요 원인: Gemini API rate limit(429), API 키 오류, analyzer 서비스 장애.  
> 이 경우 `ai_generated.label`과 `misinformation.label`은 신뢰할 수 없으며, 재시도 시 재분석됩니다(캐시 저장 안 함).

#### stages 필드

각 단계의 실행 결과. `ok: false`이면 해당 단계는 실패했지만 파이프라인은 계속 진행됩니다.

| 필드 | 타입 | 설명 |
|------|------|------|
| `ok` | bool | 성공 여부 |
| `elapsed_ms` | int | 소요 시간 (ms) |
| `informational` | string | classify 단계에만 존재. `"true"` / `"false"` |
| `error` | string | 실패 시 오류 메시지 |

### 응답 (400) — 잘못된 URL

```json
{
  "error": "invalid_url",
  "detail": "Not a YouTube URL"
}
```

### 응답 (502) — 추출 실패

| `error` 값 | 발생 상황 |
|-----------|---------|
| `video_unavailable` | 영상이 삭제/비공개/지역 제한 |
| `video_too_long` | Shorts가 아닌 긴 영상 URL |
| `extractor_error` | YouTube 봇 차단(429), 네트워크 오류 등 기타 추출 실패 |

```json
{
  "error": "video_unavailable",
  "detail": "..."
}
```

> 분석 결과는 14일간 캐시됩니다. `lang`이 다르면 별도 캐시로 관리됩니다. 동일 URL + 동일 lang 재요청 시 `cached: true`로 즉시 반환됩니다.

---

## 2. POST /api/vote — 투표 제출

"괜찮아요" 또는 "이상해요" 버튼을 누를 때 호출합니다.  
1인 1표 원칙이며, 이미 투표한 경우 다른 타입으로 변경할 수 있습니다.

### 요청

```
POST /api/vote
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `url` | string | ✅ | 유튜브 쇼츠 URL |
| `client_id` | string | ✅ | 앱 설치 시 생성한 UUID를 SHA-256 해싱한 64자 hex 문자열 |
| `vote_type` | string | ✅ | `"ok"` (괜찮아요) 또는 `"suspicious"` (이상해요) |

```json
{
  "url": "https://www.youtube.com/shorts/h5W4XNHR8BU",
  "client_id": "a3f1c2b4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2",
  "vote_type": "ok"
}
```

> `client_id` 생성 예시 (Android):
> ```kotlin
> val uuid = UUID.randomUUID().toString()
> val digest = MessageDigest.getInstance("SHA-256").digest(uuid.toByteArray())
> val clientId = digest.joinToString("") { "%02x".format(it) }
> // SharedPreferences에 저장해 재사용
> ```

### 응답 (200)

```json
{
  "ok": true,
  "url": "https://www.youtube.com/watch?v=h5W4XNHR8BU",
  "vote_type": "ok",
  "ok_count": 1,
  "suspicious_count": 0,
  "already_voted": false,
  "changed": false
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `ok` | bool | 항상 `true` |
| `url` | string | 정규화된 URL |
| `vote_type` | string | 이번 투표 타입 |
| `ok_count` | int | 투표 후 현재 "괜찮아요" 수 |
| `suspicious_count` | int | 투표 후 현재 "이상해요" 수 |
| `already_voted` | bool | 동일 타입으로 이미 투표한 경우 `true` (카운트 변화 없음) |
| `changed` | bool | 이전 투표에서 타입을 변경한 경우 `true` |

### 응답 (400)

```json
{ "error": "invalid_url" }
{ "error": "missing_client_id", "detail": "64자 hex 문자열이어야 합니다" }
{ "error": "invalid_vote_type", "detail": "ok 또는 suspicious 만 허용됩니다" }
```

### 응답 (429) — Rate Limit 초과

같은 `client_id`로 1분에 10건 초과 시 반환됩니다.

```json
{ "error": "rate_limit_exceeded" }
```

---

## 3. DELETE /api/vote — 투표 취소

자신의 투표를 취소합니다. 투표 기록이 없는 경우에도 200을 반환하며 `cancelled: false`로 구분합니다.

### 요청

```
DELETE /api/vote
Content-Type: application/json
```

```json
{
  "url": "https://www.youtube.com/shorts/h5W4XNHR8BU",
  "client_id": "a3f9...64자리hex"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `url` | string | ✅ | 유튜브 쇼츠 URL |
| `client_id` | string | ✅ | 64자 hex 문자열 |

### 응답 (200)

```json
{
  "ok": true,
  "url": "https://www.youtube.com/watch?v=h5W4XNHR8BU",
  "cancelled": true,
  "vote_type": "ok",
  "ok_count": 9,
  "suspicious_count": 3
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `cancelled` | bool | `true` = 취소됨, `false` = 취소할 투표 없음 |
| `vote_type` | string \| 없음 | 취소된 투표 타입 (`cancelled: false`이면 생략) |
| `ok_count` | int | 취소 후 현재 "괜찮아요" 수 |
| `suspicious_count` | int | 취소 후 현재 "이상해요" 수 |

### 응답 (400)

```json
{ "error": "invalid_url" }
{ "error": "missing_client_id", "detail": "64자 hex 문자열이어야 합니다" }
```

---

## 4. GET /api/vote — 투표 결과 조회

특정 URL의 투표 집계 결과를 조회합니다. 프론트에서 직접 가공해 표시합니다.

### 요청

```
GET /api/vote?url={URL인코딩된_유튜브URL}
```

```bash
curl "https://kimanyfootcleaner.asuscomm.com/api/vote?url=https%3A%2F%2Fwww.youtube.com%2Fshorts%2Fh5W4XNHR8BU"
```

### 응답 (200)

```json
{
  "url": "https://www.youtube.com/watch?v=h5W4XNHR8BU",
  "ok_count": 10,
  "suspicious_count": 3,
  "last_voted_at": "2026-06-30T06:06:43Z"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `url` | string | 정규화된 URL |
| `ok_count` | int | "괜찮아요" 누적 수. 투표 없으면 `0` |
| `suspicious_count` | int | "이상해요" 누적 수. 투표 없으면 `0` |
| `last_voted_at` | string \| null | 마지막 투표 시각 (ISO 8601). 투표 없으면 `null` |

---

## 5. GET /api/health — 상태 확인

5개 컴포넌트 상태를 확인합니다.

### 요청

```
GET /api/health
```

### 응답 (200)

```json
{
  "gateway":   "ok",
  "extractor": "ok",
  "analyzer":  "ok",
  "factcheck": "ok",
  "db":        "ok"
}
```

각 값은 `"ok"` / `"unavailable"` / `"error"` 중 하나입니다.  
HTTP 상태코드는 항상 200입니다 (개별 컴포넌트 상태는 본문으로 판단).

---

## 6. POST /api/extract — 자막 + 프레임 추출 (디버그용)

YouTube URL에서 자막과 프레임을 추출합니다.

### 요청

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `url` | string | ✅ | 유튜브 쇼츠 URL |
| `frame_count` | int | 선택 | 추출할 프레임 수 (기본값: 3) |
| `subtitle_lang` | string | 선택 | 선호 자막 언어 (기본값: `"ko"`). 없으면 자동 fallback |

```json
{
  "url": "https://www.youtube.com/shorts/h5W4XNHR8BU",
  "frame_count": 3,
  "subtitle_lang": "ko"
}
```

### 응답 (200)

```json
{
  "video_id": "h5W4XNHR8BU",
  "title": "손목이 아프다면? TFCC 손상 의심",
  "duration_sec": 50,
  "subtitle": {
    "available": true,
    "source": "auto",
    "language": "ko",
    "text": "손목을 돌릴 때 지긋하고 아프다면...",
    "char_count": 312,
    "segment_count": 18,
    "original_language": "ko",
    "selection_reason": "requested_lang_auto",
    "available_langs": {
      "manual": [],
      "auto": ["ko", "en", "ja"]
    }
  },
  "frames": {
    "count": 3,
    "timestamps_sec": [12.5, 25.0, 37.5],
    "frames_base64": ["<base64>", "<base64>", "<base64>"]
  }
}
```

**subtitle 추가 필드**

| 필드 | 타입 | 설명 |
|------|------|------|
| `original_language` | string \| null | 실제 다운로드된 자막 언어 코드 |
| `selection_reason` | string \| null | 언어 선택 이유. 아래 표 참고 |
| `available_langs.manual` | string[] | 영상에 존재하는 수동 자막 언어 목록 |
| `available_langs.auto` | string[] | 영상에 존재하는 자동 생성 자막 언어 목록 (최대 10개) |

| `selection_reason` 값 | 의미 |
|----------------------|------|
| `requested_lang_manual` | 요청한 언어의 수동 자막 사용 |
| `requested_lang_auto` | 요청한 언어의 자동 자막 사용 |
| `fallback_to_{lang}_manual` | 요청 언어 없어 fallback 언어 수동 자막 사용 |
| `fallback_to_{lang}_auto` | 요청 언어 없어 fallback 언어 자동 자막 사용 |
| `no_subtitle_available` | 자막 없음 |

---

## 7. POST /api/classify — 자막 분류 (디버그용)

자막 텍스트가 정보성인지 분류하고 카테고리를 반환합니다.

### 요청

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `subtitle_text` | string | ✅ | 자막 텍스트 |
| `lang` | string | 선택 | 응답 언어 (`"ko"` / `"en"`, 기본값: `"ko"`) |

### 응답 (200)

```json
{
  "informational": true,
  "category": "health",
  "key_topic": "손목 삼각섬유연골복합체(TFCC) 손상의 증상 및 주의사항"
}
```

| `category` 값 | 설명 |
|--------------|------|
| `health` | 건강/의학 정보 |
| `finance` | 금융/투자 정보 |
| `news` | 시사/뉴스 |
| `science` | 과학/기술 정보 |
| `other` | 기타 정보성 |

---

## 8. POST /api/info — 허위정보 분석 (디버그용)

자막에서 주장을 추출하고 사실 여부를 예비 판단합니다.

### 요청

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `subtitle_text` | string | ✅ | 자막 텍스트 |
| `category` | string | 선택 | classify에서 반환한 카테고리 (기본값: `"other"`) |
| `lang` | string | 선택 | 응답 언어 (`"ko"` / `"en"`, 기본값: `"ko"`) |

### 응답 (200)

```json
{
  "overall_judgement": "true",
  "confidence": 0.9,
  "claims": [
    {
      "text": "손목을 돌릴 때 지긋하고 아프다면 TFCC 손상일 수 있습니다.",
      "normalized": "손목 통증은 TFCC 손상으로 인한 것일 수 있다.",
      "preliminary_judgement": "true"
    }
  ]
}
```

| `overall_judgement` / `preliminary_judgement` 값 | 설명 |
|--------------------------------------------------|------|
| `true` | 사실 |
| `likely_true` | 대체로 사실 |
| `uncertain` | 판단 불가 |
| `likely_false` | 대체로 거짓 |
| `false` | 거짓 |

---

## 9. POST /api/image — AI 이미지 판별 (디버그용)

프레임 이미지로 AI 생성 여부를 판별합니다.

### 요청

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `frames_base64` | string[] | ✅ | JPEG 프레임을 base64 인코딩한 배열 |
| `lang` | string | 선택 | 응답 언어 (`"ko"` / `"en"`, 기본값: `"ko"`) |

### 응답 (200)

```json
{
  "label": "real",
  "confidence": 0.99,
  "per_frame": [
    { "label": "real", "confidence": 0.99 },
    { "label": "real", "confidence": 0.98 },
    { "label": "real", "confidence": 0.99 }
  ],
  "aggregate_evidence": "자연스러운 피부 텍스처, 안경 렌즈 굴절, 배경 일관성 확인"
}
```

| `label` 값 | 설명 |
|-----------|------|
| `real` | 실제 영상 |
| `likely_ai` | AI 생성 의심 |
| `ai` | AI 생성 확실 |
| `uncertain` | 판단 불가 |

---

## 10. POST /api/factcheck — 팩트체크 (디버그용)

Google Fact Check Tools API로 주장의 팩트체크 결과를 조회합니다.

### 요청

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `claim` | string | ✅ | 검증할 주장 텍스트 |
| `lang` | string | 선택 | 검색 시작 언어 (`"ko"` / `"en"`, 기본값: `"ko"`). 결과 없으면 fallback 언어로 재시도 |

### 응답 (200)

```json
{
  "matches": [
    {
      "publisher": "Reuters",
      "url": "https://reuters.com/article/...",
      "title": "팩트체크 기사 제목",
      "rating": "False",
      "ratingNormalized": "false",
      "reviewDate": "2024-03-15"
    }
  ],
  "language_used": "en",
  "fallback_applied": true
}
```

| `ratingNormalized` 값 | 설명 |
|----------------------|------|
| `true` | 사실 |
| `likely_true` | 대체로 사실 |
| `uncertain` | 판단 유보 |
| `likely_false` | 대체로 거짓 |
| `false` | 거짓 |
| `unknown` | 분류 불가 |

| `fallback_applied` | bool | 요청 언어에서 결과가 없어 다른 언어로 재시도한 경우 `true` |

팩트체크 기관이 다루지 않는 주제(의학 해부학 등)는 `matches: []`로 반환됩니다.  
fallback 순서: `ko` → `en`, `en` → `ko`

---

## 11. 공통 오류 코드

| HTTP | `error` 값 | 발생 상황 |
|------|-----------|---------|
| 400 | `invalid_url` | YouTube URL이 아니거나 형식 오류 |
| 400 | `missing_client_id` | client_id 누락 또는 64자 hex 형식 불일치 |
| 400 | `invalid_vote_type` | vote_type이 `ok` / `suspicious` 이외의 값 |
| 400 | `missing_url_parameter` | GET 요청에 url 파라미터 없음 |
| 429 | `rate_limit_exceeded` | 같은 client_id로 1분에 10건 초과 |
| 502 | `video_unavailable` | 영상이 삭제/비공개/지역 제한 |
| 502 | `video_too_long` | Shorts가 아닌 긴 영상 URL |
| 502 | `extractor_error` | YouTube 봇 차단(429), 네트워크 오류 등 기타 추출 실패 |
| 500 | `internal_error` | 서버 내부 오류 |

> classify / info / image 단계 실패는 502를 반환하지 않고, `/api/analyze` 응답의 `stages[xxx].ok = false`로 표현됩니다. 해당 단계 결과는 `"uncertain"`으로 처리됩니다.
