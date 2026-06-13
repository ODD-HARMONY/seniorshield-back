import os
from pathlib import Path

from .client import call_multimodal

PROMPT_PATH = Path(__file__).resolve().parents[1] / "prompts" / "image_aigen.txt"


def detect_aigen(frames_b64: list) -> dict:
    prompt = PROMPT_PATH.read_text(encoding="utf-8")
    return call_multimodal(os.environ["MODEL_IMAGE"], prompt, frames_b64)
