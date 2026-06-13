import base64
import json
import os

from google import genai
from google.genai import types

_client = genai.Client(api_key=os.environ.get("GEMINI_API_KEY", ""))


def call_text(model: str, prompt: str, json_mode: bool = True):
    config = types.GenerateContentConfig(
        response_mime_type="application/json" if json_mode else "text/plain",
    )
    resp = _client.models.generate_content(
        model=model, contents=prompt, config=config,
    )
    return json.loads(resp.text) if json_mode else resp.text


def call_with_grounding(model: str, prompt: str) -> dict:
    """Google 검색 그라운딩 + JSON 응답. 미지원 시 일반 호출로 fallback."""
    try:
        config = types.GenerateContentConfig(
            tools=[types.Tool(google_search=types.GoogleSearch())],
            response_mime_type="application/json",
        )
        resp = _client.models.generate_content(
            model=model, contents=prompt, config=config,
        )
        return json.loads(resp.text)
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
    resp = _client.models.generate_content(
        model=model, contents=parts, config=config,
    )
    return json.loads(resp.text)
