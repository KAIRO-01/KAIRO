import hmac
import os

import httpx
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

app = FastAPI(title="KAIRO backend")

GEMINI_KEY = os.environ.get("GEMINI_API_KEY", "")
APP_TOKEN = os.environ.get("KAIRO_TOKEN", "")
MODEL = os.environ.get("GEMINI_MODEL", "gemini-3.8-flash")

SYSTEM_PROMPT = (
    "You are KAIRO, a personal AI assistant. You are intelligent, professional, "
    "friendly and fast. Reply in the same language the user writes in: Hindi, "
    "Hinglish or English. Keep replies short and natural, as in a spoken "
    "conversation. Never claim you did something on the phone unless a tool "
    "confirmed it."
)


class Msg(BaseModel):
    role: str  # "user" or "assistant"
    text: str


class ChatReq(BaseModel):
    messages: list[Msg]


@app.get("/")
def root():
    return {"status": "KAIRO backend running"}


@app.get("/health")
def health():
    return {
        "ok": True,
        "gemini_key_set": bool(GEMINI_KEY),
        "token_set": bool(APP_TOKEN),
        "model": MODEL,
    }


@app.post("/chat")
async def chat(req: ChatReq, x_kairo_token: str = Header(default="")):
    got = x_kairo_token.strip()
    want = APP_TOKEN.strip()
    if not want or not hmac.compare_digest(got.encode(), want.encode()):
        raise HTTPException(status_code=401, detail="Unauthorized")
    if not GEMINI_KEY:
        raise HTTPException(status_code=500, detail="GEMINI_API_KEY not set")

    contents = [
        {
            "role": "user" if m.role == "user" else "model",
            "parts": [{"text": m.text}],
        }
        for m in req.messages[-20:]
    ]
    body = {
        "systemInstruction": {"parts": [{"text": SYSTEM_PROMPT}]},
        "contents": contents,
        "generationConfig": {"maxOutputTokens": 1024},
    }
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent"

    try:
        timeout = httpx.Timeout(120.0, connect=20.0)
        async with httpx.AsyncClient(timeout=timeout) as client:
            r = await client.post(
                url, headers={"x-goog-api-key": GEMINI_KEY}, json=body
            )
    except httpx.HTTPError as e:
        raise HTTPException(
            status_code=504,
            detail=f"Could not reach Gemini ({type(e).__name__}) using model {MODEL}",
        )

    if r.status_code != 200:
        raise HTTPException(
            status_code=502, detail=f"Gemini error {r.status_code}: {r.text[:300]}"
        )

    try:
        parts = r.json()["candidates"][0]["content"]["parts"]
        text = "".join(p.get("text", "") for p in parts)
    except Exception:
        text = ""
    return {"reply": text or "Sorry, I could not generate a reply."}
