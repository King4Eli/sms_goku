import re
from contextlib import asynccontextmanager
from datetime import datetime

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from . import config
from . import jobs
from .db import SessionLocal, get_session, init_db
from .models import ApiKey, Message, MessageStatus, generate_key, hash_key

PHONE_RE = re.compile(r"^\+?[0-9]{5,20}$")


def seed_demo_key() -> None:
    if not config.DEMO_API_KEY:
        return
    with SessionLocal() as s:
        h = hash_key(config.DEMO_API_KEY)
        if s.scalar(select(ApiKey).where(ApiKey.key_hash == h)) is None:
            s.add(ApiKey(name="demo", key_prefix=config.DEMO_API_KEY[:10], key_hash=h))
            s.commit()


@asynccontextmanager
async def lifespan(_: FastAPI):
    init_db()
    seed_demo_key()
    yield


app = FastAPI(title="SMS Goku API", lifespan=lifespan)


# ---------- auth ----------

def require_api_key(
    x_api_key: str = Header(..., alias="X-API-Key"), s: Session = Depends(get_session)
) -> ApiKey:
    key = s.scalar(select(ApiKey).where(ApiKey.key_hash == hash_key(x_api_key)))
    if key is None or not key.active:
        raise HTTPException(401, "invalid or inactive API key")
    return key


def require_admin(x_admin_token: str = Header(..., alias="X-Admin-Token")) -> None:
    if x_admin_token != config.ADMIN_TOKEN:
        raise HTTPException(401, "invalid admin token")


def require_runner(x_runner_token: str = Header(..., alias="X-Runner-Token")) -> None:
    if x_runner_token != config.RUNNER_TOKEN:
        raise HTTPException(401, "invalid runner token")


# ---------- schemas ----------

class SendRequest(BaseModel):
    to: str = Field(..., examples=["+15551234567"])
    message: str = Field(..., min_length=1, max_length=1600)

    @field_validator("to")
    @classmethod
    def _phone(cls, v: str) -> str:
        v = re.sub(r"[\s\-()]", "", v)
        if not PHONE_RE.match(v):
            raise ValueError("invalid phone number")
        return v


class MessageOut(BaseModel):
    id: int
    to: str
    message: str
    status: MessageStatus
    attempts: int
    last_error: str | None
    created_at: datetime
    sent_at: datetime | None

    @classmethod
    def of(cls, m: Message) -> "MessageOut":
        return cls(id=m.id, to=m.to_number, message=m.body, status=m.status, attempts=m.attempts,
                   last_error=m.last_error, created_at=m.created_at, sent_at=m.sent_at)


class ApiKeyCreate(BaseModel):
    name: str = Field(..., min_length=1, max_length=100)


class ResultIn(BaseModel):
    error: str | None = Field(None, max_length=500)


# ---------- client ----------

@app.get("/api/health")
def health():
    return {"ok": True}


@app.post("/api/sms", response_model=MessageOut, status_code=202)
def send_sms(req: SendRequest, key: ApiKey = Depends(require_api_key), s: Session = Depends(get_session)):
    """Queue an SMS."""
    m = Message(api_key_id=key.id, to_number=req.to, body=req.message)
    s.add(m)
    s.commit()
    s.refresh(m)
    return MessageOut.of(m)


@app.get("/api/sms", response_model=list[MessageOut])
def list_sms(limit: int = Query(50, ge=1, le=200), key: ApiKey = Depends(require_api_key),
             s: Session = Depends(get_session)):
    rows = s.scalars(select(Message).where(Message.api_key_id == key.id)
                     .order_by(Message.id.desc()).limit(limit))
    return [MessageOut.of(m) for m in rows]


@app.get("/api/sms/{msg_id}", response_model=MessageOut)
def get_sms(msg_id: int, key: ApiKey = Depends(require_api_key), s: Session = Depends(get_session)):
    m = s.get(Message, msg_id)
    if m is None or m.api_key_id != key.id:
        raise HTTPException(404, "message not found")
    return MessageOut.of(m)


# ---------- admin ----------

@app.post("/api/admin/keys", dependencies=[Depends(require_admin)], status_code=201)
def create_key(req: ApiKeyCreate, s: Session = Depends(get_session)):
    raw = generate_key()
    k = ApiKey(name=req.name, key_prefix=raw[:10], key_hash=hash_key(raw))
    s.add(k)
    s.commit()
    # raw key returned once
    return {"id": k.id, "name": k.name, "api_key": raw}


@app.get("/api/admin/keys", dependencies=[Depends(require_admin)])
def list_keys(s: Session = Depends(get_session)):
    return [{"id": k.id, "name": k.name, "prefix": k.key_prefix, "active": k.active,
             "created_at": k.created_at} for k in s.scalars(select(ApiKey).order_by(ApiKey.id))]


@app.delete("/api/admin/keys/{key_id}", dependencies=[Depends(require_admin)])
def revoke_key(key_id: int, s: Session = Depends(get_session)):
    k = s.get(ApiKey, key_id)
    if k is None:
        raise HTTPException(404, "key not found")
    k.active = False
    s.commit()
    return {"id": k.id, "active": False}


@app.get("/api/admin/stats", dependencies=[Depends(require_admin)])
def stats(s: Session = Depends(get_session)):
    rows = s.execute(select(Message.status, func.count()).group_by(Message.status)).all()
    return {st.value: 0 for st in MessageStatus} | {st.value: n for st, n in rows}


# ---------- runner ----------

@app.post("/api/runner/claim", dependencies=[Depends(require_runner)])
def runner_claim(limit: int = Query(10, ge=1, le=100)):
    """Claim queued messages (-> sending)."""
    jobs.requeue_stale()
    return [{"id": m.id, "to": m.to_number, "message": m.body} for m in jobs.claim_batch(limit)]


@app.post("/api/runner/result/{msg_id}", dependencies=[Depends(require_runner)])
def runner_result(msg_id: int, req: ResultIn):
    """Report a send; error null = sent."""
    status = jobs.record(msg_id, req.error)
    if status is None:
        raise HTTPException(409, "message not in sending")
    return {"id": msg_id, "status": status}
