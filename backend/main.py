import hmac
import os

import httpx
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

app = FastAPI(title="KAIRO backend")

GEMINI_KEY = os.environ.get("GEMINI_API_KEY", "")
APP_TOKEN = os.environ.get("KAIRO_TOKEN", "")
MODEL = os.environ.get("GEMINI_MODEL", "gemini-2.5-flash")

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
        # TEMPORARY debug: shows only lengths, never the token itself
        raise HTTPException(
            status_code=401,
            detail=f"Unauthorized (debug: got {len(got)} chars, expected {len(want)} chars)",
        )
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
    }
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent"

    async with httpx.AsyncClient(timeout=60) as client:
        r = await client.post(url, headers={"x-goog-api-key": GEMINI_KEY}, json=body)

    if r.status_code != 200:
        raise HTTPException(
            status_code=502, detail=f"Gemini error {r.status_code}: {r.text[:300]}"
        )

    try:
        parts = r.json()["candidates"][0]["content"]["parts"]
        text = "".join(p.get("text", "") for p in parts)
    except Exception:
        text = "Sorry, I could not generate a reply."
    return {"reply": text or "Sorry, I could not generate a reply."}
