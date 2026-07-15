"""Repository for the singleton `admin_settings` document.

book_admin is the ONLY writer of this document. The worker reads it at
queue-claim time (concurrency cap, pause, quota gate for new `full` tasks); the
curator reads it to gate the Resume affordance. Seeding is lazy: the first read
upserts defaults so there is no separate migration/startup write.
"""

from __future__ import annotations

from datetime import datetime, timezone

from pymongo import ReturnDocument

from app.config.settings import settings
from app.db.mongo import get_db
from app.models.admin_models import AdminSettings, AdminSettingsUpdate

SINGLETON_ID = "singleton"


def _seed_defaults() -> dict:
    return {
        "max_concurrent_generations": settings.DEFAULT_MAX_CONCURRENT_GENERATIONS,
        "llm_quota_available": True,
        "generation_paused": False,
        "updated_by": "system",
        "updated_at": datetime.now(timezone.utc),
    }


async def get_settings() -> AdminSettings:
    """Read the singleton, seeding defaults atomically on first access."""
    db = get_db()
    doc = await db.admin_settings.find_one_and_update(
        {"_id": SINGLETON_ID},
        {"$setOnInsert": _seed_defaults()},
        upsert=True,
        return_document=ReturnDocument.AFTER,
    )
    return AdminSettings.from_doc(doc)


async def update_settings(patch: AdminSettingsUpdate, updated_by: str) -> AdminSettings:
    """Apply a partial update. Only fields the caller set are written; the rest
    are preserved. Returns the resulting settings."""
    changes = patch.model_dump(exclude_none=True)
    # Ensure the singleton exists before the $set (and gives us current values
    # for a no-op patch).
    current = await get_settings()
    if not changes:
        return current

    changes["updated_by"] = updated_by
    changes["updated_at"] = datetime.now(timezone.utc)
    db = get_db()
    # upsert=True so a singleton that vanished between the get_settings() seed
    # above and this write (concurrent delete / failover) is recreated rather
    # than returning None → from_doc(None) crash. With ReturnDocument.AFTER the
    # result is always a doc; from_doc fills any unset fields with safe defaults.
    doc = await db.admin_settings.find_one_and_update(
        {"_id": SINGLETON_ID},
        {"$set": changes},
        upsert=True,
        return_document=ReturnDocument.AFTER,
    )
    return AdminSettings.from_doc(doc)
