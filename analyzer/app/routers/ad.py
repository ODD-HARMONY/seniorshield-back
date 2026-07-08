import asyncio
from fastapi import APIRouter, HTTPException
from app.schemas import AdRequest, AdResponse
from app.gemini import ad as gemini_ad

router = APIRouter()


@router.post("/ad", response_model=AdResponse)
async def ad(req: AdRequest):
    try:
        result = await asyncio.to_thread(
            gemini_ad.verify_ad,
            req.frames_base64, req.subtitle_text, req.initial_label, req.lang,
        )
        return AdResponse.model_validate(result)
    except Exception as e:
        raise HTTPException(status_code=502, detail=str(e))
