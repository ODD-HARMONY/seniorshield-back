import os
import httpx

API = "https://factchecktools.googleapis.com/v1alpha1/claims:search"

FALLBACK_ORDER = {
    "ko": ["ko", "en"],
    "en": ["en", "ko"],
}


async def search(claim: str, lang: str = "ko") -> dict:
    order = FALLBACK_ORDER.get(lang, ["ko", "en"])
    for i, l in enumerate(order):
        result = await _search_one(claim, l)
        if result["matches"]:
            result["fallback_applied"] = (i > 0)
            return result
    result["fallback_applied"] = False
    return result


async def _search_one(claim: str, lang: str) -> dict:
    params = {
        "query":        claim,
        "languageCode": lang,
        "key":          os.environ.get("GOOGLE_FACTCHECK_API_KEY", ""),
        "pageSize":     10,
    }
    async with httpx.AsyncClient(timeout=10) as cli:
        r = await cli.get(API, params=params)
        r.raise_for_status()
        data = r.json()
    return _normalize(data, lang)


def _normalize(raw: dict, language_used: str) -> dict:
    matches = []
    for claim in raw.get("claims", []):
        for review in claim.get("claimReview", []):
            matches.append({
                "publisher":         review.get("publisher", {}).get("name"),
                "publisher_site":    review.get("publisher", {}).get("site"),
                "rating":            review.get("textualRating"),
                "rating_normalized": _normalize_rating(review.get("textualRating", "")),
                "url":               review.get("url"),
                "review_date":       review.get("reviewDate"),
            })
    return {"matches": matches, "language_used": language_used}


def _normalize_rating(rating: str) -> str:
    r = rating.lower()
    if any(w in r for w in ["mostly false", "대체로 거짓"]): return "mostly_false"
    if any(w in r for w in ["mostly true",  "대체로 사실"]): return "mostly_true"
    if any(w in r for w in ["mixed",         "혼합"]):        return "mixed"
    if any(w in r for w in ["false",         "거짓", "사실 아님"]): return "false"
    if any(w in r for w in ["true",          "사실"]):        return "true"
    return "unknown"
