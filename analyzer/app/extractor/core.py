import asyncio
import base64
import logging
import os
import shutil
import subprocess
import time
import uuid
from pathlib import Path
from typing import Optional

import yt_dlp
from yt_dlp.utils import DownloadError
from fastapi import HTTPException

from app.extractor.schemas import (
    AvailableLangs,
    ElapsedMs,
    ExtractRequest,
    ExtractResponse,
    FramesResult,
    SubtitleResult,
)
from app.extractor.subtitle_parser import parse_subtitle

WORK_DIR         = os.environ.get("WORK_DIR", "/data/work")
MAX_DURATION_SEC = int(os.environ.get("MAX_DURATION_SEC", "120"))
YT_DLP_TIMEOUT   = int(os.environ.get("YT_DLP_TIMEOUT_SEC", "20"))
FFMPEG_TIMEOUT   = int(os.environ.get("FFMPEG_TIMEOUT_SEC", "15"))
COOKIE_FILE      = os.environ.get("COOKIE_FILE")
# T1: 720p 상한, env로 조정 가능. worst → bestvideo[height<=720]으로 변경해 업스케일 근본 원인 제거.
YT_FORMAT        = os.environ.get(
    "YT_FORMAT",
    "bestvideo[width<=720][ext=mp4]/bestvideo[width<=720]/best[width<=720]",
)

log = logging.getLogger(__name__)

_EXTRACTOR_ARGS = {"youtube": {"player_client": ["default"]}}


async def extract(req: ExtractRequest) -> ExtractResponse:
    job_id  = req.job_id or uuid.uuid4().hex[:8]
    job_dir = Path(WORK_DIR) / job_id
    job_dir.mkdir(parents=True, exist_ok=True)

    total_start = time.monotonic()

    subtitle_result:   Optional[SubtitleResult] = None
    frames_result:     Optional[FramesResult]   = None
    video_id:          Optional[str]            = None
    duration_sec:      Optional[float]          = None
    title:             Optional[str]            = None
    description:       Optional[str]            = None
    subtitle_ms:       Optional[int]            = None
    frames_ms:         Optional[int]            = None

    target_lang       = None
    sub_source        = None
    manual_langs:     list = []
    auto_langs:       list = []
    original_language = None

    try:
        # ── Phase 1: 메타데이터 1회 공유 fetch ───────────────────────────
        if req.extract_subtitle or req.extract_frames:
            meta_opts = {
                "skip_download":  True,
                "quiet":          True,
                "socket_timeout": YT_DLP_TIMEOUT,
                "extractor_args": _EXTRACTOR_ARGS,
            }
            if COOKIE_FILE:
                meta_opts["cookiefile"] = COOKIE_FILE

            info = await asyncio.to_thread(_ydl_extract, meta_opts, req.url, download=False)
            if info is None:
                raise HTTPException(status_code=500, detail="extractor_returned_none")
            _guard(info)

            video_id     = info.get("id")
            duration_sec = info.get("duration")
            title        = info.get("title")
            raw_desc     = info.get("description") or ""
            description  = raw_desc[:500] if raw_desc else None

            if req.extract_subtitle:
                target_lang, sub_source, manual_langs, auto_langs = _choose_subtitle_lang(
                    info, req.subtitle_lang or "ko"
                )
                original_language = info.get("language")
                log.info(
                    "Subtitle survey %s — requested=%s target=%s source=%s original=%s "
                    "manual=%s auto_top=%s",
                    video_id, req.subtitle_lang, target_lang, sub_source,
                    original_language, manual_langs, auto_langs,
                )

        # ── Phase 2: 자막·비디오 병렬 다운로드 (T1: 순차 → 병렬) ─────────
        sub_task  = None
        vid_task  = None
        sub_start = None
        vid_start = None

        if req.extract_subtitle:
            sub_start = time.monotonic()
            if target_lang is not None:
                sub_opts = {
                    "writesubtitles":           (sub_source == "manual"),
                    "writeautomaticsub":        (sub_source == "auto"),
                    "subtitleslangs":           [target_lang],
                    "subtitlesformat":          "vtt",
                    "skip_download":            True,
                    "format":                   "best",
                    "outtmpl":                  str(job_dir / "%(id)s.%(ext)s"),
                    "socket_timeout":           YT_DLP_TIMEOUT,
                    "ignoreerrors":             "only_download",
                    "sleep_interval_subtitles": 2,
                    "quiet":                    True,
                    "extractor_args":           _EXTRACTOR_ARGS,
                }
                if COOKIE_FILE:
                    sub_opts["cookiefile"] = COOKIE_FILE
                sub_task = asyncio.create_task(
                    asyncio.to_thread(_ydl_extract_safe, sub_opts, req.url, video_id or "")
                )

        if req.extract_frames and req.frame_count > 0:
            vid_start = time.monotonic()
            ydl_opts_vid = {
                "format":         YT_FORMAT,
                "outtmpl":        str(job_dir / "%(id)s.%(ext)s"),
                "socket_timeout": YT_DLP_TIMEOUT,
                "quiet":          True,
                "extractor_args": _EXTRACTOR_ARGS,
            }
            if COOKIE_FILE:
                ydl_opts_vid["cookiefile"] = COOKIE_FILE
            vid_task = asyncio.create_task(
                asyncio.to_thread(_ydl_extract, ydl_opts_vid, req.url, download=True)
            )

        # 병렬 대기
        parallel_tasks = [t for t in (sub_task, vid_task) if t is not None]
        if parallel_tasks:
            results = await asyncio.gather(*parallel_tasks, return_exceptions=True)
            for r in results:
                if isinstance(r, Exception):
                    log.warning("parallel download task failed for %s: %s", video_id, r)

        # ── Phase 3: 후처리 ───────────────────────────────────────────────
        if req.extract_subtitle:
            subtitle_result = parse_subtitle(job_dir, info, video_id)
            if subtitle_result is not None:
                subtitle_result.available_langs   = AvailableLangs(manual=manual_langs, auto=auto_langs)
                subtitle_result.original_language = original_language
                subtitle_result.selection_reason  = _selection_reason(
                    target_lang, sub_source, req.subtitle_lang or "ko"
                )
            subtitle_ms = int((time.monotonic() - sub_start) * 1000)

        if req.extract_frames and req.frame_count > 0:
            video_path = _find_video(job_dir, video_id)
            if video_path and duration_sec:
                frames_result = _extract_frames(video_path, duration_sec, req.frame_count, job_dir)
            else:
                frames_result = FramesResult(count=0, frames_base64=[], timestamps_sec=[])
            frames_ms = int((time.monotonic() - vid_start) * 1000)

        total_ms = int((time.monotonic() - total_start) * 1000)
        return ExtractResponse(
            job_id=job_id,
            video_id=video_id or "",
            duration_sec=duration_sec,
            title=title,
            description=description,
            subtitle=subtitle_result,
            frames=frames_result,
            elapsed_ms=ElapsedMs(subtitle=subtitle_ms, frames=frames_ms, total=total_ms),
        )

    finally:
        shutil.rmtree(job_dir, ignore_errors=True)


_FALLBACK_LANGS = ("ko", "en")


def _choose_subtitle_lang(info: dict, requested_lang: str) -> tuple:
    manual = info.get("subtitles") or {}
    auto   = info.get("automatic_captions") or {}
    manual_langs = list(manual.keys())
    auto_langs   = list(auto.keys())

    if requested_lang in manual:
        return requested_lang, "manual", manual_langs, auto_langs[:10]
    if requested_lang in auto:
        return requested_lang, "auto", manual_langs, auto_langs[:10]
    for lang in _FALLBACK_LANGS:
        if lang != requested_lang and lang in manual:
            return lang, "manual", manual_langs, auto_langs[:10]
    for lang in _FALLBACK_LANGS:
        if lang != requested_lang and lang in auto:
            return lang, "auto", manual_langs, auto_langs[:10]

    return None, None, manual_langs, auto_langs[:10]


def _selection_reason(target_lang, source, requested_lang: str) -> str:
    if target_lang is None:
        return "no_subtitle_available"
    if target_lang == requested_lang and source == "manual":
        return "requested_lang_manual"
    if target_lang == requested_lang and source == "auto":
        return "requested_lang_auto"
    if source == "manual":
        return f"fallback_to_{target_lang}_manual"
    return f"fallback_to_{target_lang}_auto"


def _ydl_extract(opts: dict, url: str, *, download: bool = True) -> dict:
    with yt_dlp.YoutubeDL(opts) as ydl:
        return ydl.extract_info(url, download=download)


def _ydl_extract_safe(opts: dict, url: str, video_id: str) -> None:
    """자막 다운로드 전용 — DownloadError를 삼키고 경고만 남긴다."""
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            ydl.extract_info(url, download=True)
    except (DownloadError, Exception) as e:
        log.warning("Subtitle download failed for %s: %s", video_id, e)


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
    for ext in (".mp4", ".webm", ".mkv"):
        matches = list(job_dir.glob(f"*{ext}"))
        if matches:
            return matches[0]
    return None


def _extract_frames(video_path: Path, duration: float, count: int, job_dir: Path) -> FramesResult:
    interval   = duration / (count + 1)
    timestamps = [round(interval * (i + 1), 2) for i in range(count)]
    frames_b64 = []
    actual_ts  = []

    for i, ts in enumerate(timestamps):
        out_path = job_dir / f"frame_{i:03d}.jpg"
        try:
            subprocess.run(
                # T1: 다운스케일만 허용 — 소스가 720 이하면 원본 유지, 720 초과면 720으로 축소
                ["ffmpeg", "-ss", str(ts), "-i", str(video_path),
                 "-frames:v", "1", "-vf", "scale=min(iw\\,720):-2", str(out_path), "-y"],
                check=True, capture_output=True, timeout=FFMPEG_TIMEOUT,
            )
            if out_path.exists():
                frames_b64.append(base64.b64encode(out_path.read_bytes()).decode())
                actual_ts.append(ts)
        except (subprocess.TimeoutExpired, subprocess.CalledProcessError):
            pass

    return FramesResult(count=len(frames_b64), frames_base64=frames_b64, timestamps_sec=actual_ts)
