"""Message queue: claim, record, re-queue."""

import logging
from datetime import timedelta

from sqlalchemy import select, update

from . import config
from .db import SessionLocal
from .models import Message, MessageStatus, utcnow

log = logging.getLogger("uvicorn.error")


def requeue_stale() -> None:
    """Re-queue messages stuck in sending."""
    cutoff = utcnow() - timedelta(seconds=config.STALE_SENDING_SECONDS)
    with SessionLocal() as s:
        n = s.execute(update(Message)
                      .where(Message.status == MessageStatus.sending, Message.updated_at < cutoff)
                      .values(status=MessageStatus.queued)).rowcount
        s.commit()
    if n:
        log.warning("re-queued %d stale message(s)", n)


def claim_batch(limit: int) -> list[Message]:
    """queued -> sending; SKIP LOCKED = multi-runner safe."""
    with SessionLocal() as s:
        rows = s.scalars(select(Message)
                         .where(Message.status == MessageStatus.queued,
                                Message.next_attempt_at <= utcnow())
                         .order_by(Message.id)
                         .limit(limit)
                         .with_for_update(skip_locked=True)).all()
        for m in rows:
            m.status = MessageStatus.sending
            m.attempts += 1
        s.commit()
        return rows


def record(msg_id: int, error: str | None) -> MessageStatus | None:
    """None if the message isn't in sending."""
    with SessionLocal() as s:
        m = s.get(Message, msg_id, with_for_update=True)
        if m is None or m.status != MessageStatus.sending:
            return None
        if error is None:
            m.status, m.sent_at, m.last_error = MessageStatus.sent, utcnow(), None
        else:
            m.last_error = error
            if m.attempts >= config.MAX_ATTEMPTS:
                m.status = MessageStatus.failed
            else:
                m.status = MessageStatus.queued
                m.next_attempt_at = utcnow() + timedelta(seconds=config.RETRY_BACKOFF_SECONDS * m.attempts)
        s.commit()
        return m.status
