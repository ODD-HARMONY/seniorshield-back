import os
from pathlib import Path

from .client import call_with_grounding

PROMPT_PATH = Path(__file__).resolve().parents[1] / "prompts" / "info_extract.txt"


def analyze_info(subtitle_text: str, category: str) -> dict:
    tmpl = PROMPT_PATH.read_text(encoding="utf-8")
    prompt = (
        tmpl
        .replace("{{SUBTITLE_TEXT}}", subtitle_text)
        .replace("{{CATEGORY}}", category)
    )
    return call_with_grounding(os.environ["MODEL_INFO"], prompt)
