"""Read-only generation stats for the admin status panel.

Aggregates live counts from the shared `books` and `generation_tasks`
collections plus the `active_generations` slot counter. book_admin never writes
these — it only reports them.
"""

from __future__ import annotations

from app.db.mongo import get_db

_ACTIVE_GENERATIONS_ID = "counter"


async def get_status_counts() -> dict:
    db = get_db()

    rows = await db.books.aggregate(
        [{"$group": {"_id": "$status", "count": {"$sum": 1}}}]
    ).to_list(length=None)
    counts_by_status = {r["_id"]: r["count"] for r in rows if r.get("_id")}

    slot_doc = await db.active_generations.find_one({"_id": _ACTIVE_GENERATIONS_ID})
    active_generation_slots = int(slot_doc["count"]) if slot_doc and "count" in slot_doc else 0

    pending_tasks = await db.generation_tasks.count_documents({"status": "pending"})
    processing_tasks = await db.generation_tasks.count_documents({"status": "processing"})

    return {
        "counts_by_status": counts_by_status,
        "active_generation_slots": active_generation_slots,
        "pending_tasks": pending_tasks,
        "processing_tasks": processing_tasks,
    }
