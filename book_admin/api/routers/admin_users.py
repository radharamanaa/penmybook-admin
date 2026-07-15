"""Admin-account management — the "two emails today, add more later" surface.

GET    /admin/users                       — list admin accounts
POST   /admin/users                       — create a new admin
DELETE /admin/users/{admin_uuid}          — remove (last-admin guard)
POST   /admin/users/{admin_uuid}/password — reset password (revokes their tokens)
GET    /admin/audit                       — the append-only audit trail

All routes require an authenticated admin. Every mutation is audited.
"""

from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status

from app.db.repositories import admin_audit_repo, admin_users_repo
from app.models.admin_models import (
    AdminPasswordReset,
    AdminUserCreate,
    AdminUserView,
    AuditEntryView,
)
from app.services.auth import AdminPrincipal, hash_password, require_admin

router = APIRouter()


def _client_ip(request: Request) -> str | None:
    return request.client.host if request.client else None


@router.get("/users", response_model=list[AdminUserView])
async def list_admin_users(
    _admin: AdminPrincipal = Depends(require_admin),
) -> list[AdminUserView]:
    return [AdminUserView.from_doc(doc) for doc in await admin_users_repo.list_admins()]


@router.post("/users", response_model=AdminUserView, status_code=status.HTTP_201_CREATED)
async def create_admin_user(
    body: AdminUserCreate,
    request: Request,
    admin: AdminPrincipal = Depends(require_admin),
) -> AdminUserView:
    try:
        doc = await admin_users_repo.create_admin(body.email, hash_password(body.password))
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc))
    await admin_audit_repo.record(
        actor_uuid=admin.admin_uuid,
        actor_email=admin.email,
        action="admin_user.create",
        target=body.email,
        new={"email": body.email},
        client_ip=_client_ip(request),
    )
    return AdminUserView.from_doc(doc)


@router.delete("/users/{admin_uuid}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_admin_user(
    admin_uuid: str,
    request: Request,
    admin: AdminPrincipal = Depends(require_admin),
) -> None:
    target = await admin_users_repo.get_by_uuid(admin_uuid)
    if target is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No such admin")
    try:
        await admin_users_repo.delete_admin(admin_uuid)
    except ValueError as exc:  # last-admin guard
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc))
    # Belt-and-braces: the doc is gone (require_admin already 401s on next use),
    # so no version bump is needed — but audit the removal with the target email.
    await admin_audit_repo.record(
        actor_uuid=admin.admin_uuid,
        actor_email=admin.email,
        action="admin_user.delete",
        target=admin_uuid,
        old={"email": target.get("email", "")},
        client_ip=_client_ip(request),
    )


@router.post("/users/{admin_uuid}/password", status_code=status.HTTP_204_NO_CONTENT)
async def reset_admin_password(
    admin_uuid: str,
    body: AdminPasswordReset,
    request: Request,
    admin: AdminPrincipal = Depends(require_admin),
) -> None:
    ok = await admin_users_repo.reset_password(admin_uuid, hash_password(body.password))
    if not ok:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No such admin")
    await admin_audit_repo.record(
        actor_uuid=admin.admin_uuid,
        actor_email=admin.email,
        action="admin_user.password_reset",
        target=admin_uuid,
        client_ip=_client_ip(request),
    )


@router.get("/audit", response_model=list[AuditEntryView])
async def list_audit_entries(
    limit: int = Query(50, ge=1, le=200),
    before: datetime | None = Query(None, description="Keyset cursor: last seen ts"),
    action: str | None = Query(None),
    _admin: AdminPrincipal = Depends(require_admin),
) -> list[AuditEntryView]:
    entries = await admin_audit_repo.list_entries(limit=limit, before=before, action=action)
    return [AuditEntryView(**{k: v for k, v in e.items() if k != "_id"}) for e in entries]
