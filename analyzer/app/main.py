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
