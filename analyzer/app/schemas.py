from typing import List, Optional
from pydantic import BaseModel


class ClassifyRequest(BaseModel):
    subtitle_text: str
    lang: str = "ko"


class ClassifyResponse(BaseModel):
    informational: bool
    category: str
    key_topic: str
    reason: str


class InfoRequest(BaseModel):
    subtitle_text: str
    category: str = "other"
    lang: str = "ko"


class Claim(BaseModel):
    text: str
    normalized: str
    preliminary_judgement: str
    supporting_sources: List[str] = []


class InfoResponse(BaseModel):
    claims: List[Claim]
    overall_judgement: str
    confidence: float
    reasoning: str


class ImageRequest(BaseModel):
    frames_base64: List[str]
    lang: str = "ko"


class FrameResult(BaseModel):
    index: int
    label: str
    confidence: float
    evidence: str


class ImageResponse(BaseModel):
    per_frame: List[FrameResult]
    label: str
    confidence: float
    aggregate_evidence: str
