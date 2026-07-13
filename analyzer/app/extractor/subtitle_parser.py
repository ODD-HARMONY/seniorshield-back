import re
from pathlib import Path
from typing import Optional, Tuple

import webvtt

from app.extractor.schemas import SubtitleResult


def parse_subtitle(job_dir: Path, info: dict, video_id: Optional[str]) -> SubtitleResult:
    if not video_id:
        return SubtitleResult(available=False, reason="no_video_id")

    vtt_path, source, language = _find_vtt(job_dir, info, video_id)

    if vtt_path is None:
        return SubtitleResult(available=False, reason="no_captions")

    try:
        text, segment_count = _parse_vtt(vtt_path)
    except Exception:
        return SubtitleResult(available=False, reason="parse_error")

    if segment_count == 0 or not text:
        return SubtitleResult(available=False, reason="empty_subtitle")

    return SubtitleResult(
        available=True,
        source=source,
        language=language,
        text=text,
        char_count=len(text),
        segment_count=segment_count,
    )


def _find_vtt(
    job_dir: Path, info: dict, video_id: str
) -> Tuple[Optional[Path], Optional[str], Optional[str]]:
    subtitles     = info.get("subtitles", {})
    auto_captions = info.get("automatic_captions", {})

    for lang in ("ko", "en"):
        candidates = [job_dir / f"{video_id}.{lang}.vtt"] + list(
            job_dir.glob(f"{video_id}.{lang}*.vtt")
        )
        for p in candidates:
            if p.exists():
                if lang in subtitles and subtitles[lang]:
                    source = "manual"
                elif lang in auto_captions and auto_captions[lang]:
                    source = "auto"
                else:
                    source = "auto"
                return p, source, lang

    return None, None, None


def _parse_vtt(vtt_path: Path) -> Tuple[str, int]:
    captions = list(webvtt.read(str(vtt_path)))
    seen: set = set()
    lines = []
    for cap in captions:
        for raw_line in cap.text.split("\n"):
            text = re.sub(r"<[^>]+>", "", raw_line).strip()
            if text and text not in seen:
                seen.add(text)
                lines.append(text)
    return "\n".join(lines), len(captions)
