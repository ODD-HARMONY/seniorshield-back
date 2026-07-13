import logging
import os
import time
from pathlib import Path

from .client import call_multimodal, call_multimodal_text

log = logging.getLogger(__name__)

PROMPTS_ROOT    = Path(__file__).resolve().parents[1] / "prompts"
SUPPORTED_LANGS = {"ko", "en"}
DEFAULT_LANG    = "ko"

# T3: 3-라벨 체계 플래그 (off 시 5-라벨 동작 유지)
LABEL_3CLASS_ENABLED = os.environ.get("LABEL_3CLASS_ENABLED", "true").lower() == "true"
_AI_CONF_THRESHOLD   = float(os.environ.get("AI_CONF_THRESHOLD",   "0.8"))
_REAL_CONF_THRESHOLD = float(os.environ.get("REAL_CONF_THRESHOLD",  "0.2"))
_VALID_3CLASS        = {"ai", "uncertain", "real"}


def _normalize_label(label: str, confidence: float) -> str:
    """모델이 구 5-라벨을 반환할 경우 3-라벨로 매핑. 안전 기본값: uncertain."""
    if label in _VALID_3CLASS:
        return label
    if label == "likely_ai":
        return "ai" if confidence >= _AI_CONF_THRESHOLD else "uncertain"
    if label == "likely_real":
        return "real" if confidence <= _REAL_CONF_THRESHOLD else "uncertain"
    return "uncertain"


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

    # T3: 3-라벨 정규화 (플래그 on 시)
    if LABEL_3CLASS_ENABLED:
        top_conf = result.get("confidence", 0.5)
        result["label"] = _normalize_label(result.get("label", "uncertain"), top_conf)
        for pf in result.get("per_frame") or []:
            pf["label"] = _normalize_label(pf.get("label", "uncertain"), pf.get("confidence", 0.5))

    result["_internal"] = {
        "round1_observation": round1_text,
        "round1_ms": ms1,
        "round2_ms": ms2,
    }

    return result
