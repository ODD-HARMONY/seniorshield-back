import logging
import os
import time
from pathlib import Path

from .client import call_with_grounding

log = logging.getLogger(__name__)

PROMPTS_ROOT    = Path(__file__).resolve().parents[1] / "prompts"
SUPPORTED_LANGS = {"ko", "en"}
DEFAULT_LANG    = "ko"

# R3 플래그 (환경변수, 기본 on)
_R3_TWO_AXIS   = os.environ.get("R3_TWO_AXIS_ENABLED",       "true").lower() != "false"
_R3_AI_SIGNAL  = os.environ.get("R3_AI_SCRIPT_SIGNAL_ENABLED", "true").lower() != "false"


def _prompt_path(lang: str, name: str) -> Path:
    if lang not in SUPPORTED_LANGS:
        lang = DEFAULT_LANG
    return PROMPTS_ROOT / lang / name


def _placeholder(lang: str, empty_ko: str = "(없음)", empty_en: str = "(none)") -> str:
    return empty_en if lang == "en" else empty_ko


def analyze_info(key_claim: str, category: str, lang: str = DEFAULT_LANG,
                 title: str = "", description: str = "",
                 subtitle_text: str = "", ai_script_likelihood: str = "low") -> dict:
    prompt_file = "info_extract_2axis.txt" if _R3_TWO_AXIS else "info_extract.txt"
    tmpl = _prompt_path(lang, prompt_file).read_text(encoding="utf-8")

    na = _placeholder(lang)
    sub = subtitle_text[:2000] if subtitle_text else na
    asl = ai_script_likelihood if _R3_AI_SIGNAL else "low"

    prompt = (
        tmpl
        .replace("{{KEY_CLAIM}}", key_claim or na)
        .replace("{{CATEGORY}}", category or "other")
        .replace("{{TITLE}}", title or na)
        .replace("{{DESCRIPTION}}", description or na)
        .replace("{{SUBTITLE_TEXT}}", sub)
        .replace("{{AI_SCRIPT_LIKELIHOOD}}", asl)
    )
    t0 = time.monotonic()
    result = call_with_grounding(os.environ["MODEL_INFO"], prompt)
    log.info("info elapsed=%dms", int((time.monotonic() - t0) * 1000))
    return result
