"""Closed-login auth: bcrypt verify, JWT issue/decode, require_admin gating,
persistent lockout (D7), and token_version revocation (D7/D16)."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, patch

from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

from api.routers.auth import router as auth_router
from app.services.auth import (
    AdminPrincipal,
    decode_token,
    hash_password,
    issue_token,
    require_admin,
)

_UUID = "11111111-2222-4333-8444-555555555555"


def _app() -> FastAPI:
    app = FastAPI()
    app.include_router(auth_router, prefix="/admin/auth")

    @app.get("/admin/ping")
    async def ping(admin: AdminPrincipal = Depends(require_admin)) -> dict:
        return {"admin": admin.email, "uuid": admin.admin_uuid}

    return app


def _user(
    password: str = "s3cret",
    role: str = "admin",
    token_version: int = 0,
    locked_until: datetime | None = None,
) -> dict:
    return {
        "admin_uuid": _UUID,
        "email": "admin@x",
        "password_hash": hash_password(password),
        "role": role,
        "token_version": token_version,
        "failed_login_attempts": 0,
        "locked_until": locked_until,
    }


def _mint(role: str = "admin", ver: int = 0) -> str:
    return issue_token(admin_uuid=_UUID, email="admin@x", token_version=ver, role=role)


def _patch_side_effects():
    """Silence the login route's repo/audit side-effect calls."""
    return (
        patch("api.routers.auth.admin_users_repo.record_failed_login", new=AsyncMock()),
        patch("api.routers.auth.admin_users_repo.record_successful_login", new=AsyncMock()),
        patch("api.routers.auth.admin_audit_repo.record", new=AsyncMock()),
    )


def test_login_success_returns_jwt():
    p1, p2, p3 = _patch_side_effects()
    with patch(
        "api.routers.auth.admin_users_repo.get_by_email", new=AsyncMock(return_value=_user())
    ), p1, p2, p3:
        client = TestClient(_app())
        resp = client.post("/admin/auth/login", json={"email": "admin@x", "password": "s3cret"})

    assert resp.status_code == 200
    body = resp.json()
    payload = decode_token(body["access_token"])
    # D16: sub is the admin UUID (admin-api parses it as an actor UUID); the
    # email rides as a display claim. aud/iss pin the token to this surface.
    assert payload["sub"] == _UUID
    assert payload["email"] == "admin@x"
    assert payload["role"] == "admin"
    assert payload["aud"] == "bookforge-admin"
    assert payload["iss"] == "book_admin"
    assert payload["ver"] == 0
    assert body["token_type"] == "bearer"


def test_login_bad_password_401_and_counts_failure():
    p1, p2, p3 = _patch_side_effects()
    with patch(
        "api.routers.auth.admin_users_repo.get_by_email", new=AsyncMock(return_value=_user())
    ), p1 as failed_mock, p2, p3:
        client = TestClient(_app())
        resp = client.post("/admin/auth/login", json={"email": "admin@x", "password": "wrong"})

    assert resp.status_code == 401
    failed_mock.assert_awaited_once_with("admin@x")


def test_login_unknown_user_401():
    p1, p2, p3 = _patch_side_effects()
    with patch(
        "api.routers.auth.admin_users_repo.get_by_email", new=AsyncMock(return_value=None)
    ), p1, p2, p3:
        client = TestClient(_app())
        resp = client.post("/admin/auth/login", json={"email": "ghost@x", "password": "whatever"})

    assert resp.status_code == 401


def test_login_locked_account_rejects_even_correct_password():
    """The lock must beat a CORRECT password, or it does nothing vs an attacker."""
    locked = _user(locked_until=datetime.now(timezone.utc) + timedelta(minutes=10))
    p1, p2, p3 = _patch_side_effects()
    with patch(
        "api.routers.auth.admin_users_repo.get_by_email", new=AsyncMock(return_value=locked)
    ), p1, p2, p3:
        client = TestClient(_app())
        resp = client.post("/admin/auth/login", json={"email": "admin@x", "password": "s3cret"})

    assert resp.status_code == 401  # same body as bad creds — no lock oracle


def test_require_admin_rejects_missing_token_401():
    client = TestClient(_app())
    resp = client.get("/admin/ping")
    assert resp.status_code == 401


def test_require_admin_accepts_valid_token():
    with patch(
        "app.services.auth.admin_users_repo.get_by_uuid", new=AsyncMock(return_value=_user())
    ):
        client = TestClient(_app())
        resp = client.get("/admin/ping", headers={"Authorization": f"Bearer {_mint()}"})
    assert resp.status_code == 200
    assert resp.json() == {"admin": "admin@x", "uuid": _UUID}


def test_require_admin_rejects_non_admin_role_403():
    with patch(
        "app.services.auth.admin_users_repo.get_by_uuid",
        new=AsyncMock(return_value=_user(role="viewer")),
    ):
        client = TestClient(_app())
        resp = client.get(
            "/admin/ping", headers={"Authorization": f"Bearer {_mint(role='viewer')}"}
        )
    assert resp.status_code == 403


def test_require_admin_rejects_stale_token_version():
    """Password reset / logout bumps token_version → outstanding tokens die (D7)."""
    with patch(
        "app.services.auth.admin_users_repo.get_by_uuid",
        new=AsyncMock(return_value=_user(token_version=1)),
    ):
        client = TestClient(_app())
        resp = client.get("/admin/ping", headers={"Authorization": f"Bearer {_mint(ver=0)}"})
    assert resp.status_code == 401


def test_require_admin_rejects_deleted_admin():
    """A removed admin's token must die on next use even before exp."""
    with patch(
        "app.services.auth.admin_users_repo.get_by_uuid", new=AsyncMock(return_value=None)
    ):
        client = TestClient(_app())
        resp = client.get("/admin/ping", headers={"Authorization": f"Bearer {_mint()}"})
    assert resp.status_code == 401


def test_logout_bumps_token_version():
    with patch(
        "app.services.auth.admin_users_repo.get_by_uuid", new=AsyncMock(return_value=_user())
    ), patch(
        "api.routers.auth.admin_users_repo.bump_token_version", new=AsyncMock()
    ) as bump_mock, patch(
        "api.routers.auth.admin_audit_repo.record", new=AsyncMock()
    ):
        client = TestClient(_app())
        resp = client.post(
            "/admin/auth/logout", headers={"Authorization": f"Bearer {_mint()}"}
        )
    assert resp.status_code == 204
    bump_mock.assert_awaited_once_with(_UUID)
