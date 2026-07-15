"""Idempotent bootstrap-admin seeding, run at startup.

Admins are a closed set with NO self-signup. If the ``admin_users`` collection is
empty — a fresh DB, or after a full data wipe — nobody can log in and the portal
reports "session expired". So on startup we CREATE the configured bootstrap
admin(s) **if absent**, self-healing that gap.

Create-if-absent, never upsert: an existing admin is left untouched so a password
changed via the portal CRUD is never reset back to the bootstrap value on the
next restart.

Reads, for the primary and an optional second admin (``_2`` suffix):
  - ``ADMIN_BOOTSTRAP_EMAIL`` + ``ADMIN_BOOTSTRAP_PASSWORD`` (plaintext, hashed here), OR
  - ``ADMIN_BOOTSTRAP_EMAIL`` + ``ADMIN_BOOTSTRAP_PASSWORD_HASH`` (pre-hashed bcrypt).

Best-effort: a failure logs at error but never crashes startup.
"""
from __future__ import annotations

import logging
import os

from app.db.repositories import admin_users_repo
from app.services.auth import hash_password

_logger = logging.getLogger(__name__)


async def seed_bootstrap_admins() -> None:
    """Create configured bootstrap admin(s) if they don't already exist."""
    for suffix in ("", "_2"):
        email = os.environ.get(f"ADMIN_BOOTSTRAP_EMAIL{suffix}", "").strip()
        plaintext = os.environ.get(f"ADMIN_BOOTSTRAP_PASSWORD{suffix}", "")
        pre_hashed = os.environ.get(f"ADMIN_BOOTSTRAP_PASSWORD_HASH{suffix}", "")
        if not email or not (plaintext or pre_hashed):
            continue
        try:
            if await admin_users_repo.get_by_email(email) is not None:
                continue  # already present — never reset a (possibly portal-changed) password
            await admin_users_repo.upsert_admin(
                email=email,
                password_hash=pre_hashed or hash_password(plaintext),
            )
            _logger.info("bootstrap admin seeded (was absent): %s", email)
        except Exception as exc:  # noqa: BLE001 — best-effort; must not crash startup
            _logger.error("bootstrap admin seed failed for %s: %s", email, exc)
