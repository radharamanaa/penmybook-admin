"""Admin feedback triage — list / screenshot blob / resolve (hard-delete).

Paths are WITHOUT the ``/admin`` prefix; ``main.py`` mounts with
``prefix="/admin"``. All handlers require ``require_admin``.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response
from pydantic import BaseModel, Field

from app.db.repositories import admin_audit_repo, feedback_repo
from app.services.auth import AdminPrincipal, require_admin

router = APIRouter()

_NOTE_MAX = 2000


class ResolveRequest(BaseModel):
    note: str = Field(min_length=1, max_length=_NOTE_MAX)


def _client_ip(request: Request) -> str | None:
    return request.client.host if request.client else None


@router.get("/feedback/list")
async def list_feedback(
    page: int = Query(1, ge=1),
    limit: int = Query(20, ge=1, le=100),
    _admin: AdminPrincipal = Depends(require_admin),
) -> dict[str, Any]:
    return await feedback_repo.list_feedback(page=page, limit=limit)


@router.get("/feedback/screenshot/{screenshot_id}")
async def get_screenshot(
    screenshot_id: str,
    _admin: AdminPrincipal = Depends(require_admin),
) -> Response:
    doc = await feedback_repo.get_screenshot(screenshot_id)
    if doc is None:
        raise HTTPException(status_code=404, detail="Screenshot not found")
    data = doc.get("data")
    if data is None:
        raise HTTPException(status_code=404, detail="Screenshot not found")
    # Motor may return bson.Binary — coerce to bytes for Response.
    content = bytes(data)
    content_type = str(doc.get("content_type") or "application/octet-stream")
    return Response(
        content=content,
        media_type=content_type,
        headers={
            "X-Content-Type-Options": "nosniff",
            "Content-Disposition": "inline",
        },
    )


@router.post("/feedback/{feedback_id}/resolve")
async def resolve_feedback(
    feedback_id: str,
    body: ResolveRequest,
    request: Request,
    admin: AdminPrincipal = Depends(require_admin),
) -> dict[str, Any]:
    snapshot = await feedback_repo.get_feedback(feedback_id)
    if snapshot is None:
        raise HTTPException(status_code=404, detail="Feedback not found")

    await admin_audit_repo.record(
        actor_uuid=admin.admin_uuid,
        actor_email=admin.email,
        action="feedback.resolve",
        target=feedback_id,
        old=snapshot,
        new={"resolution_note": body.note},
        outcome="allow",
        client_ip=_client_ip(request),
    )
    await feedback_repo.delete_feedback(feedback_id)
    return {"ok": True, "feedback_id": feedback_id}
