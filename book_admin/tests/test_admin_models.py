"""Unit tests for app.models.admin_models — focused on AdminSettings.from_doc,
whose whole job is to NOT 500 the dashboard when the worker's
mark_quota_unavailable upserts a PARTIAL singleton (only llm_quota_available +
updated_* set). Bare key access there would KeyError; these pin the safe
fallbacks. Also covers the PATCH-body validation bounds."""
from __future__ import annotations

from datetime import datetime, timezone

import pytest
from pydantic import ValidationError

from app.models.admin_models import AdminSettings, AdminSettingsUpdate


def test_from_doc_full_document_round_trips():
    now = datetime(2026, 6, 10, tzinfo=timezone.utc)
    s = AdminSettings.from_doc({
        "max_concurrent_generations": 4,
        "llm_quota_available": False,
        "generation_paused": True,
        "updated_by": "admin@x",
        "updated_at": now,
    })
    assert s.max_concurrent_generations == 4
    assert s.llm_quota_available is False
    assert s.generation_paused is True
    assert s.updated_by == "admin@x"
    assert s.updated_at == now


def test_from_doc_partial_worker_upsert_does_not_raise():
    # The worker outage path may create the doc with ONLY these keys.
    s = AdminSettings.from_doc({
        "llm_quota_available": False,
        "updated_at": datetime(2026, 6, 10, tzinfo=timezone.utc),
    })
    assert s.llm_quota_available is False
    # Missing keys fall back to safe defaults (not a 500).
    assert s.max_concurrent_generations >= 1
    assert s.generation_paused is False
    assert s.updated_by == "system"


def test_from_doc_empty_document_uses_all_defaults():
    s = AdminSettings.from_doc({})
    assert s.llm_quota_available is True
    assert s.generation_paused is False
    assert isinstance(s.updated_at, datetime)


def test_update_body_all_fields_optional():
    upd = AdminSettingsUpdate()
    assert upd.max_concurrent_generations is None
    assert upd.llm_quota_available is None
    assert upd.generation_paused is None


def test_update_body_rejects_zero_concurrency():
    with pytest.raises(ValidationError):
        AdminSettingsUpdate(max_concurrent_generations=0)


def test_update_body_accepts_single_lever():
    upd = AdminSettingsUpdate(generation_paused=True)
    assert upd.generation_paused is True
    assert upd.max_concurrent_generations is None
