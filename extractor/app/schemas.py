from typing import List, Optional
from pydantic import BaseModel, Field


class ExtractRequest(BaseModel):
    url: str
    job_id: Optional[str] = None
    extract_subtitle: bool = True
    extract_frames: bool = True
    frame_count: int = Field(default=3, ge=1, le=10)
    subtitle_lang: Optional[str] = "ko"  # preferred subtitle language


class AvailableLangs(BaseModel):
    manual: List[str] = []
    auto: List[str] = []


class SubtitleResult(BaseModel):
    available: bool
    source: Optional[str] = None            # "manual" | "auto"
    language: Optional[str] = None
    text: Optional[str] = None
    char_count: Optional[int] = None
    segment_count: Optional[int] = None
    reason: Optional[str] = None            # when available=False
    available_langs: Optional[AvailableLangs] = None
    original_language: Optional[str] = None # YouTube-tagged audio language
    selection_reason: Optional[str] = None  # why this lang was chosen


class FramesResult(BaseModel):
    count: int
    frames_base64: List[str]
    timestamps_sec: List[float]


class ElapsedMs(BaseModel):
    subtitle: Optional[int] = None
    frames: Optional[int] = None
    total: int


class ExtractResponse(BaseModel):
    job_id: str
    video_id: str
    duration_sec: Optional[float] = None
    title: Optional[str] = None
    subtitle: Optional[SubtitleResult] = None
    frames: Optional[FramesResult] = None
    elapsed_ms: Optional[ElapsedMs] = None
