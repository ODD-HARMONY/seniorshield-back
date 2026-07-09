from typing import List, Optional
from pydantic import BaseModel, ConfigDict, Field


class ClassifyRequest(BaseModel):
    subtitle_text: str
    title: Optional[str] = None
    description: Optional[str] = None
    lang: str = "ko"


class CheckableClaim(BaseModel):
    text: str
    category: str  # health | finance | science | other


class ClassifyResponse(BaseModel):
    informational: bool
    category: str
    key_topic: str
    key_claim: str = ""
    advertisement: bool = False
    ad_label: str = "none"  # none | normal_ad | likely_false_ad | likely_scam
    checkable_claims: List[CheckableClaim] = []
    ai_script_likelihood: str = "low"  # low | medium | high (자막 문체 기반 AI 스크립트 가능성)


class InfoRequest(BaseModel):
    key_claim: str
    category: str = "other"
    subtitle_text: Optional[str] = None
    title: Optional[str] = None
    description: Optional[str] = None
    ai_script_likelihood: str = "low"  # R3-a: classify가 생성, info로 전달
    lang: str = "ko"


class Claim(BaseModel):
    text: str
    normalized: str
    preliminary_judgement: str
    supporting_sources: List[str] = []
    # R3 2축 판정 필드 (2axis 프롬프트 사용 시 채워짐)
    axis_a_verdict: Optional[str] = None  # confirmed|refuted|no_evidence|not_applicable
    axis_b_verdict: Optional[str] = None  # consensus_ok|consensus_violation|not_applicable
    driving_axis: Optional[str] = None    # A|B|combined


class InfoResponse(BaseModel):
    claims: List[Claim]
    overall_judgement: str
    confidence: float


class AdRequest(BaseModel):
    frames_base64: List[str]
    subtitle_text: str
    initial_label: str  # likely_false_ad | likely_scam
    lang: str = "ko"


class AdResponse(BaseModel):
    label: str
    confidence: float
    reason: Optional[str] = None


class ImageRequest(BaseModel):
    frames_base64: List[str]
    lang: str = "ko"


class FrameResult(BaseModel):
    index: int
    label: str
    confidence: float


class InternalDebug(BaseModel):
    model_config = ConfigDict(extra="allow")

    round1_observation: Optional[str] = None
    round1_ms: Optional[int] = None
    round2_ms: Optional[int] = None


class ImageResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    per_frame: List[FrameResult]
    label: str
    confidence: float
    revision_notes: Optional[str] = None
    internal: Optional[InternalDebug] = Field(None, alias="_internal")
