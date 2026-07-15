"""Append-only audit trail for every admin action (`admin_audit_log`).

Every config push, money action, and account change writes one row BEFORE the
response returns. The collection is append-only by convention and code (this
module exposes no update/delete); operator-proof immutability (WORM storage)
is an AWS-phase concern recorded in the decision log.

Row shape:
    {
        "actor_uuid": "<admin uuid>",  # or "system:<component>" for machine actors
        "actor_email": "ops@x.com",
        "action": "config.push" | "admin_user.create" | "auth.login" | ...,
        "target": "operations" | "<admin uuid>" | "<user id>" | ...,
        "old": {...} | None,
        "new": {...} | None,
        "outcome": "allow" | "deny" | "error",
        "detail": "<short free text>",
        "client_ip": "1.2.3.4" | None,
        "ts": datetime,
    }
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from app.db.mongo import get_db


async def record(
    *,
    actor_uuid: str,
    actor_email: str,
    action: str,
    target: str,
    outcome: str = "allow",
    old: dict[str, Any] | None = None,
    new: dict[str, Any] | None = None,
    detail: str = "",
    client_ip: str | None = None,
) -> None:
    await get_db().admin_audit_log.insert_one(
        {
            "actor_uuid": actor_uuid,
            "actor_email": actor_email,
            "action": action,
            "target": target,
            "outcome": outcome,
            "old": old,
            "new": new,
            "detail": detail,
            "client_ip": client_ip,
            "ts": datetime.now(timezone.utc),
        }
    )


async def list_entries(
    *, limit: int = 50, before: datetime | None = None, action: str | None = None
) -> list[dict]:
    """Newest-first keyset pagination (`before` = the last seen ts)."""
    query: dict[str, Any] = {}
    if before is not None:
        query["ts"] = {"$lt": before}
    if action:
        query["action"] = action
    cursor = (
        get_db().admin_audit_log.find(query).sort("ts", -1).limit(max(1, min(limit, 200)))
    )
    return [doc async for doc in cursor]
