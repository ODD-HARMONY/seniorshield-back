# SeniorShield API 명세서

**Base URL**
- HTTP (로컬/개발): `http://localhost:8080`
- HTTPS (운영): `https://kimanyfootcleaner.asuscomm.com`

**공통 사항**
- 모든 요청/응답 본문: `Content-Type: application/json; charset=UTF-8`
- 타임아웃: 분석 API는 최대 30초 내외 소요 — 클라이언트 타임아웃을 60초 이상으로 설정

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
10. [POST /api/ad](#10-post-apiad--광고사기-검증-디버그용)
11. [POST /api/factcheck](#11-post-apifactcheck--팩트체크-디버그용)
12. [공통 오류 코드](#12-공통-오류-코드)

---

## 1. POST /api/analyze — 전체 분석 (메인)

유튜브 쇼츠 URL을 분석해 AI 생성 여부, 허위정보 여부, 광고·사기 의심 여부, 커뮤니티 의심 신호를 반환합니다.  
내부적으로 extract → classify → (image ‖ info ‖ ad_verify) → factcheck 순서로 파이프라인을 실행합니다.

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

### 응답 (200) — 정상 영상

```json
{
  "job_id": "3a9f1c2b",
  "video_id": "h5W4XNHR8BU",
  "lang": "ko",
  "cached": false,
  "verdict": {
    "ai_generated": {
      "label": "real",
      "confidence": 0.05
    },
    "misinformation": {
      "applicable": true,
      "label": "true",
      "confidence": 0.05,
      "claims": [
        {
          "text": "TFCC는 손목 안쪽에서 충격을 흡수하는 구조물이다.",
          "llm_judgement": "true",
          "factcheck_matches": [],
          "supporting_sources": ["https://www.ncbi.nlm.nih.gov/..."]
        }
      ]
    },
    "advertisement": {
      "applicable": false
    },
    "community_signal": {
      "ok_count": 10,
      "suspicious_count": 3,
      "threshold_reached": "none"
    },
    "display_message": "특별한 위험 신호가 없어요",
    "category": "health"
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

### 응답 (200) — 광고·사기 의심 영상

```json
{
  "job_id": "7b2e4f9a",
  "video_id": "XXXXXXXXXXX",
  "lang": "ko",
  "cached": false,
  "verdict": {
    "ai_generated": {
      "label": "likely_real",
      "confidence": 0.15
    },
    "misinformation": {
      "applicable": false
    },
    "advertisement": {
      "applicable": true,
      "label": "likely_false_ad",
      "confidence": 0.82,
      "reason": "검증 불가 효능 주장 2건 및 근거 없는 수치 인용 확인"
    },
    "community_signal": {
      "ok_count": 0,
      "suspicious_count": 2,
      "threshold_reached": "none"
    },
    "display_message": "허위·과대 광고가 의심돼요"
  },
  "stages": {
    "extract":   { "ok": true, "elapsed_ms": 3100 },
    "classify":  { "ok": true, "elapsed_ms": 1400, "informational": "false" },
    "info":      { "ok": true, "elapsed_ms": 0 },
    "image":     { "ok": true, "elapsed_ms": 9200 },
    "factcheck": { "ok": true, "elapsed_ms": 0 },
    "ad_verify": { "ok": true, "elapsed_ms": 6800 }
  },
  "elapsed_ms_total": 20500
}
```

#### verdict 필드 상세

**ai_generated**

| 필드 | 타입 | 설명 |
|------|------|------|
| `label` | string | `real` / `likely_real` / `uncertain` / `likely_ai` / `ai` |
| `confidence` | float | 0.0 ~ 1.0. AI에 가까울수록 1.0 |

**misinformation**

| 필드 | 타입 | 설명 |
|------|------|------|
| `applicable` | bool | 정보성 영상인지 여부. `false`이면 나머지 필드 없음 |
| `label` | string | `true` / `likely_true` / `uncertain` / `likely_false` / `false` |
| `confidence` | float | 허위·과장 정보 위험도. `false`/`likely_false`이고 근거 명확할수록 1.0, `true`/`likely_true`이고 근거 충분할수록 0.0 |
| `claims` | array | 주장별 판정 목록 |
| `claims[].text` | string | 영상이 실제로 주장하는 사실 명제 |
| `claims[].llm_judgement` | string | Gemini 1차 판정. `true` / `false` / `uncertain` |
| `claims[].factcheck_matches` | array | Google Fact Check API 매칭 결과 목록. 없으면 `[]` |
| `claims[].supporting_sources` | array | Gemini Google Search 그라운딩 URL (최대 3개). 없으면 `[]` |

**advertisement**

| 필드 | 타입 | 설명 |
|------|------|------|
| `applicable` | bool | 광고·홍보성 영상 여부. `false`이면 나머지 필드 없음 |
| `label` | string \| null | `normal_ad` / `likely_false_ad` / `likely_scam`. ad_verify 미실행 시 classify 결과 그대로 사용 |
| `confidence` | float | 의심·위험할수록 1.0, 안전할수록 0.0. ad_verify 미실행 시 응답에서 생략 |
| `reason` | string | 판정 핵심 근거 (자막 인용 포함). ad_verify 미실행 시 응답에서 생략 |

**community_signal**

| 필드 | 타입 | 설명 |
|------|------|------|
| `ok_count` | int | "괜찮아요" 투표 수 |
| `suspicious_count` | int | "이상해요" 투표 수 |
| `threshold_reached` | string | `none` / `low` (이상해요 ≥5) / `high` (이상해요 ≥20) |

**category**

classify 단계에서 분류된 콘텐츠 카테고리. 정보성 영상이 아닌 경우 응답에서 생략.

| 값 | 설명 |
|----|------|
| `health` | 건강·의료·약품 정보 |
| `finance` | 금융·투자·주식 정보. `misinformation.label`이 `true`/`likely_true`이더라도 `uncertain`으로 하향 처리 |
| `politics` | 정치·사회 이슈 |
| `news` | 시사·보도 |
| `science` | 과학·기술 정보 |
| `entertainment` | 연예·유명인 관련 |
| `other` | 기타 |

**display_message** (고령층 표시용 한 줄 메시지, `lang`에 따라 언어 선택)

| 우선순위 | 조건 | `lang=ko` | `lang=en` |
|---------|------|-----------|-----------|
| 1 | 사기 의심 | `사기가 의심되는 콘텐츠예요` | `This content appears to be a scam` |
| 2 | 허위·과대 광고 의심 | `허위·과대 광고가 의심돼요` | `This content may contain false or exaggerated advertising` |
| 3 | AI 생성 + 허위정보 | `기계가 만든 영상이고, 사실과 다를 수 있어요` | `This appears to be AI-generated and may contain false information` |
| 4 | AI 생성만 | `기계가 만든 영상이에요` | `This appears to be AI-generated content` |
| 5 | 허위정보만 | `사실과 다를 수 있어요` | `This content may contain false information` |
| 6 | 주식·투자 콘텐츠 (`category=finance`, 위험 신호 없음) | `주식·투자 관련 콘텐츠예요. 투자 전 반드시 직접 확인하세요` | `This is stock/investment content. Always verify before making any investment decisions` |
| 7 | 이상 없음 | `특별한 위험 신호가 없어요` | `No significant warning signs detected` |
| + | `community_signal = high` | 위 메시지 + ` · 많은 분들이 의심하고 있어요` | 위 메시지 + ` · Many users have flagged this as suspicious` |
| — | **분석 불완전** | `분석을 완료하지 못했어요. 잠시 후 다시 시도해주세요` | `Analysis could not be completed. Please try again later.` |

> **분석 불완전** 조건: `stages.classify.ok = false` 또는 `stages.image.ok = false`.  
> 이 경우 `ai_generated.label`과 `misinformation.label`은 신뢰할 수 없으며, 재시도 시 재분석됩니다(캐시 저장 안 함).

#### stages 필드

| 필드 | 타입 | 설명 |
|------|------|------|
| `ok` | bool | 성공 여부 |
| `elapsed_ms` | int | 소요 시간 (ms) |
| `informational` | string | classify 단계에만 존재. `"true"` / `"false"` |
| `error` | string | 실패 시 오류 메시지 |

> `info` 스테이지는 항상 포함됩니다. 정보성 영상이 아닌 경우(`informational: "false"`) `ok: true, elapsed_ms: 0`으로 표시됩니다.  
> `ad_verify` 스테이지는 classify에서 `likely_false_ad` 또는 `likely_scam`으로 분류되고, 자막과 프레임이 모두 존재할 때만 stages에 포함됩니다.

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
| `video_too_long` | `MAX_DURATION_SEC`(기본 180초) 초과 |
| `extractor_error` | YouTube 봇 차단(429), 네트워크 오류 등 기타 추출 실패 |

> 분석 결과는 14일간 캐시됩니다. `lang`이 다르면 별도 캐시로 관리됩니다.

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
| `already_voted` | bool | 동일 타입으로 이미 투표한 경우 `true` (카운트 변화 없음) |
| `changed` | bool | 이전 투표에서 타입을 변경한 경우 `true` |

### 응답 (400)

```json
{ "error": "invalid_url" }
{ "error": "missing_client_id", "detail": "64자 hex 문자열이어야 합니다" }
{ "error": "invalid_vote_type", "detail": "ok 또는 suspicious 만 허용됩니다" }
```

### 응답 (429) — Rate Limit 초과

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

---

## 4. GET /api/vote — 투표 결과 조회

특정 URL의 투표 집계 결과를 조회합니다.

### 요청

```
GET /api/vote?url={URL인코딩된_유튜브URL}
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
| `last_voted_at` | string \| null | 마지막 투표 시각 (ISO 8601). 투표 없으면 `null` |

---

## 5. GET /api/health — 상태 확인

서비스 컴포넌트 상태를 확인합니다.

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

> `extractor`, `analyzer`, `factcheck`는 모두 동일한 `ss-analyzer` 컨테이너의 `/health` 엔드포인트를 확인합니다.

---

## 6. POST /api/extract — 자막 + 프레임 추출 (디버그용)

YouTube URL에서 자막과 프레임을 추출합니다.

### 요청

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `url` | string | ✅ | 유튜브 쇼츠 URL |
| `frame_count` | int | 선택 | 추출할 프레임 수 (기본값: `FRAME_COUNT` 환경변수) |
| `subtitle_lang` | string | 선택 | 선호 자막 언어 (기본값: `"ko"`). 없으면 자동 fallback |

### 응답 (200)

```json
{
  "video_id": "h5W4XNHR8BU",
  "title": "손목이 아프다면? TFCC 손상 의심",
  "description": "TFCC 손목 통증 원인과 예방법을 알아보세요",
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
  },
  "elapsed_ms": {
    "subtitle": 3200,
    "frames": 1100,
    "total": 4300
  }
}
```

| `selection_reason` 값 | 의미 |
|----------------------|------|
| `requested_lang_manual` | 요청한 언어의 수동 자막 사용 |
| `requested_lang_auto` | 요청한 언어의 자동 자막 사용 |
| `fallback_to_{lang}_manual` | 요청 언어 없어 fallback 언어 수동 자막 사용 |
| `fallback_to_{lang}_auto` | 요청 언어 없어 fallback 언어 자동 자막 사용 |
| `no_subtitle_available` | 자막 없음 |

---

## 7. POST /api/classify — 자막 분류 (디버그용)

자막 텍스트가 정보성인지, 광고·사기성인지 분류합니다.

### 요청

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `subtitle_text` | string | ✅ | 자막 텍스트 |
| `title` | string | 선택 | 영상 제목 (없으면 `"(없음)"` 처리) |
| `description` | string | 선택 | 영상 설명 (없으면 `"(없음)"` 처리) |
| `lang` | string | 선택 | 응답 언어 (`"ko"` / `"en"`, 기본값: `"ko"`) |

### 응답 (200)

```json
{
  "informational": true,
  "category": "health",
  "key_topic": "손목 삼각섬유연골복합체(TFCC) 손상의 증상 및 주의사항",
  "key_claim": "이 영상은 손목 회전 시 통증이 있다면 TFCC 손상일 수 있으며, 조기 진단과 치료가 중요하다고 주장한다.",
  "advertisement": false,
  "ad_label": "none"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `informational` | bool | 정보성 영상 여부. `false`이면 `/api/analyze` 파이프라인에서 info/factcheck 생략 |
| `category` | string | 정보 유형 분류 |
| `key_topic` | string | 영상이 다루는 핵심 주제 (한 문장) |
| `key_claim` | string | 영상이 주장하는 핵심 내용 1~2문장. 정보성 콘텐츠가 아니면 빈 문자열 |
| `advertisement` | bool | 광고·홍보성 영상 여부 |
| `ad_label` | string | 광고 유형 |

| `category` 값 | 설명 |
|--------------|------|
| `health` | 건강/의학 정보 |
| `finance` | 금융/투자 정보 |
| `news` | 시사/뉴스 |
| `science` | 과학/기술 정보 |
| `other` | 기타 정보성 |

| `ad_label` 값 | 설명 |
|--------------|------|
| `none` | 광고 아님 |
| `normal_ad` | 일반 광고 |
| `likely_false_ad` | 허위·과대 광고 의심 |
| `likely_scam` | 사기 의심 |

> `advertisement: true`이면 `ad_label`이 `none` 이외의 값입니다.  
> `likely_false_ad` / `likely_scam`인 경우 `/api/analyze` 파이프라인에서 `/ad` 검증이 추가로 실행됩니다.

---

## 8. POST /api/info — 허위정보 분석 (디버그용)

classify에서 추출한 핵심 주장으로 사실 여부를 예비 판단합니다.

### 요청

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `key_claim` | string | ✅ | classify에서 반환한 `key_claim` (영상 핵심 주장 1~2문장) |
| `category` | string | 선택 | classify에서 반환한 카테고리 (기본값: `"other"`) |
| `title` | string | 선택 | 영상 제목 (없으면 `"(없음)"` 처리) |
| `description` | string | 선택 | 영상 설명 (없으면 `"(없음)"` 처리) |
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
      "preliminary_judgement": "true",
      "supporting_sources": ["https://example.com/article"]
    }
  ]
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `overall_judgement` | string | 전체 영상 종합 판정. `true` / `likely_true` / `uncertain` / `likely_false` / `false` |
| `confidence` | float | 허위·과장 정보 위험도. `false`/`likely_false`이고 근거 명확할수록 1.0, `true`/`likely_true`이고 근거 충분할수록 0.0, 증거 불충분하면 0.5 내외 |
| `claims` | array | 주장별 판정 목록 |
| `claims[].text` | string | 영상이 실제로 주장하는 사실 명제 |
| `claims[].normalized` | string | 팩트체크 API 검색에 적합하게 정규화한 명제 |
| `claims[].preliminary_judgement` | string | Gemini 1차 판정. `true` / `false` / `uncertain` |
| `claims[].supporting_sources` | array | Gemini Google Search 그라운딩 URL (최대 3개). 없으면 `[]` |

---

## 9. POST /api/image — AI 이미지 판별 (디버그용)

프레임 이미지로 AI 생성 여부를 2단계로 판별합니다.

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
    { "index": 0, "label": "real", "confidence": 0.99 },
    { "index": 1, "label": "real", "confidence": 0.98 },
    { "index": 2, "label": "real", "confidence": 0.99 }
  ],
  "revision_notes": "없음"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `label` | string | 종합 판정 레이블 |
| `confidence` | float | AI에 가까울수록 1.0, 실제 영상에 가까울수록 0.0 |
| `per_frame` | array | 프레임별 판정. 각 항목에 `index`, `label`, `confidence` 포함 |
| `revision_notes` | string | Round 2에서 Round 1 관찰을 수정하거나 보강한 내용. 없으면 `"없음"` / `"none"` |

| `label` 값 | 설명 |
|-----------|------|
| `real` | 실제 영상 |
| `likely_real` | 실제 영상으로 추정 |
| `uncertain` | 판단 불가 |
| `likely_ai` | AI 생성 의심 |
| `ai` | AI 생성 확실 |

---

## 10. POST /api/ad — 광고·사기 검증 (디버그용)

자막 텍스트와 프레임 이미지를 함께 분석하는 멀티모달 광고·사기 검증입니다.  
`/api/analyze` 파이프라인에서는 `classify.ad_label`이 `likely_false_ad` 또는 `likely_scam`일 때만 자동 호출됩니다.

### 요청

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `frames_base64` | string[] | ✅ | JPEG 프레임을 base64 인코딩한 배열 |
| `subtitle_text` | string | ✅ | 자막 텍스트 |
| `initial_label` | string | ✅ | classify에서 반환한 `ad_label` 값 |
| `lang` | string | 선택 | 응답 언어 (`"ko"` / `"en"`, 기본값: `"ko"`) |

```json
{
  "frames_base64": ["<base64>", "<base64>", "<base64>"],
  "subtitle_text": "...",
  "initial_label": "likely_false_ad",
  "lang": "ko"
}
```

### 응답 (200)

```json
{
  "label": "likely_false_ad",
  "confidence": 0.85,
  "reason": "검증 불가 효능 주장 2건 및 근거 없는 수치 인용 확인"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `label` | string | `normal_ad` / `likely_false_ad` / `likely_scam` |
| `confidence` | float | 의심·위험할수록 1.0, 안전할수록 0.0 |
| `reason` | string | 판정 핵심 근거 (자막 직접 인용 포함) |

> 결정적 근거가 2개 미만이면 `normal_ad`로 하향 판정합니다 (미확정 원칙).

---

## 11. POST /api/factcheck — 팩트체크 (디버그용)

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
      "publisher_site": "reuters.com",
      "url": "https://reuters.com/article/...",
      "rating": "False",
      "rating_normalized": "false",
      "review_date": "2024-03-15"
    }
  ],
  "language_used": "en",
  "fallback_applied": true
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `matches` | array | 팩트체크 매칭 결과. 없으면 `[]` |
| `matches[].publisher` | string | 팩트체크 기관명 |
| `matches[].publisher_site` | string | 기관 도메인 |
| `matches[].url` | string | 팩트체크 기사 URL |
| `matches[].rating` | string | 기관이 부여한 원문 판정 레이블 |
| `matches[].rating_normalized` | string | 정규화된 판정 값 |
| `matches[].review_date` | string | 팩트체크 날짜 (ISO 8601) |
| `language_used` | string | 실제 검색에 사용된 언어 |
| `fallback_applied` | bool | 요청 언어로 결과 없어 fallback 언어로 재시도했는지 여부 |

| `rating_normalized` 값 | 설명 |
|------------------------|------|
| `true` | 사실 |
| `mostly_true` | 대체로 사실 |
| `mixed` | 부분 사실 |
| `mostly_false` | 대체로 거짓 |
| `false` | 거짓 |
| `unknown` | 분류 불가 |

팩트체크 기관이 다루지 않는 주제(의학 해부학 등)는 `matches: []`로 반환됩니다.  
fallback 순서: `ko` → `en`, `en` → `ko`

---

## 12. 공통 오류 코드

| HTTP | `error` 값 | 발생 상황 |
|------|-----------|---------|
| 400 | `invalid_url` | YouTube URL이 아니거나 형식 오류 |
| 400 | `missing_client_id` | client_id 누락 또는 64자 hex 형식 불일치 |
| 400 | `invalid_vote_type` | vote_type이 `ok` / `suspicious` 이외의 값 |
| 400 | `missing_url_parameter` | GET 요청에 url 파라미터 없음 |
| 429 | `rate_limit_exceeded` | 같은 client_id로 1분에 10건 초과 |
| 502 | `video_unavailable` | 영상이 삭제/비공개/지역 제한 |
| 502 | `video_too_long` | `MAX_DURATION_SEC`(기본 180초) 초과 |
| 502 | `extractor_error` | YouTube 봇 차단(429), 네트워크 오류 등 기타 추출 실패 |
| 500 | `internal_error` | 서버 내부 오류 |

> classify / info / image / ad_verify 단계 실패는 502를 반환하지 않고, `/api/analyze` 응답의 `stages[xxx].ok = false`로 표현됩니다.
