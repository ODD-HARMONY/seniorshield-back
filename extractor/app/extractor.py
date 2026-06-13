import asyncio
import base64
import os
import shutil
import subprocess
import time
import uuid
from pathlib import Path
from typing import Optional

import yt_dlp
from fastapi import HTTPException

from app.schemas import (
    ElapsedMs,
    ExtractRequest,
    ExtractResponse,
    FramesResult,
    SubtitleResult,
)
from app.subtitle_parser import parse_subtitle

WORK_DIR         = os.environ.get("WORK_DIR", "/data/work")
MAX_DURATION_SEC = int(os.environ.get("MAX_DURATION_SEC", "120"))
YT_DLP_TIMEOUT   = int(os.environ.get("YT_DLP_TIMEOUT_SEC", "20"))
FFMPEG_TIMEOUT   = int(os.environ.get("FFMPEG_TIMEOUT_SEC", "15"))
COOKIE_FILE      = os.environ.get("COOKIE_FILE")  # optional: mount browser cookies to bypass 429


async def extract(req: ExtractRequest) -> ExtractResponse:
    job_id  = req.job_id or uuid.uuid4().hex[:8]
    job_dir = Path(WORK_DIR) / job_id
    job_dir.mkdir(parents=True, exist_ok=True)

    total_start = time.monotonic()
    subtitle_ms: Optional[int] = None
    frames_ms:   Optional[int] = None

    subtitle_result: Optional[SubtitleResult] = None
    frames_result:   Optional[FramesResult]   = None
    video_id:        Optional[str]            = None
    duration_sec:    Optional[float]          = None
    title:           Optional[str]            = None

    try:
        # ── 1. Subtitle extraction ────────────────────────────────────────
        if req.extract_subtitle:
            sub_start = time.monotonic()
            ydl_opts_sub = {
                "writesubtitles":    True,
                "writeautomaticsub": True,
                "subtitleslangs":    ["ko", "en"],
                "subtitlesformat":   "vtt",
                "skip_download":     True,
                # yt-dlp still runs format selection even with skip_download;
                # "best" prevents "Requested format is not available" on Shorts
                "format":            "best",
                "outtmpl":           str(job_dir / "%(id)s.%(ext)s"),
                "socket_timeout":    YT_DLP_TIMEOUT,
                # ignore individual subtitle download failures (e.g. 429 on en)
                # so ko subtitle can still be used if en fails
                "ignoreerrors":      "only_download",
                "quiet":             True,
            }
            if COOKIE_FILE:
                ydl_opts_sub["cookiefile"] = COOKIE_FILE
            info = await asyncio.to_thread(_ydl_extract, ydl_opts_sub, req.url)
            _guard(info)

            video_id     = info.get("id")
            duration_sec = info.get("duration")
            title        = info.get("title")
            subtitle_result = parse_subtitle(job_dir, info, video_id)
            subtitle_ms = int((time.monotonic() - sub_start) * 1000)

        # ── 2. Frame extraction ───────────────────────────────────────────
        if req.extract_frames and req.frame_count > 0:
            frames_start = time.monotonic()
            ydl_opts_vid = {
                # worst quality is enough for frame extraction;
                # fallback to worst (any ext) if no mp4 is available
                "format":         "worst[ext=mp4]/worst[ext=webm]/worst",
                "outtmpl":        str(job_dir / "%(id)s.%(ext)s"),
                "socket_timeout": YT_DLP_TIMEOUT,
                "quiet":          True,
            }
            if COOKIE_FILE:
                ydl_opts_vid["cookiefile"] = COOKIE_FILE
            info_v = await asyncio.to_thread(_ydl_extract, ydl_opts_vid, req.url)
            _guard(info_v)

            if video_id   is None: video_id     = info_v.get("id")
            if duration_sec is None: duration_sec = info_v.get("duration")
            if title      is None: title        = info_v.get("title")

            video_path = _find_video(job_dir, video_id)
            if video_path and duration_sec:
                frames_result = _extract_frames(video_path, duration_sec, req.frame_count, job_dir)
            else:
                frames_result = FramesResult(count=0, frames_base64=[], timestamps_sec=[])
            frames_ms = int((time.monotonic() - frames_start) * 1000)

        total_ms = int((time.monotonic() - total_start) * 1000)
        return ExtractResponse(
            job_id=job_id,
            video_id=video_id or "",
            duration_sec=duration_sec,
            title=title,
            subtitle=subtitle_result,
            frames=frames_result,
            elapsed_ms=ElapsedMs(subtitle=subtitle_ms, frames=frames_ms, total=total_ms),
        )

    finally:
        shutil.rmtree(job_dir, ignore_errors=True)


def _ydl_extract(opts: dict, url: str) -> dict:
    with yt_dlp.YoutubeDL(opts) as ydl:
        return ydl.extract_info(url, download=True)


def _guard(info: dict) -> None:
    if info.get("is_live"):
        raise HTTPException(status_code=400, detail="live_not_supported")
    duration = info.get("duration")
    if duration and duration > MAX_DURATION_SEC:
        raise HTTPException(status_code=400, detail="video_too_long")


def _find_video(job_dir: Path, video_id: Optional[str]) -> Optional[Path]:
    if not video_id:
        return None
    for ext in (".mp4", ".webm", ".mkv"):
        p = job_dir / f"{video_id}{ext}"
        if p.exists():
            return p
    # fallback glob
    for ext in (".mp4", ".webm", ".mkv"):
        matches = list(job_dir.glob(f"*{ext}"))
        if matches:
            return matches[0]
    return None


def _extract_frames(
    video_path: Path, duration: float, count: int, job_dir: Path
) -> FramesResult:
    interval = duration / (count + 1)
    timestamps = [round(interval * (i + 1), 2) for i in range(count)]
    frames_b64 = []
    actual_ts  = []

    for i, ts in enumerate(timestamps):
        out_path = job_dir / f"frame_{i:03d}.jpg"
        try:
            subprocess.run(
                [
                    "ffmpeg", "-ss", str(ts),
                    "-i", str(video_path),
                    "-frames:v", "1",
                    "-vf", "scale=640:-1",
                    str(out_path), "-y",
                ],
                check=True,
                capture_output=True,
                timeout=FFMPEG_TIMEOUT,
            )
            if out_path.exists():
                frames_b64.append(base64.b64encode(out_path.read_bytes()).decode())
                actual_ts.append(ts)
        except (subprocess.TimeoutExpired, subprocess.CalledProcessError):
            pass

    return FramesResult(count=len(frames_b64), frames_base64=frames_b64, timestamps_sec=actual_ts)
