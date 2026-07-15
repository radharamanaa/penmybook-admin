"""book_admin configuration loaded from environment variables.

Deliberately a SMALL surface — the admin control plane only needs Mongo, the
closed-login JWT secret/TTL, the single admin frontend origin for CORS, and a
bootstrap admin credential. Generation/publishing knobs live in their own
services. Matches the plain-class pattern used by book_publisher/book_curator so
the settings files read identically side-by-side.
"""

from __future__ import annotations

import os


class Settings:
    # MongoDB — the admin_settings + active_generations singletons live in the
    # same database every other service reads.
    MONGODB_URL: str = os.environ.get("MONGODB_URL", "mongodb://localhost:27017")
    MONGODB_DB_NAME: str = os.environ.get("MONGODB_DB_NAME", "bookforge")

    # Seed value for max_concurrent_generations the first time the singleton is
    # created. After that the live value comes from the admin_settings document
    # (editable from the dashboard) — this env var is only the initial default.
    DEFAULT_MAX_CONCURRENT_GENERATIONS: int = int(
        os.environ.get("DEFAULT_MAX_CONCURRENT_GENERATIONS", "3")
    )

    # Closed-login JWT. No self-signup: admins are seeded into admin_users.
    # Dev default is ≥32 bytes because admin-api's Nimbus decoder enforces the
    # RFC 7518 256-bit HS256 minimum (PyJWT does not) — a shorter secret makes
    # every admin-api call 500 with KeyLengthException.
    ADMIN_JWT_SECRET: str = os.environ.get(
        "ADMIN_JWT_SECRET", "dev-insecure-admin-secret-change-me-32b!"
    )
    ADMIN_JWT_TTL_MIN: int = int(os.environ.get("ADMIN_JWT_TTL_MIN", "60"))
    # aud/iss pin the admin token to THIS surface (D4/D16 of the admin-control-plane
    # decision log): a token minted here must never validate as a mesh service token
    # and vice versa. admin-api validates the same claim set (sub/role/aud/iss/exp).
    ADMIN_JWT_AUDIENCE: str = os.environ.get("ADMIN_JWT_AUDIENCE", "bookforge-admin")
    ADMIN_JWT_ISSUER: str = os.environ.get("ADMIN_JWT_ISSUER", "book_admin")

    # Login brute-force lockout (D7). Counters persist on the admin_users doc so
    # they survive restarts and hold across replicas.
    ADMIN_LOGIN_MAX_FAILURES: int = int(os.environ.get("ADMIN_LOGIN_MAX_FAILURES", "5"))
    ADMIN_LOGIN_LOCKOUT_MIN: int = int(os.environ.get("ADMIN_LOGIN_LOCKOUT_MIN", "15"))

    # Service identity for admin→PenMyBook pushes (D4): minted with the shared
    # mesh key but carrying sub=service:book_admin — PenMyBook's admin-facing
    # /internal/* routes allowlist exactly that sub (require_admin_service).
    SERVICE_JWT_SIGNING_KEY: str = os.environ.get("SERVICE_JWT_SIGNING_KEY", "")
    SERVICE_JWT_ISSUER: str = os.environ.get("SERVICE_JWT_ISSUER", "bookforge-internal")
    SERVICE_JWT_TTL_SECONDS: int = int(os.environ.get("SERVICE_JWT_TTL_SECONDS", "300"))
    ADMIN_SERVICE_SUB: str = "service:book_admin"

    # PenMyBook internal endpoints this service pushes config to (Phase 2+).
    CURATOR_INTERNAL_URL: str = os.environ.get(
        "CURATOR_INTERNAL_URL", "http://book_curator:8000"
    )
    CURATOR_HTTP_TIMEOUT_S: float = float(os.environ.get("CURATOR_HTTP_TIMEOUT_S", "5.0"))

    # Bootstrap admin — seeded by scripts/seed_admin_user.py when present. The
    # password is provided pre-hashed (bcrypt) so a plaintext secret never lives
    # in the environment.
    ADMIN_BOOTSTRAP_EMAIL: str = os.environ.get("ADMIN_BOOTSTRAP_EMAIL", "")
    ADMIN_BOOTSTRAP_PASSWORD_HASH: str = os.environ.get("ADMIN_BOOTSTRAP_PASSWORD_HASH", "")

    # Logging
    LOG_LEVEL: str = os.environ.get("LOG_LEVEL", "INFO")
    LOG_FORMAT: str = os.environ.get("LOG_FORMAT", "text")

    # CORS — ONLY the admin frontend origin. The admin panel is hosted on its
    # own (strictly-authenticated) site, distinct from the user-facing SPA, so
    # this list intentionally does NOT include the app origin.
    ADMIN_FRONTEND_ORIGIN: str = os.environ.get(
        "ADMIN_FRONTEND_ORIGIN", "http://localhost:5174"
    )

    @property
    def cors_origins_list(self) -> list[str]:
        return [o.strip() for o in self.ADMIN_FRONTEND_ORIGIN.split(",") if o.strip()]


settings = Settings()
