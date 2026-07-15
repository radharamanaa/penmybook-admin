"""admin_settings_repo: seed-on-read defaults + partial PATCH semantics.

Mongo is patched at the boundary (get_db) so these run without a live stack —
mirrors book_publisher's test style.
"""

from __future__ import annotations

from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

from app.db.repositories import admin_settings_repo
from app.models.admin_models import AdminSettingsUpdate


def _doc(**over) -> dict:
    base = {
        "_id": "singleton",
        "max_concurrent_generations": 3,
        "llm_quota_available": True,
        "generation_paused": False,
        "updated_by": "system",
        "updated_at": datetime.now(timezone.utc),
    }
    base.update(over)
    return base


async def test_get_settings_seeds_defaults():
    fake_db = MagicMock()
    fake_db.admin_settings.find_one_and_update = AsyncMock(return_value=_doc())

    with patch("app.db.repositories.admin_settings_repo.get_db", return_value=fake_db):
        result = await admin_settings_repo.get_settings()

    assert result.max_concurrent_generations == 3
    assert result.llm_quota_available is True
    assert result.generation_paused is False
    # Seeding is an upsert: first read creates the singleton if missing.
    _, kwargs = fake_db.admin_settings.find_one_and_update.call_args
    assert kwargs["upsert"] is True


async def test_update_settings_partial_preserves_others():
    fake_db = MagicMock()
    # 1st find_one_and_update = the seed/get inside update_settings;
    # 2nd = the $set returning the updated doc.
    fake_db.admin_settings.find_one_and_update = AsyncMock(
        side_effect=[_doc(), _doc(max_concurrent_generations=5, updated_by="admin@x")]
    )

    with patch("app.db.repositories.admin_settings_repo.get_db", return_value=fake_db):
        result = await admin_settings_repo.update_settings(
            AdminSettingsUpdate(max_concurrent_generations=5), updated_by="admin@x"
        )

    assert result.max_concurrent_generations == 5
    assert result.updated_by == "admin@x"
    assert result.llm_quota_available is True  # untouched field preserved


async def test_update_settings_noop_when_no_fields_set():
    fake_db = MagicMock()
    fake_db.admin_settings.find_one_and_update = AsyncMock(return_value=_doc())

    with patch("app.db.repositories.admin_settings_repo.get_db", return_value=fake_db):
        result = await admin_settings_repo.update_settings(
            AdminSettingsUpdate(), updated_by="admin@x"
        )

    # No changes → no $set write; returns current settings (seed/get only).
    assert result.max_concurrent_generations == 3
    assert fake_db.admin_settings.find_one_and_update.await_count == 1
