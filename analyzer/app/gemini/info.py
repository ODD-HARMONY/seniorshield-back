import os
from pathlib import Path

from .client import call_with_grounding

PROMPTS_ROOT    = Path(__file__).resolve().parents[1] / "prompts"
SUPPORTED_LANGS = {"ko", "en"}
DEFAULT_LANG    = "ko"


def _prompt_path(lang: str, name: str) -> Path:
    if lang not in SUPPORTED_LANGS:
        lang = DEFAULT_LANG
    return PROMPTS_ROOT / lang / name


def analyze_info(subtitle_text: str, category: str, lang: str = DEFAULT_LANG) -> dict:
    tmpl = _prompt_path(lang, "info_extract.txt").read_text(encoding="utf-8")
    prompt = (
        tmpl
        .replace("{{SUBTITLE_TEXT}}", subtitle_text)
        .replace("{{CATEGORY}}", category)
    )
    return call_with_grounding(os.environ["MODEL_INFO"], prompt)
