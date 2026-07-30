"""Hermetic test baseline for admin/book_admin.

New file -- book_admin previously had no conftest. Neutralises credentials and applies the
Mongo kill-switch before any ``app.*`` import, since settings are evaluated from
``os.environ`` at class-body import time.

Note on this service's history: its suite appeared broken (6 collection errors,
``ModuleNotFoundError: motor``) because the console-script shebang in ``.venv/bin/pytest``
still pointed at the pre-migration ``book_admin/.venv`` path after the subtree move to
``admin/book_admin/``. The venv itself was fine -- ``.venv/bin/python -m pytest`` always
worked. A fresh ``rm -rf .venv && uv sync`` rewrites the shebang; plain ``uv sync`` does
not.
"""
import os
import sys

# ── testkit bootstrap (must precede every app import) ────────────────────────────
_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = _HERE
while _ROOT != os.path.dirname(_ROOT):
    if os.path.isdir(os.path.join(_ROOT, "testkit")):
        break
    _ROOT = os.path.dirname(_ROOT)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from testkit.hermetic import install_hermetic_env  # noqa: E402

install_hermetic_env(__file__)
