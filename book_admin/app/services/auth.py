"""Closed-login auth for the admin control plane.

No self-signup: admins live in the `admin_users` collection (seeded by
scripts/seed_admin_user.py, managed afterwards via the /admin/users CRUD).
Login verifies a bcrypt password hash and issues a short-lived HS256 JWT;
`require_admin` is the FastAPI dependency every data route depends on.

Token model (decision log D7/D16, docs/admin-control-plane/DECISIONS.md):
- `sub` is the admin's stable UUID (NOT the email) — admin-api's audit actor
  parses `sub` as a UUID, and the entitlements OTP contract takes a UUID
  staffUserId. The email rides as an `email` claim for display/audit text.
- `aud`/`iss` pin the token to the admin surface; HS256 only.
- `ver` mirrors the admin doc's `token_version`. Removing an admin, resetting a
  password, or logging out bumps the doc version, so every outstanding token for
  that admin dies on its next use — stateless JWTs with a one-read revocation.
"""

from __future__ import annotations

import asyncio
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

import bcrypt
import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.config.settings import settings
from app.db.repositories import admin_users_repo

_ALGORITHM = "HS256"
_bearer = HTTPBearer(auto_error=False)


@dataclass(frozen=True)
class AdminPrincipal:
    """The authenticated admin identity handed to route handlers."""

    admin_uuid: str
    email: str


def hash_password(plaintext: str) -> str:
    return bcrypt.hashpw(plaintext.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")


async def verify_password(plaintext: str, password_hash: str) -> bool:
    # bcrypt.checkpw is CPU-bound (~100-300ms at default cost). Run it off the
    # event loop so a login can't block all other admin requests (and so it isn't
    # a cheap CPU-exhaustion DoS vector). See the "sync in async blocks loop" rule.
    def _check() -> bool:
        try:
            return bcrypt.checkpw(plaintext.encode("utf-8"), password_hash.encode("utf-8"))
        except (ValueError, TypeError):
            return False

    return await asyncio.to_thread(_check)


def issue_token(
    *, admin_uuid: str, email: str, token_version: int, role: str = "admin"
) -> str:
    now = datetime.now(timezone.utc)
    claims: dict[str, Any] = {
        "sub": admin_uuid,
        "email": email,
        "role": role,
        "ver": token_version,
        "aud": settings.ADMIN_JWT_AUDIENCE,
        "iss": settings.ADMIN_JWT_ISSUER,
        "iat": now,
        "exp": now + timedelta(minutes=settings.ADMIN_JWT_TTL_MIN),
        "jti": uuid.uuid4().hex,
    }
    return jwt.encode(claims, settings.ADMIN_JWT_SECRET, algorithm=_ALGORITHM)


def decode_token(token: str) -> dict[str, Any]:
    """Decode + validate (signature, exp, aud, iss; HS256 only).

    Raises jwt.InvalidTokenError on any problem.
    """
    return jwt.decode(
        token,
        settings.ADMIN_JWT_SECRET,
        algorithms=[_ALGORITHM],
        audience=settings.ADMIN_JWT_AUDIENCE,
        issuer=settings.ADMIN_JWT_ISSUER,
        options={"require": ["exp", "sub", "aud", "iss"]},
    )


def _unauthorized(detail: str) -> HTTPException:
    return HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=detail)


async def require_admin(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> AdminPrincipal:
    """Dependency for admin-only routes.

    Beyond signature/exp/aud/iss, this re-reads the admin doc and compares the
    token's `ver` claim to the live `token_version` — a removed admin or a
    rotated password invalidates every outstanding token immediately. One
    indexed Mongo read per request is the deliberate price (2-operator surface).
    """
    if credentials is None:
        raise _unauthorized("Missing bearer token")
    try:
        payload = decode_token(credentials.credentials)
    except jwt.InvalidTokenError:
        raise _unauthorized("Invalid or expired token")
    if payload.get("role") != "admin":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Admin role required"
        )
    admin_uuid = str(payload.get("sub", ""))
    user = await admin_users_repo.get_by_uuid(admin_uuid)
    if user is None:
        raise _unauthorized("Admin account no longer exists")
    if int(payload.get("ver", -1)) != int(user.get("token_version", 0)):
        raise _unauthorized("Token has been revoked")
    return AdminPrincipal(admin_uuid=admin_uuid, email=str(user.get("email", "")))
