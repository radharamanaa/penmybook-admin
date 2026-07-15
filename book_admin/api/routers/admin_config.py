"""Runtime-config sections — the portal's knob surface (D3/D8/D18).

GET /admin/config                 — operator levers + overrides + env defaults
PUT /admin/config/{section}       — push one section's changed knobs

Sections: tiers | safety | moderation | budgets | cost_rates (the curator's
ingestion endpoint owns the per-key allowlist/bounds and re-validates — this
service neither duplicates the registry nor trusts the SPA).

Ordering guarantee (audit cross-cutting fix): the push to PenMyBook happens
FIRST; the admin-side audit row is written only after the push confirms. A
failed push surfaces as 502/422 and nothing is recorded as applied.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field

from app.db.repositories import admin_audit_repo
from app.services import curator_push
from app.services.auth import AdminPrincipal, require_admin
from app.services.curator_push import CuratorPushError

router = APIRouter()

_SECTIONS = ("tiers", "safety", "moderation", "budgets", "cost_rates")


class SectionPut(BaseModel):
    values: dict[str, Any] = Field(min_length=1)


def _client_ip(request: Request) -> str | None:
    return request.client.host if request.client else None


@router.get("/config")
async def read_config(_admin: AdminPrincipal = Depends(require_admin)) -> dict[str, Any]:
    try:
        return await curator_push.get_config()
    except CuratorPushError as exc:
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=str(exc))


@router.put("/config/{section}")
async def put_config_section(
    section: str,
    body: SectionPut,
    request: Request,
    admin: AdminPrincipal = Depends(require_admin),
) -> dict[str, Any]:
    if section not in _SECTIONS:
        raise HTTPException(status_code=404, detail=f"unknown section '{section}'")
    try:
        result = await curator_push.push_section(admin.email, section, body.values)
    except CuratorPushError as exc:
        # 422 from PenMyBook (validation) passes through as 422 so the SPA can
        # show the bound violation; transport/5xx surfaces as 502.
        if exc.status_code == 422:
            raise HTTPException(status_code=422, detail=str(exc))
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=str(exc))
    # Push confirmed → now (and only now) record the audit row.
    await admin_audit_repo.record(
        actor_uuid=admin.admin_uuid,
        actor_email=admin.email,
        action=f"config.{section}",
        target="runtime_config",
        new=result.get("applied", body.values),
        client_ip=_client_ip(request),
    )
    return result
