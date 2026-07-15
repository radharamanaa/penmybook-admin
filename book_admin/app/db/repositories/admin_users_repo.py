"""Repository for the `admin_users` collection (closed set, no self-signup).

Document shape (decision log D16):
    {
        "_id": ObjectId,                  # Mongo-internal
        "admin_uuid": "<uuid4 str>",      # stable identity; JWT `sub`
        "email": "ops@example.com",
        "password_hash": "<bcrypt>",
        "role": "admin",
        "token_version": 0,               # bump = revoke all outstanding tokens
        "failed_login_attempts": 0,       # D7 lockout counters (persistent)
        "locked_until": datetime | None,
        "created_at": datetime,
    }
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from app.config.settings import settings
from app.db.mongo import get_db


async def get_by_email(email: str) -> dict | None:
    db = get_db()
    return await db.admin_users.find_one({"email": email})


async def get_by_uuid(admin_uuid: str) -> dict | None:
    db = get_db()
    return await db.admin_users.find_one({"admin_uuid": admin_uuid})


async def list_admins() -> list[dict]:
    db = get_db()
    cursor = db.admin_users.find(
        {}, {"password_hash": 0}
    ).sort("created_at", 1)
    return [doc async for doc in cursor]


async def count_admins() -> int:
    db = get_db()
    return int(await db.admin_users.count_documents({}))


async def upsert_admin(email: str, password_hash: str, role: str = "admin") -> None:
    """Idempotently create/refresh an admin user. Used by the seed script.

    Re-seeding an existing admin refreshes the password hash but does NOT reset
    identity (admin_uuid) — audit history stays attached to the same UUID.
    """
    db = get_db()
    await db.admin_users.update_one(
        {"email": email},
        {
            "$set": {"password_hash": password_hash, "role": role},
            "$setOnInsert": {
                "admin_uuid": str(uuid.uuid4()),
                "token_version": 0,
                "failed_login_attempts": 0,
                "locked_until": None,
                "created_at": datetime.now(timezone.utc),
            },
        },
        upsert=True,
    )


async def create_admin(email: str, password_hash: str) -> dict:
    """Create a new admin (portal CRUD). Fails if the email already exists."""
    db = get_db()
    existing = await db.admin_users.find_one({"email": email})
    if existing is not None:
        raise ValueError("admin with this email already exists")
    doc = {
        "admin_uuid": str(uuid.uuid4()),
        "email": email,
        "password_hash": password_hash,
        "role": "admin",
        "token_version": 0,
        "failed_login_attempts": 0,
        "locked_until": None,
        "created_at": datetime.now(timezone.utc),
    }
    await db.admin_users.insert_one(doc)
    doc.pop("password_hash", None)
    return doc


async def delete_admin(admin_uuid: str) -> bool:
    """Remove an admin. The LAST admin can never be removed (lockout guard).

    Atomicity note: the count-then-delete window is acceptable on this surface —
    admin CRUD is a 2-operator, low-write path, and the failure mode (a delete
    racing another delete) still leaves ≥0 admins only if both reads saw >1;
    the seed script can always restore access.
    """
    db = get_db()
    total = await db.admin_users.count_documents({})
    if total <= 1:
        raise ValueError("cannot remove the last admin account")
    result = await db.admin_users.delete_one({"admin_uuid": admin_uuid})
    return result.deleted_count == 1


async def reset_password(admin_uuid: str, password_hash: str) -> bool:
    """Set a new password hash and revoke all outstanding tokens for the admin."""
    result = await get_db().admin_users.update_one(
        {"admin_uuid": admin_uuid},
        {
            "$set": {
                "password_hash": password_hash,
                "failed_login_attempts": 0,
                "locked_until": None,
            },
            "$inc": {"token_version": 1},
        },
    )
    return result.matched_count == 1


async def bump_token_version(admin_uuid: str) -> None:
    """Revoke every outstanding token for the admin (logout / removal hygiene)."""
    await get_db().admin_users.update_one(
        {"admin_uuid": admin_uuid}, {"$inc": {"token_version": 1}}
    )


# ───────────────────────── login lockout (D7) ─────────────────────────


def is_locked(user: dict, now: datetime | None = None) -> bool:
    locked_until = user.get("locked_until")
    if locked_until is None:
        return False
    now = now or datetime.now(timezone.utc)
    if locked_until.tzinfo is None:
        locked_until = locked_until.replace(tzinfo=timezone.utc)
    return locked_until > now


async def record_failed_login(email: str) -> None:
    """Increment the failure counter; trip the lock at the configured ceiling."""
    db = get_db()
    user = await db.admin_users.find_one({"email": email})
    if user is None:
        return  # unknown email — nothing to lock (response is the same 401)
    failures = int(user.get("failed_login_attempts", 0)) + 1
    update: dict = {"$set": {"failed_login_attempts": failures}}
    if failures >= settings.ADMIN_LOGIN_MAX_FAILURES:
        update["$set"]["locked_until"] = datetime.now(timezone.utc) + timedelta(
            minutes=settings.ADMIN_LOGIN_LOCKOUT_MIN
        )
        update["$set"]["failed_login_attempts"] = 0
    await db.admin_users.update_one({"email": email}, update)


async def record_successful_login(email: str) -> None:
    await get_db().admin_users.update_one(
        {"email": email},
        {"$set": {"failed_login_attempts": 0, "locked_until": None}},
    )
