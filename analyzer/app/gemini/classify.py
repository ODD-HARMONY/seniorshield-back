import os
from pathlib import Path

from .client import call_text

PROMPTS_ROOT   = Path(__file__).resolve().parents[1] / "prompts"
SUPPORTED_LANGS = {"ko", "en"}
DEFAULT_LANG    = "ko"


def _prompt_path(lang: str, name: str) -> Path:
    if lang not in SUPPORTED_LANGS:
        lang = DEFAULT_LANG
    return PROMPTS_ROOT / lang / name


def classify(subtitle_text: str, lang: str = DEFAULT_LANG,
             title: str = "", description: str = "") -> dict:
    tmpl = _prompt_path(lang, "subtitle_classify.txt").read_text(encoding="utf-8")
    prompt = (tmpl
              .replace("{{SUBTITLE_TEXT}}", subtitle_text)
              .replace("{{TITLE}}", title or "(없음)")
              .replace("{{DESCRIPTION}}", description or "(없음)"))
    return call_text(os.environ["MODEL_CLASSIFY"], prompt, json_mode=True)
