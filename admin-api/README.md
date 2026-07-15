# admin-api

> **Status:** Scaffolded with failing BDD tests (TDD red). No implementation yet.
> **Not under git** — intentionally untracked for now.
> **Config:** all values from [`.env`](./.env) (commented inline). No AWS Secrets/SSM at this stage.

PenMyBook-staff portal backend. Thin facade over entitlements + billing + org with
step-up OTP on every write and an append-only audit row per action.
Full design: [`../specs/05-admin-api.md`](../specs/05-admin-api.md).

> The **admin frontend** is a separate React app (`admin.bookforge.dev`) — not in this folder.

---

## Roles & responsibilities

| # | Responsibility | Notes |
|---|---|---|
| 1 | **User search / view** | aggregate across entitlements + billing + org |
| 2 | **Grant credit** | manual admin grant; step-up OTP required; audited |
| 3 | **Refund** | delegates to billing refund saga; step-up OTP; audited |
| 4 | **Suspend / unsuspend** | flips account status via entitlements; step-up OTP; audited |
| 5 | **Flag developer** | toggles is_developer; step-up OTP; audited |
| 6 | **Audit log** | every write inserts an audit row in the same transaction as the action |

**Security:** `bookforge_admin` role required; TOTP MFA at login; step-up OTP per write; WAF IP allowlist (infra); separate Cognito App Client.

---

## Public API surface (to implement)

| Method | Path | Step-up | Purpose |
|---|---|---|---|
| GET  | `/v1/admin/users` | no | Search |
| GET  | `/v1/admin/users/{id}` | no | Aggregated view |
| POST | `/v1/admin/users/{id}/grant_credit` | yes | Manual grant |
| POST | `/v1/admin/users/{id}/suspend` | yes | Suspend |
| POST | `/v1/admin/users/{id}/unsuspend` | yes | Reactivate |
| POST | `/v1/admin/users/{id}/flag_developer` | yes | Toggle developer |
| POST | `/v1/admin/payments/{id}/refund` | yes | Refund |
| GET  | `/v1/admin/audit` | no | Audit log |

---

## BDD scenario catalog

### `step_up_enforcement.feature`
- a write without a step-up token is rejected
- a write with a valid step-up token proceeds
- a step-up token bound to one action is rejected for another

### `admin_actions.feature`
- granting credit records an audit entry
- suspending a user sets the account status to suspended
- refunding a payment delegates to billing and records an audit entry
- an audit-insert failure rolls back the whole action

---

## How to run

```bash
cd services/admin-api
mvn test     # Cucumber suite → RED (stubs throw "not implemented")
```

---

## Implementation order (red → green)

1. `AdminService` step-up validation → greens `step_up_enforcement.feature`.
2. Wire to entitlements internal endpoints (grant/suspend/flag) → greens `admin_actions.feature`.
3. Audit-in-same-transaction invariant + rollback test.

Target: 90% line coverage at project end.
