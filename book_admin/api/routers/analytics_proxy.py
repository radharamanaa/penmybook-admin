"""Analytics proxy — the SPA's window into the curator's internal analytics (D15).

The operator's browser only ever talks to the admin VM; this router relays the
read-only analytics GETs to the curator's service-auth mount
(``/internal/admin/analytics/*``, caller-allowlisted to this service's
``sub=service:book_admin``). Query params pass through verbatim.
"""

from __future__ import annotations

import time
from typing import Any

import httpx
import jwt
from fastapi import APIRouter, Depends, HTTPException, Request, status

from app.config.settings import settings
from app.services.auth import AdminPrincipal, require_admin

router = APIRouter()

_ALLOWED = frozenset({"conversations", "books", "books/list", "live", "usage/lanes"})

# Paths carrying a dynamic trailing id segment (exact-match can't express them).
# The suffix must be a single non-empty path segment — no '/' and no traversal.
_ALLOWED_PREFIXES = ("usage/by-user/", "usage/by-org/")


def _is_allowed(path: str) -> bool:
    if path in _ALLOWED:
        return True
    for prefix in _ALLOWED_PREFIXES:
        if path.startswith(prefix):
            suffix = path[len(prefix):]
            return bool(suffix) and "/" not in suffix
    return False


def _mint_service_jwt() -> str:
    now = int(time.time())
    return jwt.encode(
        {
            "iss": settings.SERVICE_JWT_ISSUER,
            "aud": "book_curator",
            "sub": settings.ADMIN_SERVICE_SUB,
            "scope": "internal",
            "iat": now,
            "exp": now + settings.SERVICE_JWT_TTL_SECONDS,
        },
        settings.SERVICE_JWT_SIGNING_KEY,
        algorithm="HS256",
    )


@router.get("/analytics/{path:path}")
async def proxy_analytics(
    path: str, request: Request, _admin: AdminPrincipal = Depends(require_admin)
) -> Any:
    if not _is_allowed(path):
        raise HTTPException(status_code=404, detail=f"unknown analytics path '{path}'")
    url = f"{settings.CURATOR_INTERNAL_URL.rstrip('/')}/internal/admin/analytics/{path}"
    headers = {"Authorization": f"Bearer {_mint_service_jwt()}"}
    try:
        async with httpx.AsyncClient(timeout=settings.CURATOR_HTTP_TIMEOUT_S) as client:
            resp = await client.get(url, params=dict(request.query_params), headers=headers)
    except httpx.HTTPError as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"PenMyBook analytics unreachable: {exc}",
        )
    if resp.status_code >= 400:
        raise HTTPException(status_code=resp.status_code, detail=resp.text[:300])
    return resp.json()
