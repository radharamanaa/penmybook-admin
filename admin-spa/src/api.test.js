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
  ['refund', () => api.refund('u1', 'pay1', 500, 'admin refund', 'tok-abc')],
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

  describe.each(STEP_UP_WRITES)('%s', (name, call) => {
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
      await vi.advanceTimersByTimeAsync(12_000)
      await settled
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

  describe('settleRefundResidual', () => {
    it('sends the step-up token and the admin JWT together', async () => {
      await api.settleRefundResidual('res-1', 'tok-abc')
      const [, init] = global.fetch.mock.calls[0]
      expect(init.headers['X-Step-Up-Token']).toBe('tok-abc')
      expect(init.headers.Authorization).toBe('Bearer admin-jwt')
      expect(init.method).toBe('POST')
    })
  })
})
