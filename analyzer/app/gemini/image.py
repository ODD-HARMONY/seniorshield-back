import os
from pathlib import Path

from .client import call_multimodal

PROMPTS_ROOT    = Path(__file__).resolve().parents[1] / "prompts"
SUPPORTED_LANGS = {"ko", "en"}
DEFAULT_LANG    = "ko"


def _prompt_path(lang: str, name: str) -> Path:
    if lang not in SUPPORTED_LANGS:
        lang = DEFAULT_LANG
    return PROMPTS_ROOT / lang / name


def detect_aigen(frames_b64: list, lang: str = DEFAULT_LANG) -> dict:
    prompt = _prompt_path(lang, "image_aigen.txt").read_text(encoding="utf-8")
    return call_multimodal(os.environ["MODEL_IMAGE"], prompt, frames_b64)
