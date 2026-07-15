"""Admin settings + status API: auth gating, partial PATCH, validation, status shape."""

from __future__ import annotations

from datetime import datetime, timezone
from unittest.mock import AsyncMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from api.routers.admin import router as admin_router
from app.services.auth import issue_token

_UUID = "11111111-2222-4333-8444-555555555555"


@pytest.fixture(autouse=True)
def _stub_admin_lookup():
    """require_admin re-reads the admin doc (token_version check) — stub it."""
    doc = {"admin_uuid": _UUID, "email": "admin@x", "role": "admin", "token_version": 0}
    with patch(
        "app.services.auth.admin_users_repo.get_by_uuid", new=AsyncMock(return_value=doc)
    ):
        yield


def _app() -> FastAPI:
    app = FastAPI()
    app.include_router(admin_router, prefix="/admin")
    return app


def _auth() -> dict:
    token = issue_token(admin_uuid=_UUID, email="admin@x", token_version=0, role="admin")
    return {"Authorization": f"Bearer {token}"}


def _ops(**over) -> dict:
    """The `operations` block as the curator's GET /internal/admin-config returns
    it (book_admin reads the levers from there — the source of truth)."""
    base = dict(
        max_concurrent_generations=3,
        llm_quota_available=True,
        generation_paused=False,
        updated_by="system",
        updated_at=datetime.now(timezone.utc).isoformat(),
    )
    base.update(over)
    return base


def _config(**over) -> dict:
    return {"operations": _ops(**over)}


def test_get_settings_requires_auth_401():
    client = TestClient(_app())
    assert client.get("/admin/settings").status_code == 401


def test_get_settings_with_token_200():
    with patch("api.routers.admin.curator_push.get_config", new=AsyncMock(return_value=_config())):
        client = TestClient(_app())
        resp = client.get("/admin/settings", headers=_auth())
    assert resp.status_code == 200
    assert resp.json()["max_concurrent_generations"] == 3


def test_patch_settings_pushes_to_curator_then_audits():
    """PUSH model (D3): the curator persists; audit fires only after the push
    confirms; the lever change rides the push payload with the admin's email."""
    push_mock = AsyncMock(return_value={"applied": {"max_concurrent_generations": 5}})
    audit_mock = AsyncMock()
    # before-read, then read-back-after-push (reflects the pushed value).
    get_cfg_mock = AsyncMock(
        side_effect=[_config(), _config(max_concurrent_generations=5, updated_by="admin@x")]
    )
    with patch("api.routers.admin.curator_push.push_operations", new=push_mock), \
         patch("api.routers.admin.curator_push.get_config", new=get_cfg_mock), \
         patch("api.routers.admin.admin_audit_repo.record", new=audit_mock):
        client = TestClient(_app())
        resp = client.patch("/admin/settings", headers=_auth(), json={"max_concurrent_generations": 5})
    assert resp.status_code == 200
    assert resp.json()["max_concurrent_generations"] == 5
    push_mock.assert_awaited_once_with("admin@x", {"max_concurrent_generations": 5})
    audit_mock.assert_awaited_once()


def test_patch_settings_failed_push_means_no_audit_and_502():
    """A failed push must surface loudly and record NOTHING as applied."""
    from app.services.curator_push import CuratorPushError

    audit_mock = AsyncMock()
    with patch(
        "api.routers.admin.curator_push.push_operations",
        new=AsyncMock(side_effect=CuratorPushError("BookForge unreachable")),
    ), patch(
        "api.routers.admin.curator_push.get_config",
        new=AsyncMock(return_value=_config()),
    ), patch("api.routers.admin.admin_audit_repo.record", new=audit_mock):
        client = TestClient(_app())
        resp = client.patch("/admin/settings", headers=_auth(), json={"generation_paused": True})
    assert resp.status_code == 502
    audit_mock.assert_not_awaited()


def test_patch_rejects_cap_below_1_422():
    client = TestClient(_app())
    resp = client.patch("/admin/settings", headers=_auth(), json={"max_concurrent_generations": 0})
    assert resp.status_code == 422


def test_get_status_returns_counts():
    stats = {
        "counts_by_status": {"generating": 2, "queued": 4, "llm_failed": 1},
        "active_generation_slots": 2,
        "pending_tasks": 4,
        "processing_tasks": 2,
    }
    with patch("api.routers.admin.curator_push.get_config", new=AsyncMock(return_value=_config())), \
         patch("api.routers.admin.generation_stats_repo.get_status_counts", new=AsyncMock(return_value=stats)):
        client = TestClient(_app())
        resp = client.get("/admin/status", headers=_auth())
    assert resp.status_code == 200
    body = resp.json()
    assert body["active_generation_slots"] == 2
    assert body["counts_by_status"]["generating"] == 2
    assert body["settings"]["max_concurrent_generations"] == 3
