"""S3/S4: outside development, refuse to start on a missing or well-known development key.

Local docker compose sets ``DEV_MODE=true`` for book_admin; the admin box's prod override sets
``DEV_MODE=false``. Without development mode, book_admin stops at startup when the admin-login
secret ``ADMIN_JWT_SECRET`` or the key it signs with is missing or a known development value, or
when its own mesh key
``MESH_KEY_BOOK_ADMIN`` is set to one (unset or ``REPLACE_ME`` is the normal H1 rollout state).
"""

from __future__ import annotations

import os
from collections.abc import Mapping

from app.services import mesh_keys


class SigningKeyRefused(RuntimeError):
    """A signing key is missing or a development value outside development mode."""


def signing_key_problems(
    *, admin_jwt_secret: str, own_mesh_key: str, signing_key: str
) -> list[str]:
    """What is wrong with the keys, in words an operator can act on (empty = fine)."""
    problems: list[str] = []
    secret = admin_jwt_secret.strip()
    if not secret:
        problems.append("ADMIN_JWT_SECRET is not set")
    elif secret in mesh_keys.DEV_KEY_VALUES:
        problems.append("ADMIN_JWT_SECRET holds a known development value")
    signing = signing_key.strip()
    if not signing:
        problems.append("SERVICE_JWT_SIGNING_KEY is not set")
    elif signing in mesh_keys.DEV_KEY_VALUES:
        problems.append("SERVICE_JWT_SIGNING_KEY holds a known development value")
    if own_mesh_key in mesh_keys.DEV_KEY_VALUES:
        problems.append(
            f"{mesh_keys.env_name_for('book_admin')} holds a known development value"
        )
    return problems


def refuse_dev_keys_unless_dev(
    *,
    dev_mode: bool,
    admin_jwt_secret: str,
    signing_key: str,
    environ: Mapping[str, str] | None = None,
) -> None:
    """Raise :class:`SigningKeyRefused` when not in development mode and a key is unsafe."""
    if dev_mode:
        return
    env = os.environ if environ is None else environ
    problems = signing_key_problems(
        admin_jwt_secret=admin_jwt_secret,
        own_mesh_key=mesh_keys.own_key("book_admin", environ),
        signing_key=signing_key,
    )
    # The signing key above is the own key once set, which would hide a dev shared key.
    if env.get("SERVICE_JWT_SIGNING_KEY", "").strip() in mesh_keys.DEV_KEY_VALUES:
        problems.append("SERVICE_JWT_SIGNING_KEY holds a known development value")
    if problems:
        raise SigningKeyRefused(
            "Refusing to start: "
            + "; ".join(problems)
            + ". Set the real secret, or set DEV_MODE=true for local development only."
        )
