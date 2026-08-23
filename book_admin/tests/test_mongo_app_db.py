"""S2b: Operations histogram reads bookforge via the primary admin URI.

The feedback-scoped DocumentDB user is DENIED on books / generation_tasks /
active_generations. get_app_db must not share that client.
"""
from __future__ import annotations

from unittest.mock import MagicMock, patch

from app.db import mongo as mongo_mod


def test_get_app_db_uses_primary_client_pinned_to_app_db_name() -> None:
    primary = MagicMock(name="primary")
    feedback = MagicMock(name="feedback")
    app_db = object()
    primary.__getitem__.return_value = app_db

    with (
        patch.object(mongo_mod, "get_motor_client", return_value=primary),
        patch.object(mongo_mod, "get_feedback_motor_client", return_value=feedback),
    ):
        got = mongo_mod.get_app_db()

    assert got is app_db
    primary.__getitem__.assert_called_once_with(mongo_mod.settings.APP_MONGODB_DB_NAME)
    feedback.__getitem__.assert_not_called()


def test_get_feedback_db_stays_on_feedback_scoped_client() -> None:
    primary = MagicMock(name="primary")
    feedback = MagicMock(name="feedback")
    feedback_db = object()
    feedback.__getitem__.return_value = feedback_db

    with (
        patch.object(mongo_mod, "get_motor_client", return_value=primary),
        patch.object(mongo_mod, "get_feedback_motor_client", return_value=feedback),
    ):
        got = mongo_mod.get_feedback_db()

    assert got is feedback_db
    feedback.__getitem__.assert_called_once_with(mongo_mod.settings.APP_MONGODB_DB_NAME)
    primary.__getitem__.assert_not_called()
