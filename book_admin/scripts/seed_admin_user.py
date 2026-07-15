"""One-off admin bootstrap. No self-signup exists, so admins are created here.

Usage:
  # From an already-hashed bootstrap pair in the environment:
  uv run python scripts/seed_admin_user.py

  # Or hash a plaintext password inline (does not persist the plaintext):
  uv run python scripts/seed_admin_user.py admin@penmybook.ai 's3cret'
"""

from __future__ import annotations

import asyncio
import sys

from app.config.settings import settings
from app.db.mongo import close_db
from app.db.repositories import admin_users_repo
from app.services.auth import hash_password


async def _main() -> None:
    if len(sys.argv) == 3:
        email, password_hash = sys.argv[1], hash_password(sys.argv[2])
    elif settings.ADMIN_BOOTSTRAP_EMAIL and settings.ADMIN_BOOTSTRAP_PASSWORD_HASH:
        email, password_hash = (
            settings.ADMIN_BOOTSTRAP_EMAIL,
            settings.ADMIN_BOOTSTRAP_PASSWORD_HASH,
        )
    else:
        print(
            "Provide 'email password' as args, or set ADMIN_BOOTSTRAP_EMAIL + "
            "ADMIN_BOOTSTRAP_PASSWORD_HASH.",
            file=sys.stderr,
        )
        sys.exit(1)

    await admin_users_repo.upsert_admin(email=email, password_hash=password_hash)
    print(f"Seeded admin user: {email}")
    close_db()


if __name__ == "__main__":
    asyncio.run(_main())
