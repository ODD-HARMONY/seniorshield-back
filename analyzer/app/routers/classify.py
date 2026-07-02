import asyncio
from fastapi import APIRouter, HTTPException
from app.schemas import ClassifyRequest, ClassifyResponse
from app.gemini import classify as gemini_classify

router = APIRouter()


@router.post("/classify", response_model=ClassifyResponse)
async def classify(req: ClassifyRequest):
    try:
        result = await asyncio.to_thread(gemini_classify.classify, req.subtitle_text, req.lang)
        return ClassifyResponse(**result)
    except Exception as e:
        raise HTTPException(status_code=502, detail=str(e))
