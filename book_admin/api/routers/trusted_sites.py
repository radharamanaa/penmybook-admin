"""Trusted sites — add a site to the worker's trusted list, or suspend one, without a deploy.

GET    /admin/trusted-sites          — every runtime change
PUT    /admin/trusted-sites          — add or change one (site, action, reason)
DELETE /admin/trusted-sites/{site}   — remove one

PenMyBook owns the data and re-validates everything; this service neither stores the list nor
trusts the SPA. As with config pushes, the push happens FIRST and the audit row is written only
after it is confirmed.
"""

from __future__ import annotations

from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field

from app.db.repositories import admin_audit_repo
from app.services import curator_push
from app.services.auth import AdminPrincipal, require_admin
from app.services.curator_push import CuratorPushError

router = APIRouter()

_AUDIT_TARGET = "trusted_sites"


class TrustedSitePut(BaseModel):
    site: str = Field(min_length=1, max_length=253)
    action: Literal["trust", "suspend"]
    reason: str = Field(min_length=3, max_length=500)


def _as_http(exc: CuratorPushError) -> HTTPException:
    """PenMyBook's own 404/422 pass through so the SPA can show them; anything else is a 502."""
    if exc.status_code in (404, 422):
        return HTTPException(status_code=exc.status_code, detail=str(exc))
    return HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=str(exc))


def _client_ip(request: Request) -> str | None:
    return request.client.host if request.client else None


@router.get("/trusted-sites")
async def list_trusted_sites(_admin: AdminPrincipal = Depends(require_admin)) -> dict[str, Any]:
    try:
        return await curator_push.get_trusted_sites()
    except CuratorPushError as exc:
        raise _as_http(exc) from exc


@router.put("/trusted-sites")
async def put_trusted_site(
    body: TrustedSitePut, request: Request, admin: AdminPrincipal = Depends(require_admin),
) -> dict[str, Any]:
    try:
        result = await curator_push.put_trusted_site(admin.email, body.site, body.action, body.reason)
    except CuratorPushError as exc:
        raise _as_http(exc) from exc
    await admin_audit_repo.record(
        actor_uuid=admin.admin_uuid, actor_email=admin.email, action=f"trusted_sites.{body.action}",
        target=_AUDIT_TARGET, new=result.get("applied", body.model_dump()), client_ip=_client_ip(request),
    )
    return result


@router.delete("/trusted-sites/{site}")
async def delete_trusted_site(
    site: str, request: Request, admin: AdminPrincipal = Depends(require_admin),
) -> dict[str, Any]:
    try:
        result = await curator_push.delete_trusted_site(admin.email, site)
    except CuratorPushError as exc:
        raise _as_http(exc) from exc
    await admin_audit_repo.record(
        actor_uuid=admin.admin_uuid, actor_email=admin.email, action="trusted_sites.remove",
        target=_AUDIT_TARGET, new=result, client_ip=_client_ip(request),
    )
    return result
