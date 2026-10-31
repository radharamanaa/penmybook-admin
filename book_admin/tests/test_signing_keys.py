"""H1 + S3/S4 for book_admin: its own mesh signing key, and refusing dev keys outside DEV_MODE.

See docs/security/mesh-keys.md, app/services/mesh_keys.py and app/config/startup_keys.py.
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config.startup_keys import SigningKeyRefused, refuse_dev_keys_unless_dev

_REAL_SECRET = "a-real-admin-login-secret-at-least-32-bytes"
_DEV_SECRET = "dev-insecure-admin-secret-change-me-32b!"
_SERVICE_ROOT = Path(__file__).resolve().parent.parent


def _resolved_signing_key(own: str | None) -> str:
    """The signing key a fresh book_admin process resolves (settings are read at import time)."""
    env = {**os.environ, "SERVICE_JWT_SIGNING_KEY": "shared-mesh-key-at-least-32-bytes-long!!"}
    env.pop("MESH_KEY_BOOK_ADMIN", None)
    if own is not None:
        env["MESH_KEY_BOOK_ADMIN"] = own
    result = subprocess.run(
        [sys.executable, "-c", "from app.config.settings import settings; print(settings.SERVICE_JWT_SIGNING_KEY)"],
        cwd=_SERVICE_ROOT,
        env=env,
        capture_output=True,
        text=True,
        check=True,
    )
    return result.stdout.strip()


@pytest.mark.parametrize("own", [None, "REPLACE_ME"])
def test_book_admin_signs_with_shared_key_until_its_own_key_is_set(own: str | None) -> None:
    assert _resolved_signing_key(own) == "shared-mesh-key-at-least-32-bytes-long!!"


def test_book_admin_signs_with_its_own_key_once_set() -> None:
    # Fails on the old code, which always signed with the shared key.
    own = "book-admin-own-key-at-least-32-bytes-lo"
    assert _resolved_signing_key(own) == own


@pytest.mark.parametrize("secret", ["", "  ", _DEV_SECRET, "REPLACE_ME"])
def test_outside_dev_mode_a_missing_or_dev_admin_secret_is_refused(secret: str) -> None:
    with pytest.raises(SigningKeyRefused, match="ADMIN_JWT_SECRET"):
        refuse_dev_keys_unless_dev(dev_mode=False, admin_jwt_secret=secret, signing_key=_REAL_SECRET, environ={})


def test_outside_dev_mode_a_dev_value_as_own_mesh_key_is_refused() -> None:
    env = {"MESH_KEY_BOOK_ADMIN": "dev-only-hs256-shared-secret-change-me"}
    with pytest.raises(SigningKeyRefused, match="MESH_KEY_BOOK_ADMIN"):
        refuse_dev_keys_unless_dev(dev_mode=False, admin_jwt_secret=_REAL_SECRET, signing_key=_REAL_SECRET, environ=env)


@pytest.mark.parametrize("own", [None, "REPLACE_ME", "book-admin-own-key-at-least-32-bytes-lo"])
def test_outside_dev_mode_real_keys_start(own: str | None) -> None:
    env = {} if own is None else {"MESH_KEY_BOOK_ADMIN": own}
    refuse_dev_keys_unless_dev(dev_mode=False, admin_jwt_secret=_REAL_SECRET, signing_key=_REAL_SECRET, environ=env)


def test_dev_mode_allows_dev_keys() -> None:
    env = {"MESH_KEY_BOOK_ADMIN": "dev-only-hs256-shared-secret-change-me"}
    refuse_dev_keys_unless_dev(dev_mode=True, admin_jwt_secret=_DEV_SECRET, signing_key="", environ=env)


def test_service_startup_stops_on_a_dev_admin_secret(monkeypatch: pytest.MonkeyPatch) -> None:
    # Fails on the old code: book_admin started on the dev default.
    import main

    monkeypatch.setattr(main.settings, "DEV_MODE", False)
    monkeypatch.setattr(main.settings, "ADMIN_JWT_SECRET", _DEV_SECRET)
    with pytest.raises(SigningKeyRefused):
        with TestClient(main.app):
            pass


def test_a_missing_signing_key_stops_the_service() -> None:
    with pytest.raises(SigningKeyRefused, match="SERVICE_JWT_SIGNING_KEY"):
        refuse_dev_keys_unless_dev(
            dev_mode=False, admin_jwt_secret=_REAL_SECRET, signing_key=" ", environ={}
        )
