"""Closed admin login — POST /admin/auth/login, POST /admin/auth/logout.

No registration endpoint. Login enforces the persistent lockout (D7) and
audits every attempt; logout bumps the admin's token_version so every
outstanding token for that account dies immediately (in-memory SPA storage
means there is no other copy to chase).
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request, status

from app.config.settings import settings
from app.db.repositories import admin_audit_repo, admin_users_repo
from app.models.admin_models import LoginRequest, TokenResponse
from app.services.auth import AdminPrincipal, issue_token, require_admin, verify_password

router = APIRouter()


def _client_ip(request: Request) -> str | None:
    return request.client.host if request.client else None


@router.post("/login", response_model=TokenResponse)
async def login(body: LoginRequest, request: Request) -> TokenResponse:
    user = await admin_users_repo.get_by_email(body.email)

    # Lockout check FIRST — a locked account rejects even a correct password,
    # otherwise the lock does nothing against an attacker who finds it.
    if user is not None and admin_users_repo.is_locked(user):
        await admin_audit_repo.record(
            actor_uuid=user.get("admin_uuid", ""),
            actor_email=body.email,
            action="auth.login",
            target=body.email,
            outcome="deny",
            detail="account locked",
            client_ip=_client_ip(request),
        )
        # Same 401 as bad credentials — don't confirm the account exists/locks.
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid credentials"
        )

    # Same 401 for unknown-user and bad-password — don't leak which emails exist.
    if user is None or not await verify_password(body.password, user.get("password_hash", "")):
        await admin_users_repo.record_failed_login(body.email)
        await admin_audit_repo.record(
            actor_uuid=(user or {}).get("admin_uuid", ""),
            actor_email=body.email,
            action="auth.login",
            target=body.email,
            outcome="deny",
            detail="bad credentials",
            client_ip=_client_ip(request),
        )
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid credentials"
        )

    await admin_users_repo.record_successful_login(body.email)
    token = issue_token(
        admin_uuid=str(user["admin_uuid"]),
        email=body.email,
        token_version=int(user.get("token_version", 0)),
        role=user.get("role", "admin"),
    )
    await admin_audit_repo.record(
        actor_uuid=str(user["admin_uuid"]),
        actor_email=body.email,
        action="auth.login",
        target=body.email,
        outcome="allow",
        client_ip=_client_ip(request),
    )
    return TokenResponse(access_token=token, expires_in=settings.ADMIN_JWT_TTL_MIN * 60)


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
async def logout(request: Request, admin: AdminPrincipal = Depends(require_admin)) -> None:
    """Server-side logout: bump token_version → all of this admin's tokens die."""
    await admin_users_repo.bump_token_version(admin.admin_uuid)
    await admin_audit_repo.record(
        actor_uuid=admin.admin_uuid,
        actor_email=admin.email,
        action="auth.logout",
        target=admin.email,
        client_ip=_client_ip(request),
    )
