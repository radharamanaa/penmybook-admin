"""Feedback triage accessors — metadata list, screenshot blob, resolve delete.

Uses ``get_feedback_db()`` (pinned to ``APP_MONGODB_DB_NAME`` with feedback-scoped
creds). Never touch screenshot bytes in list projections.
"""

from __future__ import annotations

from typing import Any

from app.db.mongo import get_feedback_db

_FEEDBACK = "feedback"
_SCREENSHOTS = "feedback_screenshots"

# Metadata-only projection — bytes live in the child collection anyway.
_LIST_PROJECTION = {
    "_id": 0,
    "feedback_id": 1,
    "context": 1,
    "category": 1,
    "rating": 1,
    "message": 1,
    "created_at": 1,
    "user_id": 1,
    "org_id": 1,
    "email": 1,
    "screenshot_ids": 1,
    "book_id": 1,
    "run_id": 1,
    "version": 1,
    "session_id": 1,
    "page_url": 1,
}


async def list_feedback(*, page: int, limit: int) -> dict[str, Any]:
    """Newest-first page of feedback metadata (no screenshot bytes)."""
    page = max(1, page)
    limit = max(1, min(limit, 100))
    db = get_feedback_db()
    total = await db[_FEEDBACK].count_documents({})
    cursor = (
        db[_FEEDBACK]
        .find({}, _LIST_PROJECTION)
        .sort("created_at", -1)
        .skip((page - 1) * limit)
        .limit(limit)
    )
    items = [doc async for doc in cursor]
    return {"items": items, "total": total, "page": page, "limit": limit}


async def get_feedback(feedback_id: str) -> dict[str, Any] | None:
    """Return the parent feedback doc (snapshot for audit), or None."""
    db = get_feedback_db()
    doc = await db[_FEEDBACK].find_one(
        {"feedback_id": feedback_id},
        {"_id": 0, "data": 0},  # belt: never project a stray bytes field
    )
    return doc


async def get_screenshot(screenshot_id: str) -> dict[str, Any] | None:
    """Return one child doc including ``data`` bytes + ``content_type``."""
    db = get_feedback_db()
    return await db[_SCREENSHOTS].find_one(
        {"screenshot_id": screenshot_id},
        {"_id": 0},
    )


async def delete_feedback(feedback_id: str) -> bool:
    """Delete parent + children. Idempotent: returns False if already gone."""
    db = get_feedback_db()
    parent = await db[_FEEDBACK].delete_one({"feedback_id": feedback_id})
    await db[_SCREENSHOTS].delete_many({"feedback_id": feedback_id})
    return parent.deleted_count > 0
