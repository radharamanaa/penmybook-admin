"""MongoDB connection and client setup (async only — admin has no checkpointer)."""

from motor.motor_asyncio import AsyncIOMotorClient

from app.config.settings import settings

client: AsyncIOMotorClient | None = None


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
    """Get the async MongoDB database instance."""
    return get_motor_client()[settings.MONGODB_DB_NAME]


def close_db():
    global client
    if client is not None:
        client.close()
        client = None
