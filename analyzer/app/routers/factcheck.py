from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Literal

from app.factcheck.core import search

router = APIRouter()


class FactCheckRequest(BaseModel):
    claim: str
    lang: Literal["ko", "en"] = "ko"


@router.post("/factcheck")
async def factcheck(req: FactCheckRequest):
    try:
        return await search(req.claim, req.lang)
    except Exception as e:
        raise HTTPException(status_code=502, detail=str(e))
