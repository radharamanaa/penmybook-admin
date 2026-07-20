// PenMyBook admin API client.
//
// Two backends behind the same origin (nginx in prod, vite proxy in dev):
//   /py/*  -> book_admin (auth, config sections, admin accounts, audit, status)
//   /api/* -> admin-api  (user search, money ops, pricing, waitlist)
//
// The admin JWT lives IN MEMORY ONLY (decision log D7) — never localStorage:
// an XSS can't exfiltrate what isn't persisted, and a tab refresh simply
// re-prompts the login. Both backends accept the same token.

let _token = null
let _onAuthExpired = () => {}

export function setToken(token) {
  _token = token
}

export function hasToken() {
  return _token != null
}

export function onAuthExpired(handler) {
  _onAuthExpired = handler
}

class ApiError extends Error {
  constructor(status, body) {
    const detail = body && (body.detail ?? body.message ?? body.error)
    super(typeof detail === 'string' ? detail : JSON.stringify(detail ?? body) || `HTTP ${status}`)
    this.status = status
    this.body = body
  }
}

// A hung backend must not freeze the console (the tool you use to fix incidents).
const _REQUEST_TIMEOUT_MS = 12000

async function request(path, { method = 'GET', body } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (_token) headers.Authorization = `Bearer ${_token}`
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), _REQUEST_TIMEOUT_MS)
  let resp
  try {
    resp = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    })
  } catch (err) {
    throw new ApiError(0, {
      detail: err.name === 'AbortError'
        ? 'Request timed out — the backend may be unreachable.'
        : `Network error: ${err.message}`,
    })
  } finally {
    clearTimeout(timer)
  }
  if (resp.status === 401) {
    _token = null
    _onAuthExpired()
    throw new ApiError(401, { detail: 'Session expired — sign in again.' })
  }
  let json = null
  const text = await resp.text()
  if (text) {
    try { json = JSON.parse(text) } catch { json = { detail: text } }
  }
  if (!resp.ok) throw new ApiError(resp.status, json)
  return json
}

export const api = {
  // ── auth + accounts (book_admin) ──
  login: (email, password) =>
    request('/py/admin/auth/login', { method: 'POST', body: { email, password } }),
  logout: () => request('/py/admin/auth/logout', { method: 'POST' }),
  listAdmins: () => request('/py/admin/users'),
  createAdmin: (email, password) =>
    request('/py/admin/users', { method: 'POST', body: { email, password } }),
  deleteAdmin: (adminUuid) => request(`/py/admin/users/${adminUuid}`, { method: 'DELETE' }),
  resetAdminPassword: (adminUuid, password) =>
    request(`/py/admin/users/${adminUuid}/password`, { method: 'POST', body: { password } }),
  listAudit: (limit = 100) => request(`/py/admin/audit?limit=${limit}`),

  // ── operations + config (book_admin → curator push) ──
  getSettings: () => request('/py/admin/settings'),
  patchSettings: (changes) => request('/py/admin/settings', { method: 'PATCH', body: changes }),
  getStatus: () => request('/py/admin/status'),
  getConfig: () => request('/py/admin/config'),
  putConfigSection: (section, values) =>
    request(`/py/admin/config/${section}`, { method: 'PUT', body: { values } }),

  // ── users & money (admin-api) ──
  searchUsers: (email, limit = 50) =>
    request(`/api/v1/admin/users/search?email=${encodeURIComponent(email)}&limit=${limit}`),
  walletTopUp: (userId, amountCents, reason, stepUpToken) =>
    fetch(`/api/v1/admin/users/${userId}/wallet/top-up`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${_token}`,
        'X-Step-Up-Token': stepUpToken,
      },
      body: JSON.stringify({ amountCents, reason }),
    }).then(async (r) => {
      const j = await r.json().catch(() => null)
      if (!r.ok) throw new ApiError(r.status, j)
      return j
    }),
  setPlatformAdmin: (userId, isPlatformAdmin) =>
    request(`/api/v1/admin/users/${userId}`, { method: 'PATCH', body: { isPlatformAdmin } }),
  otpStart: (action, targetUserId, params) =>
    request('/api/v1/admin/otp/start', { method: 'POST', body: { action, targetUserId, params } }),
  otpVerify: (challengeId, code, action, targetUserId, params) =>
    request('/api/v1/admin/otp/verify', {
      method: 'POST', body: { challengeId, code, action, targetUserId, params },
    }),
  grantCredits: (userId, amountCents, stepUpToken) =>
    fetch(`/api/v1/admin/users/${userId}/grant-credits`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${_token}`,
        'X-Step-Up-Token': stepUpToken,
      },
      body: JSON.stringify({ amountCents }),
    }).then(async (r) => {
      const j = await r.json().catch(() => null)
      if (!r.ok) throw new ApiError(r.status, j)
      return j
    }),
  refund: (userId, paymentId, amountCents, reason, stepUpToken) =>
    fetch(`/api/v1/admin/users/${userId}/refund`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${_token}`,
        'X-Step-Up-Token': stepUpToken,
      },
      body: JSON.stringify({ paymentId, amountCents, reason }),
    }).then(async (r) => {
      const j = await r.json().catch(() => null)
      if (!r.ok) throw new ApiError(r.status, j)
      return j
    }),
  // ── pricing (admin-api → entitlements) ──
  getPricing: () => request('/api/v1/admin/pricing'),
  upsertGlobalPricing: (action, valueCents) =>
    request('/api/v1/admin/pricing', { method: 'POST', body: { action, valueCents } }),
  upsertOrgPricing: (orgId, action, valueCents) =>
    request(`/api/v1/admin/orgs/${orgId}/pricing`, { method: 'POST', body: { action, valueCents } }),

  // ── waitlist (admin-api → entitlements) ──
  waitlistQueue: (limit = 50) => request(`/api/v1/admin/waitlist?limit=${limit}`),
  waitlistAdmit: (userId) => request(`/api/v1/admin/waitlist/${userId}/admit`, { method: 'POST', body: {} }),
  waitlistConfig: () => request('/api/v1/admin/waitlist/config'),
  updateWaitlistConfig: (enabled, weeklyApprovalLimit) =>
    request('/api/v1/admin/waitlist/config', {
      method: 'POST', body: { enabled, weeklyApprovalLimit },
    }),

  // ── analytics (book_admin proxy → curator internal) ──
  analyticsConversations: (period = 'last_week') =>
    request(`/py/admin/analytics/conversations?period=${period}`),
  analyticsBooks: (period = 'last_week') => request(`/py/admin/analytics/books?period=${period}`),
  analyticsLive: () => request('/py/admin/analytics/live'),

  // ── spend dashboard: exact-cents money (admin-api → entitlements + billing) ──
  spendGlobal: () => request('/api/v1/admin/spend/global'),
  spendFlows: () => request('/api/v1/admin/spend/flows'),
  paymentsHealth: () => request('/api/v1/admin/payments/health'),
  userSpend: (userId) => request(`/api/v1/admin/users/${userId}/spend`),
  orgSpend: (orgId) => request(`/api/v1/admin/orgs/${orgId}/spend`),
  // ── spend dashboard: product usage (book_admin proxy → curator Mongo rollup) ──
  usageLanes: () => request('/py/admin/analytics/usage/lanes'),
  usageByUser: (userId) => request(`/py/admin/analytics/usage/by-user/${userId}`),
  usageByOrg: (orgId) => request(`/py/admin/analytics/usage/by-org/${orgId}`),
}
