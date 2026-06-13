import subprocess

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse

from app.extractor import extract
from app.schemas import ExtractRequest

app = FastAPI(title="SeniorShield Extractor", version="0.1.0")


@app.get("/health")
async def health():
    yt_dlp_version = _run_version(["yt-dlp", "--version"])
    ffmpeg_raw     = _run_version(["ffmpeg", "-version"])
    ffmpeg_version = ffmpeg_raw.split("\n")[0].split(" ")[2] if ffmpeg_raw else "unknown"
    return {"status": "ok", "yt_dlp_version": yt_dlp_version, "ffmpeg_version": ffmpeg_version}


@app.post("/extract")
async def extract_endpoint(req: ExtractRequest):
    try:
        return await extract(req)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


def _run_version(cmd: list) -> str:
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
        return r.stdout.strip()
    except Exception:
        return "unknown"
