"""Unit tests for app.db.repositories.generation_stats_repo.get_status_counts —
the read-only aggregation powering the admin status panel. A fake Mongo verifies
the books group-by mapping, the slot-counter read (incl. the missing-doc → 0
fallback), and the pending/processing task counts."""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.db.repositories import generation_stats_repo as repo


class _Aggregate:
    def __init__(self, rows):
        self._rows = rows

    def to_list(self, length=None):
        async def _coro():
            return self._rows
        return _coro()


def _db(group_rows, slot_doc, pending, processing):
    db = MagicMock()
    db.books.aggregate = MagicMock(return_value=_Aggregate(group_rows))
    db.active_generations.find_one = AsyncMock(return_value=slot_doc)
    db.generation_tasks.count_documents = AsyncMock(side_effect=[pending, processing])
    return db


@pytest.mark.asyncio
async def test_aggregates_counts_slots_and_tasks():
    db = _db(
        group_rows=[
            {"_id": "completed", "count": 3},
            {"_id": "generating", "count": 2},
        ],
        slot_doc={"_id": "counter", "count": 2},
        pending=5,
        processing=1,
    )
    with patch.object(repo, "get_app_db", return_value=db):
        out = await repo.get_status_counts()

    assert out["counts_by_status"] == {"completed": 3, "generating": 2}
    assert out["active_generation_slots"] == 2
    assert out["pending_tasks"] == 5
    assert out["processing_tasks"] == 1


@pytest.mark.asyncio
async def test_missing_slot_counter_defaults_to_zero():
    db = _db(group_rows=[], slot_doc=None, pending=0, processing=0)
    with patch.object(repo, "get_app_db", return_value=db):
        out = await repo.get_status_counts()
    assert out["active_generation_slots"] == 0
    assert out["counts_by_status"] == {}


@pytest.mark.asyncio
async def test_group_rows_with_null_status_are_dropped():
    # A book with no status field aggregates to _id=None — must be excluded.
    db = _db(
        group_rows=[{"_id": None, "count": 9}, {"_id": "queued", "count": 1}],
        slot_doc={"count": 0}, pending=0, processing=0,
    )
    with patch.object(repo, "get_app_db", return_value=db):
        out = await repo.get_status_counts()
    assert out["counts_by_status"] == {"queued": 1}
