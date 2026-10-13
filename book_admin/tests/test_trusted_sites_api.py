"""Trusted-sites admin API: auth gating, pass-through of PenMyBook's answers, audit after the push."""

from __future__ import annotations

from unittest.mock import AsyncMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from api.routers.trusted_sites import router as trusted_sites_router
from app.services.auth import issue_token
from app.services.curator_push import CuratorPushError

_UUID = "11111111-2222-4333-8444-555555555555"
_ROW = {"site": "docs.example.org", "action": "suspend", "who": "admin@x", "reason": "flagged", "when": None}


@pytest.fixture(autouse=True)
def _stub_admin_lookup():
    doc = {"admin_uuid": _UUID, "email": "admin@x", "role": "admin", "token_version": 0}
    with patch("app.services.auth.admin_users_repo.get_by_uuid", new=AsyncMock(return_value=doc)):
        yield


@pytest.fixture
def audit():
    with patch("api.routers.trusted_sites.admin_audit_repo.record", new=AsyncMock()) as recorded:
        yield recorded


def _client() -> TestClient:
    app = FastAPI()
    app.include_router(trusted_sites_router, prefix="/admin")
    return TestClient(app)


def _auth() -> dict:
    return {"Authorization": f"Bearer {issue_token(admin_uuid=_UUID, email='admin@x', token_version=0, role='admin')}"}


def test_every_route_needs_an_admin(audit):
    client = _client()
    assert client.get("/admin/trusted-sites").status_code == 401
    assert client.put("/admin/trusted-sites", json={"site": "a.example", "action": "trust", "reason": "why"}).status_code == 401
    assert client.delete("/admin/trusted-sites/a.example").status_code == 401
    audit.assert_not_awaited()


def test_the_list_is_passed_through(audit):
    with patch("api.routers.trusted_sites.curator_push.get_trusted_sites", new=AsyncMock(return_value={"overrides": [_ROW]})):
        answer = _client().get("/admin/trusted-sites", headers=_auth())
    assert answer.status_code == 200 and answer.json() == {"overrides": [_ROW]}


def test_a_change_is_pushed_with_the_admins_email_and_audited_after_it_is_confirmed(audit):
    pushed = AsyncMock(return_value={"applied": _ROW})
    with patch("api.routers.trusted_sites.curator_push.put_trusted_site", new=pushed):
        answer = _client().put(
            "/admin/trusted-sites", headers=_auth(),
            json={"site": "docs.example.org", "action": "suspend", "reason": "flagged"},
        )
    assert answer.status_code == 200
    pushed.assert_awaited_once_with("admin@x", "docs.example.org", "suspend", "flagged")
    assert audit.await_args.kwargs["action"] == "trusted_sites.suspend" and audit.await_args.kwargs["new"] == _ROW


@pytest.mark.parametrize(("upstream", "expected"), [(422, 422), (404, 404), (500, 502), (None, 502)])
def test_a_refused_change_is_not_audited(audit, upstream, expected):
    refused = AsyncMock(side_effect=CuratorPushError("refused", status_code=upstream))
    with patch("api.routers.trusted_sites.curator_push.put_trusted_site", new=refused):
        answer = _client().put(
            "/admin/trusted-sites", headers=_auth(), json={"site": "x", "action": "trust", "reason": "why"},
        )
    assert answer.status_code == expected
    audit.assert_not_awaited()


def test_an_unknown_action_or_a_missing_reason_never_reaches_penmybook(audit):
    pushed = AsyncMock()
    with patch("api.routers.trusted_sites.curator_push.put_trusted_site", new=pushed):
        client = _client()
        assert client.put("/admin/trusted-sites", headers=_auth(), json={"site": "a.example", "action": "bless", "reason": "why"}).status_code == 422
        assert client.put("/admin/trusted-sites", headers=_auth(), json={"site": "a.example", "action": "trust", "reason": ""}).status_code == 422
    pushed.assert_not_awaited()


def test_a_removal_is_pushed_and_audited(audit):
    removed = AsyncMock(return_value={"removed": "docs.example.org"})
    with patch("api.routers.trusted_sites.curator_push.delete_trusted_site", new=removed):
        answer = _client().delete("/admin/trusted-sites/docs.example.org", headers=_auth())
    assert answer.status_code == 200
    removed.assert_awaited_once_with("admin@x", "docs.example.org")
    assert audit.await_args.kwargs["action"] == "trusted_sites.remove"
