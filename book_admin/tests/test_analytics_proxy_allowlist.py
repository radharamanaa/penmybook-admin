"""Analytics-proxy allowlist: exact paths, dynamic usage/by-* prefixes, traversal rejection.

Regression guard for the Spend-dashboard blocker where the SPA's new
``usage/by-user/{id}`` / ``usage/by-org/{id}`` calls 404'd at this proxy because the
exact-match allowlist could not express a dynamic id segment.
"""

from __future__ import annotations

import pytest

from api.routers.analytics_proxy import _is_allowed

_UUID = "11111111-2222-4333-8444-555555555555"


@pytest.mark.parametrize(
    "path",
    [
        "conversations",
        "books",
        "books/list",
        "live",
        "usage/lanes",
        f"usage/by-user/{_UUID}",
        f"usage/by-org/{_UUID}",
    ],
)
def test_allowed_paths(path: str) -> None:
    assert _is_allowed(path)


@pytest.mark.parametrize(
    "path",
    [
        "",
        "unknown",
        "usage",
        "usage/by-user",          # missing id segment
        "usage/by-user/",         # empty id segment
        f"usage/by-user/{_UUID}/extra",  # extra segment
        "usage/by-user/../../admin",     # traversal shape (multi-segment)
        "usage/by-team/abc",      # unknown usage sub-path
        "books/list/extra",
    ],
)
def test_rejected_paths(path: str) -> None:
    assert not _is_allowed(path)
