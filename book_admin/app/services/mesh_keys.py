"""One signing key per calling service for internal ("mesh") tokens (H1).

Each service signs the tokens it sends to another service's ``/internal/*`` routes with its OWN
secret, ``MESH_KEY_<CALLER>`` (for example ``MESH_KEY_BOOK_CURATOR``), and names itself in
``sub`` as ``service:<caller>``. A receiver reads that name from the token and checks the
signature with that caller's key, so a service's secret can no longer mint tokens as another
service. The map of who calls whom is ``docs/security/mesh-keys.md``.

Rollout without a switch:

- While a caller's key is unset or still holds the SSM placeholder ``REPLACE_ME``, the caller
  signs with the old shared ``SERVICE_JWT_SIGNING_KEY`` and receivers accept the shared key
  for it (today's behaviour).
- Once a caller's key is real, a receiver that holds that key accepts only that key for it.
- Once EVERY caller a receiver knows has a real key, that receiver stops accepting the shared
  key for any token at all, including a token that names no caller or an unknown one.

Byte-identical mirror in book_curator, book_worker, book_editor, book_publisher
(``app/services/``) and admin/book_admin (``app/services/``). Edit one copy and copy it to the
others; ``scripts/check_shared_drift.py`` fails on drift.
"""

from __future__ import annotations

import os
from collections.abc import Iterable, Mapping
from typing import Final

import jwt

# Terraform seeds every SSM secret with this placeholder until the real value is written.
SSM_SECRET_PLACEHOLDER: Final[str] = "REPLACE_ME"
SUBJECT_PREFIX: Final[str] = "service:"
ENV_PREFIX: Final[str] = "MESH_KEY_"

# Values that are only ever development or test defaults. Outside development a service refuses
# to start with one of these as a signing key (S3/S4).
DEV_KEY_VALUES: Final[frozenset[str]] = frozenset(
    {
        SSM_SECRET_PLACEHOLDER,
        "dev-only-hs256-shared-secret-change-me",
        "integration-suite-hs256-shared-key-32+bytes",
        "dev-insecure-admin-secret-change-me-32b!",
    }
)


def subject_for(caller: str) -> str:
    """The ``sub`` a caller puts in its tokens, e.g. ``service:book_curator``."""
    return f"{SUBJECT_PREFIX}{caller}"


def env_name_for(caller: str) -> str:
    """The environment variable holding a caller's key, e.g. ``MESH_KEY_BOOK_CURATOR``."""
    return f"{ENV_PREFIX}{caller.upper()}"


def configured_key(raw: str | None) -> str:
    """The key as configured, or "" when it is unset, blank or still the SSM placeholder."""
    value = (raw or "").strip()
    return "" if value == SSM_SECRET_PLACEHOLDER else value


def own_key(caller: str, environ: Mapping[str, str] | None = None) -> str:
    """The caller's own configured key, or "" while it has none."""
    env = os.environ if environ is None else environ
    return configured_key(env.get(env_name_for(caller)))


def signing_key(caller: str, shared_key: str, environ: Mapping[str, str] | None = None) -> str:
    """The key ``caller`` signs with: its own key once set, else the shared key."""
    return own_key(caller, environ) or shared_key


def caller_of(subject: object) -> str | None:
    """The caller name in a ``service:<caller>`` subject, or None for anything else."""
    if not isinstance(subject, str) or not subject.startswith(SUBJECT_PREFIX):
        return None
    name = subject[len(SUBJECT_PREFIX) :]
    return name or None


def unverified_caller(token: str) -> str | None:
    """The caller a token claims to be, read WITHOUT checking its signature.

    Used only to pick which key to check the signature with; the full check follows.
    """
    try:
        claims: object = jwt.decode(token, options={"verify_signature": False})
    except jwt.InvalidTokenError:
        return None
    if not isinstance(claims, dict):
        return None
    return caller_of(claims.get("sub"))


def verification_key(
    token: str,
    *,
    known_callers: Iterable[str],
    shared_key: str,
    environ: Mapping[str, str] | None = None,
) -> str:
    """The key to check a mesh token with, or "" when the token must be refused.

    ``known_callers`` are the services allowed to call this receiver (its row in
    docs/security/mesh-keys.md).
    """
    keys = {caller: own_key(caller, environ) for caller in known_callers}
    caller = unverified_caller(token)
    if caller is not None and keys.get(caller):
        return keys[caller]
    if keys and all(keys.values()):
        return ""
    return shared_key
