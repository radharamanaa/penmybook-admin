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

async function request(path, { method = 'GET', body, headers: extraHeaders, timeoutMs } = {}) {
  // Caller headers merge UNDER the defaults: a caller must never be able to blank
  // Authorization or Content-Type by passing them through.
  const headers = { ...(extraHeaders ?? {}), 'Content-Type': 'application/json' }
  if (_token) headers.Authorization = `Bearer ${_token}`
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs ?? _REQUEST_TIMEOUT_MS)
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
    request(`/api/v1/admin/users/${userId}/wallet/top-up`, {
      method: 'POST',
      body: { amountCents, reason },
      headers: { 'X-Step-Up-Token': stepUpToken },
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
    request(`/api/v1/admin/users/${userId}/grant-credits`, {
      method: 'POST',
      body: { amountCents },
      headers: { 'X-Step-Up-Token': stepUpToken },
    }),
  refund: (userId, paymentId, amountCents, reason, stepUpToken, refundRequestId) =>
    request(`/api/v1/admin/users/${userId}/refund`, {
      method: 'POST',
      body: refundRequestId
        ? { paymentId, amountCents, reason, refundRequestId }
        : { paymentId, amountCents, reason },
      headers: { 'X-Step-Up-Token': stepUpToken },
    }),

  // ── refund-request queue (admin-api → billing). Resolve owns a 35s budget because
  // billing fans out one 15s Paddle refund per payment; the default 12s abort would
  // fire while money is still moving and look like a failed refund.
  refundRequestQueue: (status = 'PENDING', limit = 50) =>
    request(`/api/v1/admin/refund-requests?status=${encodeURIComponent(status)}&limit=${limit}`),
  resolveRefundRequest: (id, decision, amountCents, note, stepUpToken) =>
    request(`/api/v1/admin/refund-requests/${id}/resolve`, {
      method: 'POST',
      body: { decision, amountCents, note },
      headers: { 'X-Step-Up-Token': stepUpToken },
      timeoutMs: 35_000,
    }),
  refundResiduals: () => request('/api/v1/admin/refund-residuals'),
  settleRefundResidual: (id, stepUpToken) =>
    request(`/api/v1/admin/refund-residuals/${id}/settle`, {
      method: 'POST',
      headers: { 'X-Step-Up-Token': stepUpToken },
    }),
  // ── pricing (admin-api → entitlements) ──
  getPricing: () => request('/api/v1/admin/pricing'),
  upsertGlobalPricing: (action, valueCents, category = null) =>
    request('/api/v1/admin/pricing', {
      method: 'POST',
      body: category ? { action, valueCents, category } : { action, valueCents },
    }),
  upsertOrgPricing: (orgId, action, valueCents, category = null) =>
    request(`/api/v1/admin/orgs/${orgId}/pricing`, {
      method: 'POST',
      body: category ? { action, valueCents, category } : { action, valueCents },
    }),

  // ── waitlist (admin-api → entitlements) ──
  waitlistQueue: (limit = 50) => request(`/api/v1/admin/waitlist?limit=${limit}`),
  waitlistAdmit: (userId) => request(`/api/v1/admin/waitlist/${userId}/admit`, { method: 'POST', body: {} }),
  waitlistConfig: () => request('/api/v1/admin/waitlist/config'),
  updateWaitlistConfig: (enabled, weeklyApprovalLimit) =>
    request('/api/v1/admin/waitlist/config', {
      method: 'POST', body: { enabled, weeklyApprovalLimit },
    }),
  preAdmitEmail: (email) =>
    request('/api/v1/admin/waitlist/pre-admit', { method: 'POST', body: { email } }),
  listPreAdmits: () => request('/api/v1/admin/waitlist/pre-admits'),

  // ── analytics (book_admin proxy → curator internal) ──
  analyticsConversations: (period = 'last_week') =>
    request(`/py/admin/analytics/conversations?period=${period}`),
  analyticsBooks: (period = 'last_week') => request(`/py/admin/analytics/books?period=${period}`),
  analyticsBooksList: ({ period = 'last_week', fromDate, toDate, page = 1, limit = 20 } = {}) => {
    const qs = new URLSearchParams({ period, page: String(page), limit: String(limit) })
    if (fromDate) qs.set('from_date', fromDate)
    if (toDate) qs.set('to_date', toDate)
    return request(`/py/admin/analytics/books/list?${qs}`)
  },
  analyticsLive: () => request('/py/admin/analytics/live'),
  analyticsGenreCounts: (n = 8) => request(`/py/admin/analytics/genre-counts?n=${n}`),

  // ── feedback triage (book_admin → bookforge.feedback) ──
  feedbackList: ({ page = 1, limit = 20 } = {}) =>
    request(`/py/admin/feedback/list?page=${page}&limit=${limit}`),
  resolveFeedback: (feedbackId, note) =>
    request(`/py/admin/feedback/${encodeURIComponent(feedbackId)}/resolve`, {
      method: 'POST',
      body: { note },
    }),
  // Raw blob fetch — request() does resp.text()→JSON and cannot return bytes.
  fetchFeedbackScreenshotBlob: async (screenshotId) => {
    const headers = {}
    if (_token) headers.Authorization = `Bearer ${_token}`
    const resp = await fetch(
      `/py/admin/feedback/screenshot/${encodeURIComponent(screenshotId)}`,
      { headers },
    )
    if (resp.status === 401) {
      _token = null
      _onAuthExpired()
      throw new ApiError(401, { detail: 'Session expired — sign in again.' })
    }
    if (!resp.ok) {
      const text = await resp.text()
      let body
      try { body = JSON.parse(text) } catch { body = { detail: text } }
      throw new ApiError(resp.status, body)
    }
    return resp.blob()
  },

  // ── spend dashboard: exact-cents money (admin-api → entitlements + billing) ──
  spendGlobal: () => request('/api/v1/admin/spend/global'),
  spendFlows: () => request('/api/v1/admin/spend/flows'),
  spendByBooks: (bookIds) =>
    request('/api/v1/admin/spend/by-books', { method: 'POST', body: bookIds }),
  paymentsHealth: () => request('/api/v1/admin/payments/health'),
  userSpend: (userId) => request(`/api/v1/admin/users/${userId}/spend`),
  orgSpend: (orgId) => request(`/api/v1/admin/orgs/${orgId}/spend`),
  // ── spend dashboard: product usage (book_admin proxy → curator Mongo rollup) ──
  usageLanes: () => request('/py/admin/analytics/usage/lanes'),
  usageByUser: (userId) => request(`/py/admin/analytics/usage/by-user/${userId}`),
  usageByOrg: (orgId) => request(`/py/admin/analytics/usage/by-org/${orgId}`),
}
