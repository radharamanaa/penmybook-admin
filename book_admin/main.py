"""book_admin FastAPI entrypoint.

Operator control-plane service. Owns the singleton `admin_settings` document
(the only writer) and seeds the `active_generations` slot counter the worker
uses to enforce the concurrency cap. The worker and curator are readers +
enforcers; this service is the control surface + the admin dashboard's backend.

Routers:
- /health            — liveness probe
- /admin/auth/login  — closed login (no self-signup), issues a short-lived JWT
- /admin/settings    — GET current levers / PATCH to change them (require_admin)
- /admin/status      — live generation counts (require_admin)
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config.settings import settings
from app.db.mongo import close_db
from api.routers.admin import router as admin_router
from api.routers.admin_config import router as admin_config_router
from api.routers.trusted_sites import router as trusted_sites_router
from api.routers.admin_users import router as admin_users_router
from api.routers.analytics_proxy import router as analytics_proxy_router
from api.routers.auth import router as auth_router
from api.routers.feedback import router as feedback_router
from api.routers.health import router as health_router

_logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    # Self-heal admin login: create the configured bootstrap admin(s) if the
    # admin_users collection is empty (fresh DB / after a data wipe). Idempotent
    # create-if-absent — never resets an existing admin's password. Best-effort.
    from app.services.bootstrap import seed_bootstrap_admins

    await seed_bootstrap_admins()
    # Motor connects lazily on first get_db() call. Settings/counter singletons
    # are seeded on first access by their repos, so there is no other startup write.
    yield
    close_db()


app = FastAPI(title="book_admin", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins_list,
    allow_methods=["GET", "POST", "PATCH", "DELETE"],
    allow_headers=["*"],
)

app.include_router(health_router)
app.include_router(auth_router, prefix="/admin/auth")
app.include_router(admin_router, prefix="/admin")
app.include_router(admin_users_router, prefix="/admin")
app.include_router(admin_config_router, prefix="/admin")
app.include_router(trusted_sites_router, prefix="/admin")
app.include_router(analytics_proxy_router, prefix="/admin")
app.include_router(feedback_router, prefix="/admin")
