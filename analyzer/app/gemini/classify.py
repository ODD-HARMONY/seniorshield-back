import os
from pathlib import Path

from .client import call_text

PROMPT_PATH = Path(__file__).resolve().parents[1] / "prompts" / "subtitle_classify.txt"


def classify(subtitle_text: str) -> dict:
    tmpl = PROMPT_PATH.read_text(encoding="utf-8")
    prompt = tmpl.replace("{{SUBTITLE_TEXT}}", subtitle_text)
    return call_text(os.environ["MODEL_CLASSIFY"], prompt, json_mode=True)
