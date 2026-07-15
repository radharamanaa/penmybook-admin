# admin/ — PenMyBook Admin Control Plane

Self-contained **admin control plane**, deployed **separately** from the main BookForge/PenMyBook
stack (decision log D1/D2). This directory is the source of truth; it is **mirrored one-way** to a
standalone `penmybook-admin` repo (see "Mirror workflow" below).

**Trust is ONE-WAY:** admin services may reach BookForge's internal endpoints
(admin → BookForge ALLOWED); nothing in the BookForge stack may reference an admin host
(BookForge → admin DENIED — firewalled in prod).

## Services (built by `admin/docker-compose.yml`)

| Service | Stack | Role |
|---|---|---|
| `admin-spa` (`./admin-spa`) | Vite/React frontend | the admin UI (served on :80, published :5174 locally) |
| `book_admin` (`./book_admin`) | Python (uv) | admin API — admin auth (`ADMIN_JWT_SECRET`), reads/writes admin data + curator internal API |
| `admin-api` (`./admin-api`) | Java / Maven (`dev.bookforge.adminapi`) | control-plane API — calls the Java services (entitlements/billing/org) |
| `admin_mongo` | mongo:7 (local only) | parked in prod; prod uses the shared DocDB `bookforge_admin` DB |

All three build contexts are **local to `admin/`** (`./admin-spa`, `./book_admin`, `./admin-api`) —
do NOT reintroduce `../` paths, or the subtree mirror + the EC2 build break.

## How it deploys (prod)

A **dedicated EC2 island** (`infra/terraform/admin_ec2.tf`, currently in the MAIN terraform state —
see the deferred TF-split), reached at **admin.writemybook.me** (Route 53), **never** through
penmybook.ai / the ALB. Boot (`infra/terraform/admin_user_data.sh.tftpl`): install docker → clone
the repo branch → `cd src/admin` → `docker compose -f docker-compose.yml -f docker-compose.prod.yml
up -d --build admin-spa book_admin admin-api` → seed 2 bootstrap admins (`seed_admin_user`) → Caddy
edge with a real cert via Route 53 DNS-01.

⚠️ **The box fetches secrets (`CLONE_PAT`, admin bootstrap creds) from SSM at boot** — it must be
(re)provisioned AFTER `populate-ssm.sh` has run, or the clone gets `REPLACE_ME` and the whole boot
fails silently. (This is why admin died in the 2026-07-14 rebuild.)

## External dependencies (stay coupled — shared data/runtime plane)

- **DocumentDB** — the shared app cluster, `bookforge_admin` DB (`MONGODB_URL` / `ADMIN_MONGODB_URL`).
- **Cloud Map Java services** — `entitlements/billing/org.pmb.local` (prod) / host ports `1810x` (local).
- **`book_curator` internal admin API** (`CURATOR_INTERNAL_URL`) — mesh-authed via `SERVICE_JWT_SIGNING_KEY`.

## Secrets

Real values live in `admin/.env` (**gitignored — never commit it; the mirror must not carry it**) or,
in prod, are SSM-hydrated into the box's env. Keys: `ADMIN_JWT_SECRET`, `SERVICE_JWT_SIGNING_KEY`,
`ADMIN_BOOTSTRAP_EMAIL/_PASSWORD(_HASH)`, `ADMIN_MONGODB_URL` / `MONGODB_URL`.

## Mirror workflow (work HERE, push to the standalone repo)

This repo is the source of truth. Changes are authored here and mirrored **one-way** to
`penmybook-admin`:

```bash
git subtree push --prefix=admin penmybook-admin main
```

Do not author changes directly in `penmybook-admin` — it is a mirror/deploy target.

## Reference

- Full deploy runbook: `docs/deployment/full-teardown-rebuild-runbook.md`
- Subtree migration design: `docs/superpowers/specs/2026-07-15-admin-subtree-migration-design.md`
- Architecture / decision log: `docs/admin-control-plane/` (if present)
