"""MongoDB connection and client setup (async only — admin has no checkpointer).

Primary client (`get_db`) serves admin_users / admin_audit_log / analytics.
A SECOND Motor client (`get_feedback_db`) is pinned to the app DB
(`APP_MONGODB_DB_NAME=bookforge`) with feedback-scoped creds — least privilege
for feedback triage; never reuse `get_db()` for feedback reads.
"""

from motor.motor_asyncio import AsyncIOMotorClient

from app.config.settings import settings

client: AsyncIOMotorClient | None = None
feedback_client: AsyncIOMotorClient | None = None


def get_motor_client() -> AsyncIOMotorClient:
    global client
    if client is None:
        # Explicit timeouts (Motor defaults serverSelectionTimeoutMS to 30s) so an
        # unreachable Mongo fails fast instead of stalling a uvicorn worker for 30s
        # on the first /admin/* request. Matches the other services' client setup.
        client = AsyncIOMotorClient(
            settings.MONGODB_URL,
            serverSelectionTimeoutMS=5000,
            connectTimeoutMS=5000,
        )
    return client


def get_db():
    """Get the async MongoDB database instance (admin / analytics primary)."""
    return get_motor_client()[settings.MONGODB_DB_NAME]


def get_feedback_motor_client() -> AsyncIOMotorClient:
    """Motor client for the feedback-scoped DocumentDB user.

    Falls back to ``MONGODB_URL`` when ``FEEDBACK_MONGODB_URL`` is empty (local
    docker-compose). Production MUST set the scoped URL via SSM.
    """
    global feedback_client
    if feedback_client is None:
        url = settings.FEEDBACK_MONGODB_URL or settings.MONGODB_URL
        feedback_client = AsyncIOMotorClient(
            url,
            serverSelectionTimeoutMS=5000,
            connectTimeoutMS=5000,
        )
    return feedback_client


def get_feedback_db():
    """App DB (`bookforge`) via the feedback-scoped client — NOT ``get_db()``."""
    return get_feedback_motor_client()[settings.APP_MONGODB_DB_NAME]


def close_db():
    global client, feedback_client
    if client is not None:
        client.close()
        client = None
    if feedback_client is not None:
        feedback_client.close()
        feedback_client = None
