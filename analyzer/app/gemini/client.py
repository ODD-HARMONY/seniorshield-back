import base64
import json
import os
import time

from google import genai
from google.genai import errors, types

_client = genai.Client(api_key=os.environ.get("GEMINI_API_KEY", ""))


def _retry_delay_sec(e: errors.ClientError) -> int:
    """429 응답 본문에서 retryDelay(초) 추출. 없으면 60 반환."""
    try:
        inner = (e.details or {}).get("error", {}).get("details", [])
        for item in inner:
            if "retryDelay" in item:
                return int(float(item["retryDelay"].rstrip("s"))) + 1
    except Exception:
        pass
    return 60


def _with_retry(fn):
    """429 발생 시 retryDelay만큼 대기 후 1회 재시도."""
    try:
        return fn()
    except errors.ClientError as e:
        if e.code != 429:
            raise
        delay = _retry_delay_sec(e)
        print(f"[gemini] 429 rate limit — {delay}초 대기 후 재시도", flush=True)
        time.sleep(delay)
        return fn()


def call_text(model: str, prompt: str, json_mode: bool = True):
    config = types.GenerateContentConfig(
        response_mime_type="application/json" if json_mode else "text/plain",
    )
    def _call():
        resp = _client.models.generate_content(
            model=model, contents=prompt, config=config,
        )
        return json.loads(resp.text) if json_mode else resp.text
    return _with_retry(_call)


def call_with_grounding(model: str, prompt: str) -> dict:
    """Google 검색 그라운딩 + JSON 응답. 미지원 시 일반 호출로 fallback."""
    try:
        config = types.GenerateContentConfig(
            tools=[types.Tool(google_search=types.GoogleSearch())],
            response_mime_type="application/json",
        )
        def _call():
            resp = _client.models.generate_content(
                model=model, contents=prompt, config=config,
            )
            return json.loads(resp.text)
        return _with_retry(_call)
    except Exception:
        # §15: 그라운딩 미지원 모델·지역이면 일반 호출로 fallback
        return call_text(model, prompt, json_mode=True)


def call_multimodal(model: str, prompt: str, images_b64: list) -> dict:
    parts = [types.Part.from_text(text=prompt)]
    for b64 in images_b64:
        parts.append(types.Part.from_bytes(
            data=base64.b64decode(b64), mime_type="image/jpeg",
        ))
    config = types.GenerateContentConfig(
        response_mime_type="application/json",
    )
    def _call():
        resp = _client.models.generate_content(
            model=model, contents=parts, config=config,
        )
        return json.loads(resp.text)
    return _with_retry(_call)
