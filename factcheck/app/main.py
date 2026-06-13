from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from app.google_factcheck import search

app = FastAPI(title="SeniorShield FactCheck", version="0.1.0")


class FactCheckRequest(BaseModel):
    claim: str
    language: str = "ko"


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/factcheck")
async def factcheck(req: FactCheckRequest):
    try:
        return await search(req.claim, req.language)
    except Exception as e:
        raise HTTPException(status_code=502, detail=str(e))
