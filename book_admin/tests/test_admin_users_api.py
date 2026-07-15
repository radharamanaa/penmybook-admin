"""Admin-account CRUD: create/list/delete + last-admin guard + password reset."""

from __future__ import annotations

from datetime import datetime, timezone
from unittest.mock import AsyncMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from api.routers.admin_users import router as admin_users_router
from app.services.auth import issue_token

_UUID = "11111111-2222-4333-8444-555555555555"
_OTHER = "99999999-8888-4777-8666-555555555555"


@pytest.fixture(autouse=True)
def _stub_admin_lookup():
    doc = {"admin_uuid": _UUID, "email": "admin@x", "role": "admin", "token_version": 0}
    with patch(
        "app.services.auth.admin_users_repo.get_by_uuid", new=AsyncMock(return_value=doc)
    ):
        yield


@pytest.fixture(autouse=True)
def _stub_audit():
    with patch("api.routers.admin_users.admin_audit_repo.record", new=AsyncMock()):
        yield


def _app() -> FastAPI:
    app = FastAPI()
    app.include_router(admin_users_router, prefix="/admin")
    return app


def _auth() -> dict:
    token = issue_token(admin_uuid=_UUID, email="admin@x", token_version=0)
    return {"Authorization": f"Bearer {token}"}


def _doc(uuid: str = _OTHER, email: str = "second@x") -> dict:
    return {
        "admin_uuid": uuid,
        "email": email,
        "role": "admin",
        "created_at": datetime.now(timezone.utc),
    }


def test_list_admins_requires_auth():
    assert TestClient(_app()).get("/admin/users").status_code == 401


def test_list_admins_returns_views():
    with patch(
        "api.routers.admin_users.admin_users_repo.list_admins",
        new=AsyncMock(return_value=[_doc()]),
    ):
        resp = TestClient(_app()).get("/admin/users", headers=_auth())
    assert resp.status_code == 200
    assert resp.json()[0]["email"] == "second@x"
    assert "password_hash" not in resp.json()[0]


def test_create_admin_201():
    with patch(
        "api.routers.admin_users.admin_users_repo.create_admin",
        new=AsyncMock(return_value=_doc()),
    ):
        resp = TestClient(_app()).post(
            "/admin/users",
            headers=_auth(),
            json={"email": "second@x", "password": "a-long-password-12"},
        )
    assert resp.status_code == 201
    assert resp.json()["admin_uuid"] == _OTHER


def test_create_admin_short_password_422():
    resp = TestClient(_app()).post(
        "/admin/users", headers=_auth(), json={"email": "second@x", "password": "short"}
    )
    assert resp.status_code == 422


def test_create_admin_duplicate_409():
    with patch(
        "api.routers.admin_users.admin_users_repo.create_admin",
        new=AsyncMock(side_effect=ValueError("admin with this email already exists")),
    ):
        resp = TestClient(_app()).post(
            "/admin/users",
            headers=_auth(),
            json={"email": "admin@x", "password": "a-long-password-12"},
        )
    assert resp.status_code == 409


def test_delete_last_admin_blocked_409():
    """The last-admin guard: the portal can never lock everyone out."""
    with patch(
        "api.routers.admin_users.admin_users_repo.get_by_uuid",
        new=AsyncMock(return_value=_doc()),
    ), patch(
        "api.routers.admin_users.admin_users_repo.delete_admin",
        new=AsyncMock(side_effect=ValueError("cannot remove the last admin account")),
    ):
        resp = TestClient(_app()).delete(f"/admin/users/{_OTHER}", headers=_auth())
    assert resp.status_code == 409


def test_delete_admin_204():
    with patch(
        "api.routers.admin_users.admin_users_repo.get_by_uuid",
        new=AsyncMock(return_value=_doc()),
    ), patch(
        "api.routers.admin_users.admin_users_repo.delete_admin",
        new=AsyncMock(return_value=True),
    ):
        resp = TestClient(_app()).delete(f"/admin/users/{_OTHER}", headers=_auth())
    assert resp.status_code == 204


def test_password_reset_204():
    with patch(
        "api.routers.admin_users.admin_users_repo.reset_password",
        new=AsyncMock(return_value=True),
    ) as reset_mock:
        resp = TestClient(_app()).post(
            f"/admin/users/{_OTHER}/password",
            headers=_auth(),
            json={"password": "a-new-long-password-1"},
        )
    assert resp.status_code == 204
    assert reset_mock.await_args.args[0] == _OTHER
