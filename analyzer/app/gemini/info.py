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


def analyze_info(key_claim: str, category: str, lang: str = DEFAULT_LANG,
                 title: str = "", description: str = "") -> dict:
    tmpl = _prompt_path(lang, "info_extract.txt").read_text(encoding="utf-8")
    prompt = (
        tmpl
        .replace("{{KEY_CLAIM}}", key_claim)
        .replace("{{CATEGORY}}", category)
        .replace("{{TITLE}}", title or "(없음)")
        .replace("{{DESCRIPTION}}", description or "(없음)")
    )
    return call_with_grounding(os.environ["MODEL_INFO"], prompt)
