import logging
import os
import time
from pathlib import Path

from .client import call_multimodal, call_multimodal_text

log = logging.getLogger(__name__)

PROMPTS_ROOT    = Path(__file__).resolve().parents[1] / "prompts"
SUPPORTED_LANGS = {"ko", "en"}
DEFAULT_LANG    = "ko"


def _prompt_path(lang: str, name: str) -> Path:
    if lang not in SUPPORTED_LANGS:
        lang = DEFAULT_LANG
    return PROMPTS_ROOT / lang / name


def detect_aigen(frames_b64: list, lang: str = DEFAULT_LANG) -> dict:
    """2단계 자기반복 이미지 판별.

    Round 1: 자연어 관찰 (판정 없음)
    Round 2: 재검토 + JSON 최종 판정 (revision_notes 포함)
    """
    model = os.environ["MODEL_IMAGE"]

    # Round 1 — 자연어 관찰
    prompt1 = _prompt_path(lang, "image_aigen_round1.txt").read_text(encoding="utf-8")
    t1 = time.monotonic()
    round1_text = call_multimodal_text(model=model, prompt=prompt1, images_b64=frames_b64)
    ms1 = int((time.monotonic() - t1) * 1000)

    # Round 2 — 재검토 + JSON
    prompt2_tmpl = _prompt_path(lang, "image_aigen_round2.txt").read_text(encoding="utf-8")
    prompt2 = prompt2_tmpl.replace("{{ROUND1_OBSERVATION}}", round1_text)
    t2 = time.monotonic()
    result = call_multimodal(model=model, prompt=prompt2, images_b64=frames_b64)
    ms2 = int((time.monotonic() - t2) * 1000)

    log.info("image round1=%dms round2=%dms total=%dms", ms1, ms2, ms1 + ms2)

    result["_internal"] = {
        "round1_observation": round1_text,
        "round1_ms": ms1,
        "round2_ms": ms2,
    }

    return result
