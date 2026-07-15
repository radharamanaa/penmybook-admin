"""Admin control-plane API — all routes require a valid admin JWT.

GET  /admin/settings  — current operator levers
PATCH /admin/settings — change one or more levers (audited with the admin identity)
GET  /admin/status    — live generation counts for the dashboard

Writes go through the PUSH model (D3): PATCH delegates to the curator's
`PUT /internal/admin-config/operations`, which persists field-scoped into
PenMyBook's own Mongo — book_admin no longer writes `admin_settings` directly.
Reads (settings/status) ALSO come from the curator GET (/internal/admin-config),
the source of truth. In the separate-DB topology (AWS) book_admin's own Mongo is
NOT the curator/worker DB, and the worker co-writes `llm_quota_available` into the
curator copy on an outage — so reading book_admin's local copy is a split-brain
that made pushed levers (pause / concurrency) look like silent no-ops in the UI.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request, status

from app.db.repositories import admin_audit_repo, generation_stats_repo
from app.models.admin_models import AdminSettings, AdminSettingsUpdate, AdminStatus
from app.services import curator_push
from app.services.auth import AdminPrincipal, require_admin
from app.services.curator_push import CuratorPushError

router = APIRouter()


async def _operations_from_curator() -> AdminSettings:
    """Read the effective operator levers from PenMyBook (curator) — the source
    of truth — NOT book_admin's own Mongo (a split-brain in the separate-DB
    topology). Raises HTTP 502 if the curator is unreachable so an outage is
    loud, not a silently-stale dashboard."""
    try:
        config = await curator_push.get_config()
    except CuratorPushError as exc:
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=str(exc))
    return AdminSettings.from_doc(config.get("operations") or {})


@router.get("/settings", response_model=AdminSettings)
async def read_settings(_admin: AdminPrincipal = Depends(require_admin)) -> AdminSettings:
    return await _operations_from_curator()


@router.patch("/settings", response_model=AdminSettings)
async def patch_settings(
    body: AdminSettingsUpdate,
    request: Request,
    admin: AdminPrincipal = Depends(require_admin),
) -> AdminSettings:
    before = await _operations_from_curator()
    changed = body.model_dump(exclude_none=True)
    if not changed:
        return before
    # PUSH first (PenMyBook validates + persists, source of truth)...
    try:
        await curator_push.push_operations(admin.email, changed)
    except CuratorPushError as exc:
        if exc.status_code == 422:
            raise HTTPException(status_code=422, detail=str(exc))
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=str(exc))
    # ...audit only after the push confirmed.
    await admin_audit_repo.record(
        actor_uuid=admin.admin_uuid,
        actor_email=admin.email,
        action="config.operations",
        target="admin_settings",
        old={k: getattr(before, k) for k in changed},
        new=changed,
        client_ip=request.client.host if request.client else None,
    )
    # Read back from the source of truth so the response reflects the push.
    return await _operations_from_curator()


@router.get("/status", response_model=AdminStatus)
async def read_status(_admin: AdminPrincipal = Depends(require_admin)) -> AdminStatus:
    current = await _operations_from_curator()
    stats = await generation_stats_repo.get_status_counts()
    return AdminStatus(settings=current, **stats)
