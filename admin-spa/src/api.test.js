/**
 * `api.js` contract tests.
 *
 * These exist because the three step-up money writes — wallet top-up, grant
 * credits, refund — used to hand-roll `fetch` in order to attach the
 * `X-Step-Up-Token` header, and in doing so silently lost everything
 * `request()` provides: the 12s abort, the 401 -> onAuthExpired bounce, and
 * the null-token guard (an expired in-memory token sent the literal string
 * `Bearer null`). Every assertion below fails against that older shape.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

import { api, setToken, hasToken, onAuthExpired } from './api.js'

const okResponse = (body = {}) => ({
  status: 200,
  ok: true,
  text: async () => JSON.stringify(body),
})

const errorResponse = (status, body) => ({
  status,
  ok: false,
  text: async () => JSON.stringify(body),
})

/** The three writes that carry a step-up token, and how to invoke each. */
const STEP_UP_WRITES = [
  ['walletTopUp', () => api.walletTopUp('u1', 500, 'admin top-up', 'tok-abc')],
  ['grantCredits', () => api.grantCredits('u1', 500, 'tok-abc')],
  ['refund', () => api.refund('u1', 'pay1', 500, 'admin refund', 'tok-abc'), 65_000],
  ['forceResolveReversed', () => api.forceResolveReversed('u1', 'pay1', 'Stripe refund re_01 confirmed failed', 'tok-abc')],
  ['unsuspend', () => api.unsuspend('u1', 'dispute won in Stripe', 'tok-abc')],
  ['unfreezeWallet', () => api.unfreezeWallet('u1', 'dispute won in Stripe', 'tok-abc')],
  ['unfreezeOrgWallet', () => api.unfreezeOrgWallet('o1', 'dispute won in Stripe', 'tok-abc')],
  ['markRefundPaidByHand', () => api.markRefundPaidByHand('req-1', 'o1', 're_123', null, 'tok-abc')],
]

describe('api.js', () => {
  beforeEach(() => {
    setToken('admin-jwt')
    onAuthExpired(() => {})
    global.fetch = vi.fn().mockResolvedValue(okResponse())
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  describe.each(STEP_UP_WRITES)('%s', (name, call, abortAfterMs = 12_000) => {
    it('sends the step-up token and the admin JWT together', async () => {
      await call()
      const [, init] = global.fetch.mock.calls[0]
      expect(init.headers['X-Step-Up-Token']).toBe('tok-abc')
      expect(init.headers.Authorization).toBe('Bearer admin-jwt')
      expect(init.headers['Content-Type']).toBe('application/json')
    })

    it('bounces to login on 401 instead of surfacing a raw error', async () => {
      // The hand-rolled fetches skipped this entirely, so an expired session
      // looked like a failed refund rather than a signed-out console.
      const expired = vi.fn()
      onAuthExpired(expired)
      global.fetch.mockResolvedValue(errorResponse(401, { detail: 'nope' }))

      await expect(call()).rejects.toMatchObject({ status: 401 })
      expect(expired).toHaveBeenCalledTimes(1)
      expect(hasToken()).toBe(false)
    })

    it('surfaces a step-up rejection as a 403 with its code intact', async () => {
      global.fetch.mockResolvedValue(
        errorResponse(403, { code: 'step_up_invalid' }),
      )
      await expect(call()).rejects.toMatchObject({
        status: 403,
        body: { code: 'step_up_invalid' },
      })
    })

    it('aborts rather than hanging the console forever', async () => {
      vi.useFakeTimers()
      global.fetch.mockImplementation(
        (_url, init) =>
          new Promise((_resolve, reject) => {
            init.signal.addEventListener('abort', () => {
              const err = new Error('aborted')
              err.name = 'AbortError'
              reject(err)
            })
          }),
      )
      // Attach the rejection handler BEFORE advancing timers — otherwise the
      // promise rejects with nothing listening and Node reports it unhandled.
      const settled = expect(call()).rejects.toMatchObject({ status: 0 })
      await vi.advanceTimersByTimeAsync(abortAfterMs)
      await settled
    })
  })

  describe('refund', () => {
    it('outlasts admin-api\'s 60s wait for billing instead of aborting at 12s', async () => {
      vi.useFakeTimers()
      let aborted = false
      global.fetch.mockImplementation(
        (_url, init) =>
          new Promise((_resolve, reject) => {
            init.signal.addEventListener('abort', () => {
              aborted = true
              const err = new Error('aborted')
              err.name = 'AbortError'
              reject(err)
            })
          }),
      )
      const settled = expect(api.refund('u1', 'pay1', 500, 'admin refund', 'tok-abc'))
        .rejects.toMatchObject({ status: 0 })
      await vi.advanceTimersByTimeAsync(60_000)
      expect(aborted).toBe(false)
      await vi.advanceTimersByTimeAsync(5_000)
      await settled
      expect(aborted).toBe(true)
    })

    it('tags a request it stopped waiting for as a client timeout', async () => {
      vi.useFakeTimers()
      global.fetch.mockImplementation(
        (_url, init) =>
          new Promise((_resolve, reject) => {
            init.signal.addEventListener('abort', () => {
              const err = new Error('aborted')
              err.name = 'AbortError'
              reject(err)
            })
          }),
      )
      const settled = expect(api.refund('u1', 'pay1', 500, 'admin refund', 'tok-abc'))
        .rejects.toMatchObject({ status: 0, body: { code: 'CLIENT_TIMEOUT' } })
      await vi.advanceTimersByTimeAsync(65_000)
      await settled
    })

    it('tags a request the browser could not send as a network error', async () => {
      global.fetch.mockRejectedValue(new TypeError('Failed to fetch'))
      await expect(api.refund('u1', 'pay1', 500, 'admin refund', 'tok-abc'))
        .rejects.toMatchObject({ status: 0, body: { code: 'NETWORK_ERROR' } })
    })

    // Q13: the freeze is hashed only when chosen, so the body carries it only then.
    it('sends freezeAccount only when staff chose to freeze', async () => {
      await api.refund('u1', 'pay1', 500, 'admin refund', 'tok-abc', null, false, true)
      await api.refund('u1', 'pay1', 500, 'admin refund', 'tok-abc', null, false, false)
      const [frozen, plain] = global.fetch.mock.calls.map(([, init]) => JSON.parse(init.body))
      expect(frozen.freezeAccount).toBe(true)
      expect(plain).not.toHaveProperty('freezeAccount')
    })
  })

  describe('markRefundPaidByHand', () => {
    it('posts the owner, Stripe refund id and note to the request route', async () => {
      await api.markRefundPaidByHand('req-1', 'org-1', 're_123', 'paid in Stripe', 'tok-abc')
      const [url, init] = global.fetch.mock.calls[0]
      expect(url).toBe('/api/v1/admin/refund-requests/req-1/mark-paid-by-hand')
      expect(init.method).toBe('POST')
      expect(JSON.parse(init.body)).toEqual({
        ownerId: 'org-1', providerRefundId: 're_123', note: 'paid in Stripe',
      })
    })

    it('surfaces billing\'s refusal message verbatim', async () => {
      const copy = "Stripe hasn't told us about this refund yet — try again in a minute."
      global.fetch.mockResolvedValue(errorResponse(409, { code: 'refund_not_seen', message: copy }))
      await expect(api.markRefundPaidByHand('req-1', 'o1', 're_1', null, 'tok-abc'))
        .rejects.toMatchObject({ status: 409, message: copy })
    })
  })

  it('never lets a caller blank the Authorization header', async () => {
    // Caller headers merge UNDER the defaults. A write that could clear
    // Authorization would 401 mid-refund for no visible reason.
    await api.grantCredits('u1', 500, 'tok-abc')
    const [, init] = global.fetch.mock.calls[0]
    expect(init.headers.Authorization).toBe('Bearer admin-jwt')
  })

  it('omits Authorization entirely when there is no token', async () => {
    setToken(null)
    await api.grantCredits('u1', 500, 'tok-abc')
    const [, init] = global.fetch.mock.calls[0]
    // Not `Bearer null` — the old hand-rolled writes sent exactly that.
    expect(init.headers.Authorization).toBeUndefined()
  })

  describe('refund body', () => {
    it('forwards refundRequestId when staff name an approved request', async () => {
      await api.refund('u1', 'pay1', 500, 'admin refund', 'tok-abc', 'req-1')
      const [, init] = global.fetch.mock.calls[0]
      expect(JSON.parse(init.body)).toEqual({
        paymentId: 'pay1',
        amountCents: 500,
        reason: 'admin refund',
        refundRequestId: 'req-1',
      })
    })

    it('omits refundRequestId on a bare payout', async () => {
      await api.refund('u1', 'pay1', 500, 'admin refund', 'tok-abc')
      const [, init] = global.fetch.mock.calls[0]
      expect(JSON.parse(init.body)).toEqual({
        paymentId: 'pay1',
        amountCents: 500,
        reason: 'admin refund',
      })
    })

    it('marks a same-token retry of a payout that may have gone through', async () => {
      await api.refund('u1', 'pay1', 500, 'admin refund', 'tok-abc', 'req-1', true)
      const [, init] = global.fetch.mock.calls[0]
      expect(JSON.parse(init.body)).toEqual({
        paymentId: 'pay1',
        amountCents: 500,
        reason: 'admin refund',
        refundRequestId: 'req-1',
        retryOfUnknownOutcome: true,
      })
      expect(init.headers['X-Step-Up-Token']).toBe('tok-abc')
    })
  })

  describe('forceResolveReversed body', () => {
    it('posts note to the payment restore route', async () => {
      await api.forceResolveReversed('u1', 'pay1', 'Stripe refund re_01 confirmed failed', 'tok-abc')
      const [url, init] = global.fetch.mock.calls[0]
      expect(url).toBe('/api/v1/admin/users/u1/payments/pay1/force-resolve-reversed')
      expect(JSON.parse(init.body)).toEqual({ note: 'Stripe refund re_01 confirmed failed' })
    })
  })

  describe('resolveRefundRequest', () => {
    it('sends the step-up token and owns a 35s abort, not the default 12s', async () => {
      vi.useFakeTimers()
      global.fetch.mockImplementation(
        (_url, init) =>
          new Promise((_resolve, reject) => {
            init.signal.addEventListener('abort', () => {
              const err = new Error('aborted')
              err.name = 'AbortError'
              reject(err)
            })
          }),
      )
      const call = api.resolveRefundRequest('req-1', 'APPROVE', 2000, 'returned to card', 'tok-abc')
      const settled = expect(call).rejects.toMatchObject({ status: 0 })
      await vi.advanceTimersByTimeAsync(12_000)
      expect(global.fetch).toHaveBeenCalledTimes(1)
      const [, init] = global.fetch.mock.calls[0]
      expect(init.headers['X-Step-Up-Token']).toBe('tok-abc')
      expect(init.headers.Authorization).toBe('Bearer admin-jwt')
      await vi.advanceTimersByTimeAsync(23_000)
      await settled
    })
  })

  describe('unrecoveredPayments', () => {
    it('reads the admin-api unrecovered-payments list with the admin JWT', async () => {
      await api.unrecoveredPayments()
      const [url, init] = global.fetch.mock.calls[0]
      expect(url).toContain('/api/v1/admin/payments/unrecovered')
      expect(init.headers.Authorization).toBe('Bearer admin-jwt')
      expect(init.method ?? 'GET').toBe('GET')
    })
  })
})
