"""Pydantic models for the admin control plane.

`AdminSettings` is the operator-facing shape of the singleton `admin_settings`
document. `AdminSettingsUpdate` is the PATCH body — every field optional so a
caller can flip one lever without resending the others.
"""

from __future__ import annotations

from datetime import datetime, timezone

from pydantic import BaseModel, Field

from app.config.settings import settings


class AdminSettings(BaseModel):
    max_concurrent_generations: int = Field(
        ge=1,
        description="Max book runs doing any generation work at once. Over-cap runs wait as pending.",
    )
    llm_quota_available: bool = Field(
        description="When False, Resume is hidden AND the worker stops claiming new full tasks.",
    )
    generation_paused: bool = Field(
        description="When True, the worker stops claiming new tasks; in-flight runs continue.",
    )
    updated_by: str
    updated_at: datetime

    @classmethod
    def from_doc(cls, doc: dict) -> "AdminSettings":
        # Defensive .get() with defaults: the worker's mark_quota_unavailable may
        # create the singleton with ONLY llm_quota_available/updated_* set (it
        # upserts on an outage even if book_admin never seeded the doc). Bare key
        # access would KeyError and 500 the dashboard; fall back to the same safe
        # defaults the worker/curator read paths use.
        return cls(
            max_concurrent_generations=int(
                doc.get("max_concurrent_generations", settings.DEFAULT_MAX_CONCURRENT_GENERATIONS)
            ),
            llm_quota_available=bool(doc.get("llm_quota_available", True)),
            generation_paused=bool(doc.get("generation_paused", False)),
            updated_by=doc.get("updated_by", "system"),
            updated_at=doc.get("updated_at") or datetime.now(timezone.utc),
        )


class AdminSettingsUpdate(BaseModel):
    max_concurrent_generations: int | None = Field(default=None, ge=1)
    llm_quota_available: bool | None = None
    generation_paused: bool | None = None


class AdminStatus(BaseModel):
    settings: AdminSettings
    active_generation_slots: int  # active_generations.count — book runs holding a slot
    pending_tasks: int            # generation_tasks awaiting a worker
    processing_tasks: int         # generation_tasks currently claimed
    counts_by_status: dict[str, int]  # books grouped by lifecycle status


class LoginRequest(BaseModel):
    email: str
    password: str


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in: int  # seconds until the JWT expires


# ───────────────────── admin-account CRUD (portal-managed) ─────────────────────


class AdminUserView(BaseModel):
    admin_uuid: str
    email: str
    role: str = "admin"
    created_at: datetime

    @classmethod
    def from_doc(cls, doc: dict) -> "AdminUserView":
        return cls(
            admin_uuid=doc.get("admin_uuid", ""),
            email=doc.get("email", ""),
            role=doc.get("role", "admin"),
            created_at=doc.get("created_at") or datetime.now(timezone.utc),
        )


class AdminUserCreate(BaseModel):
    email: str = Field(min_length=3, pattern=r"^[^@\s]+@[^@\s]+$")
    password: str = Field(min_length=12, description="Plaintext; hashed server-side.")


class AdminPasswordReset(BaseModel):
    password: str = Field(min_length=12)


class AuditEntryView(BaseModel):
    actor_uuid: str
    actor_email: str
    action: str
    target: str
    outcome: str
    old: dict | None = None
    new: dict | None = None
    detail: str = ""
    client_ip: str | None = None
    ts: datetime
