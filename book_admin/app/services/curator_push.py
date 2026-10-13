"""Push admin config changes to the curator's internal ingestion endpoint (D3/D8).

book_admin is the admin control plane's orchestrator: the SPA talks to it, it
validates, then PUSHES to PenMyBook — the curator persists into PenMyBook's own
Mongo and is the source of truth. The admin-side audit row is recorded by the
caller ONLY after this push confirms.

Auth: a short-lived service JWT minted with the shared mesh key, carrying
``sub=service:book_admin`` — the curator's admin routes allowlist exactly that
sub (``require_admin_service``). ``scope=internal`` + ``aud=book_curator`` per
ADR 0002.
"""

from __future__ import annotations

import time
from typing import Any

from urllib.parse import quote

import httpx
import jwt

from app.config.settings import settings

_CURATOR_AUD = "book_curator"


class CuratorPushError(RuntimeError):
    """The push did not confirm — the operator must see this loudly; the
    admin-side mirror/audit MUST NOT record the change as applied."""

    def __init__(self, message: str, status_code: int | None = None):
        super().__init__(message)
        self.status_code = status_code


def _mint_service_jwt() -> str:
    now = int(time.time())
    claims = {
        "iss": settings.SERVICE_JWT_ISSUER,
        "aud": _CURATOR_AUD,
        "sub": settings.ADMIN_SERVICE_SUB,
        "scope": "internal",
        "iat": now,
        "exp": now + settings.SERVICE_JWT_TTL_SECONDS,
    }
    return jwt.encode(claims, settings.SERVICE_JWT_SIGNING_KEY, algorithm="HS256")


async def _request(method: str, path: str, json_body: dict[str, Any] | None = None) -> Any:
    url = f"{settings.CURATOR_INTERNAL_URL.rstrip('/')}{path}"
    headers = {"Authorization": f"Bearer {_mint_service_jwt()}"}
    try:
        async with httpx.AsyncClient(timeout=settings.CURATOR_HTTP_TIMEOUT_S) as client:
            resp = await client.request(method, url, json=json_body, headers=headers)
    except httpx.HTTPError as exc:
        raise CuratorPushError(f"PenMyBook unreachable — config NOT applied: {exc}") from exc
    if resp.status_code >= 400:
        detail: Any
        try:
            detail = resp.json().get("detail", resp.text)
        except ValueError:
            detail = resp.text
        raise CuratorPushError(
            f"PenMyBook rejected the push ({resp.status_code}): {detail}",
            status_code=resp.status_code,
        )
    return resp.json()


async def get_config() -> dict[str, Any]:
    """Current operator levers + runtime overrides + env defaults."""
    return await _request("GET", "/internal/admin-config")


async def push_operations(actor: str, changes: dict[str, Any]) -> dict[str, Any]:
    return await _request(
        "PUT", "/internal/admin-config/operations", {"actor": actor, **changes}
    )


async def get_trusted_sites() -> dict[str, Any]:
    """Every runtime change to the trusted-site list."""
    return await _request("GET", "/internal/trusted-sites")


async def put_trusted_site(actor: str, site: str, action: str, reason: str) -> dict[str, Any]:
    return await _request(
        "PUT", "/internal/trusted-sites", {"actor": actor, "site": site, "action": action, "reason": reason},
    )


async def delete_trusted_site(actor: str, site: str) -> dict[str, Any]:
    return await _request("DELETE", f"/internal/trusted-sites/{quote(site, safe='')}?actor={quote(actor, safe='')}")


async def push_section(actor: str, section: str, values: dict[str, Any]) -> dict[str, Any]:
    return await _request(
        "PUT", f"/internal/admin-config/{section}", {"actor": actor, "values": values}
    )
