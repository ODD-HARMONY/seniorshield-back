import asyncio
from fastapi import APIRouter, HTTPException
from app.schemas import ImageRequest, ImageResponse
from app.gemini import image as gemini_image

router = APIRouter()


@router.post("/image", response_model=ImageResponse)
async def image(req: ImageRequest):
    try:
        result = await asyncio.to_thread(gemini_image.detect_aigen, req.frames_base64, req.lang)
        return ImageResponse(**result)
    except Exception as e:
        raise HTTPException(status_code=502, detail=str(e))
