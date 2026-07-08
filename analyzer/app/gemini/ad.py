import logging
import os
import time
from pathlib import Path

from .client import call_multimodal

log = logging.getLogger(__name__)

PROMPTS_ROOT    = Path(__file__).resolve().parents[1] / "prompts"
SUPPORTED_LANGS = {"ko", "en"}
DEFAULT_LANG    = "ko"


def _prompt_path(lang: str, name: str) -> Path:
    if lang not in SUPPORTED_LANGS:
        lang = DEFAULT_LANG
    return PROMPTS_ROOT / lang / name


def verify_ad(frames_b64: list, subtitle_text: str, initial_label: str, lang: str = DEFAULT_LANG) -> dict:
    """단일 패스 광고·사기 검증 (멀티모달)."""
    model = os.environ["MODEL_IMAGE"]

    tmpl = _prompt_path(lang, "ad_verify.txt").read_text(encoding="utf-8")
    prompt = tmpl.replace("{{SUBTITLE_TEXT}}", subtitle_text)

    t0 = time.monotonic()
    result = call_multimodal(model=model, prompt=prompt, images_b64=frames_b64)
    elapsed_ms = int((time.monotonic() - t0) * 1000)

    log.info("ad_verify elapsed=%dms initial=%s final=%s",
             elapsed_ms, initial_label, result.get("label"))

    return result
