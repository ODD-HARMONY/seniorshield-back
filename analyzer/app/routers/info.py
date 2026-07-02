import asyncio
from fastapi import APIRouter, HTTPException
from app.schemas import InfoRequest, InfoResponse
from app.gemini import info as gemini_info

router = APIRouter()


@router.post("/info", response_model=InfoResponse)
async def info(req: InfoRequest):
    try:
        result = await asyncio.to_thread(
            gemini_info.analyze_info, req.subtitle_text, req.category, req.lang
        )
        return InfoResponse(**result)
    except Exception as e:
        raise HTTPException(status_code=502, detail=str(e))
