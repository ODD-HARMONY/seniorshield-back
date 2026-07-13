import logging

# uvicorn이 root logger를 WARNING으로 재설정하므로, 'app' 네임스페이스에 직접 핸들러를 붙여 독립 유지
_app_log = logging.getLogger("app")
_app_log.setLevel(logging.INFO)
if not _app_log.handlers:
    _h = logging.StreamHandler()
    _h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s"))
    _app_log.addHandler(_h)
_app_log.propagate = False

from fastapi import FastAPI
from app.routers import classify, info, image, ad, extract, factcheck

app = FastAPI(title="SeniorShield Analyzer", version="0.1.0")

app.include_router(classify.router)
app.include_router(info.router)
app.include_router(image.router)
app.include_router(ad.router)
app.include_router(extract.router)
app.include_router(factcheck.router)


@app.get("/health")
async def health():
    return {"status": "ok"}
