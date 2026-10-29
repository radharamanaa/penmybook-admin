import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

const otpStart = vi.hoisted(() => vi.fn())
const otpVerify = vi.hoisted(() => vi.fn())
const refund = vi.hoisted(() => vi.fn())

vi.mock('../api.js', () => ({
  api: {
    searchUsers: async () => [],
    otpStart: (...a) => otpStart(...a),
    otpVerify: (...a) => otpVerify(...a),
    refund: (...a) => refund(...a),
    accountState: async () => ({ accountStatus: 'ACTIVE', walletFrozen: false, unrecoveredCents: 0 }),
  },
}))

import Users from './Users.jsx'

const REQUEST_ID = '22222222-2222-2222-2222-222222222222'
const PAYMENT_ID = '11111111-1111-1111-1111-111111111111'
const TOKEN = 'tok-first-approval'
const RETRY = /retry with the same approval/i
// TTL 300 s minus the 10 s safety margin.
const USABLE_MS = 290_000

const COPY = {
  outcomeUnknown: 'The refund may have gone through — check the payment before retrying.',
  retryTimedOut:
    'The retry timed out too — the refund may have gone through. Check the payment before retrying.',
  notSent: 'Billing could not be reached — nothing was sent. It is safe to retry.',
  browserNetwork:
    'The admin server may have received the refund. Retry with the same approval — that cannot '
    + "pay twice. Don't start a new code until you have checked the payment.",
  replayed: 'That refund had already gone through — nothing more was paid.',
  paid: `Refund issued for payment ${PAYMENT_ID}: 874¢ (plus any tax refunded with it).`,
  expired: 'The approval has expired — check the payment in Stripe, then start a new refund.',
  approvalUsed:
    'This approval was already used — the first attempt may still be going through. '
    + 'Wait a minute and check the payment in Stripe before starting a new refund.',
  stepUpRefused: 'The approval was refused — start the refund again with a new code.',
  retryNotSent:
    'This retry did not reach billing — the first attempt may still have gone through. '
    + 'Check the payment, or retry with the same approval (it cannot pay twice).',
  billingError:
    'Billing hit an error — the refund may have gone through. Check the payment before retrying.',
  stripeDeclined: 'Stripe declined the refund — nothing was paid.',
  proxyError:
    'The admin server did not answer properly — the refund may have gone through. '
    + 'Check the payment before retrying.',
  readyForNewRefund: 'You can start a new refund now — check the payment in Stripe first.',
  retryNetwork:
    'The admin server may have received this retry, and the first attempt may still have gone '
    + 'through. Retry with the same approval — that cannot pay twice. '
    + "Don't start a new code until you have checked the payment.",
  shortfall: `Refunded 500¢ of the 874¢ asked for payment ${PAYMENT_ID} (plus any tax refunded `
    + 'with it) — the rest was already spent, and the refund request is now closed.',
  nothingLeft: `Nothing was refunded for payment ${PAYMENT_ID} — the 874¢ asked was already spent, `
    + 'and the refund request is now closed.',
}

function apiError(status, body, message = 'request failed') {
  return Object.assign(new Error(message), { status, body })
}

const timedOut = () => apiError(504, { code: 'REFUND_OUTCOME_UNKNOWN' })

let user

function renderUsers() {
  return render(
    <MemoryRouter initialEntries={[
      `/users?refundRequestId=${REQUEST_ID}&ownerType=USER&ownerId=u1&ownerEmail=a@b.co`
        + `&paymentId=${PAYMENT_ID}&amountCents=874`,
    ]}>
      <Users />
    </MemoryRouter>,
  )
}

async function payOut() {
  renderUsers()
  await user.click(screen.getByRole('button', { name: /start otp refund/i }))
  await user.type(screen.getByLabelText(/otp code/i), '123456')
  await user.click(screen.getByRole('button', { name: /verify & execute/i }))
}

const firstSend = ['u1', PAYMENT_ID, 874, 'admin refund', TOKEN, REQUEST_ID, false]
const retrySend = ['u1', PAYMENT_ID, 874, 'admin refund', TOKEN, REQUEST_ID, true]

describe('Users payout: retry with the same approval', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    otpStart.mockReset()
    otpVerify.mockReset()
    refund.mockReset()
    otpStart.mockResolvedValue({ challengeId: 'ch-1' })
    otpVerify.mockResolvedValue({ verified: true, stepUpToken: TOKEN })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('offers the retry on an unknown outcome, shows the time left and locks the refund fields', async () => {
    refund.mockRejectedValueOnce(timedOut())
    await payOut()

    expect(await screen.findByText(COPY.outcomeUnknown)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: RETRY })).toBeEnabled()
    expect(screen.getByText(/this approval is still valid for 4:(4\d|50)\./i)).toBeInTheDocument()
    expect(screen.getByLabelText(/refund — payment id/i)).toBeDisabled()
    expect(screen.getByLabelText(/refund amount \(cents\)/i)).toBeDisabled()
    expect(screen.getByLabelText(/refund request id/i)).toBeDisabled()
    expect(screen.getByRole('button', { name: /start otp refund/i })).toBeDisabled()
    expect(screen.queryByLabelText(/otp code/i)).toBeNull()
  })

  it('resends the identical request with the same token and says a replay paid nothing', async () => {
    refund.mockRejectedValueOnce(timedOut())
      .mockResolvedValueOnce({ idempotentReplay: true, requestedCents: 874, walletDebitedCents: 874 })
    await payOut()
    await user.click(await screen.findByRole('button', { name: RETRY }))

    expect(await screen.findByText(COPY.replayed)).toBeInTheDocument()
    expect(refund).toHaveBeenCalledTimes(2)
    expect(refund.mock.calls[0]).toEqual(firstSend)
    expect(refund.mock.calls[1]).toEqual(retrySend)
    expect(otpStart).toHaveBeenCalledTimes(1)
    expect(otpVerify).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('button', { name: RETRY })).toBeNull()
    expect(screen.getByLabelText(/refund amount \(cents\)/i)).toBeEnabled()
  })

  it('resends the stored request even if the fields were edited before the timeout', async () => {
    refund.mockRejectedValueOnce(timedOut()).mockResolvedValueOnce({ requestedCents: 874, walletDebitedCents: 874 })
    renderUsers()
    await user.click(screen.getByRole('button', { name: /start otp refund/i }))
    const amount = screen.getByLabelText(/refund amount \(cents\)/i)
    await user.clear(amount)
    await user.type(amount, '99999')
    await user.type(screen.getByLabelText(/otp code/i), '123456')
    await user.click(screen.getByRole('button', { name: /verify & execute/i }))
    await user.click(await screen.findByRole('button', { name: RETRY }))

    expect(await screen.findByText(COPY.paid)).toBeInTheDocument()
    expect(refund.mock.calls[1]).toEqual(retrySend)
  })

  it('shows the normal success with the amount when the retry pays now', async () => {
    refund.mockRejectedValueOnce(timedOut())
      .mockResolvedValueOnce({ idempotentReplay: false, requestedCents: 874, walletDebitedCents: 874 })
    await payOut()
    await user.click(await screen.findByRole('button', { name: RETRY }))

    expect(await screen.findByText(COPY.paid)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: RETRY })).toBeNull()
  })

  it('names billing\'s clamped amount, not the asked one, on success', async () => {
    refund.mockResolvedValueOnce({ idempotentReplay: false, requestedCents: 500, walletDebitedCents: 500 })
    await payOut()
    expect(await screen.findByText(`Refund issued for payment ${PAYMENT_ID}: 500¢ (plus any tax refunded with it).`)).toBeInTheDocument()
  })

  it('names what was paid, not the ask, on a shortfall payout, and says the request closed', async () => {
    refund.mockResolvedValueOnce({ idempotentReplay: false, requestedCents: 874, walletDebitedCents: 500 })
    await payOut()
    expect(await screen.findByText(COPY.shortfall)).toBeInTheDocument()
    expect(screen.queryByText(/Refund issued/)).toBeNull()
  })

  it('says nothing was refunded when the whole ask was already spent', async () => {
    refund.mockResolvedValueOnce({ idempotentReplay: false, requestedCents: 874, walletDebitedCents: 0 })
    await payOut()
    expect(await screen.findByText(COPY.nothingLeft)).toBeInTheDocument()
  })

  it('says a first-try replay paid nothing', async () => {
    refund.mockResolvedValueOnce({ idempotentReplay: true, requestedCents: 874, walletDebitedCents: 874 })
    await payOut()
    expect(await screen.findByText(COPY.replayed)).toBeInTheDocument()
  })

  it('offers the same button again when the retry times out too', async () => {
    refund.mockRejectedValueOnce(timedOut()).mockRejectedValueOnce(timedOut())
      .mockResolvedValueOnce({ idempotentReplay: true })
    await payOut()
    await user.click(await screen.findByRole('button', { name: RETRY }))

    expect(await screen.findByText(COPY.retryTimedOut)).toBeInTheDocument()
    expect(screen.getByText(/this approval is still valid for 4:\d\d\./i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: RETRY }))
    expect(await screen.findByText(COPY.replayed)).toBeInTheDocument()
    expect(refund.mock.calls.every((call) => call[4] === TOKEN)).toBe(true)
  })

  it('treats a browser timeout on the retry as unknown too', async () => {
    refund.mockRejectedValueOnce(timedOut())
      .mockRejectedValueOnce(apiError(0, { code: 'CLIENT_TIMEOUT' }))
    await payOut()
    await user.click(await screen.findByRole('button', { name: RETRY }))
    expect(await screen.findByText(COPY.retryTimedOut)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: RETRY })).toBeEnabled()
  })

  it('hides the button and says the approval expired once the TTL runs out', async () => {
    refund.mockRejectedValueOnce(timedOut())
    await payOut()
    await screen.findByRole('button', { name: RETRY })

    await act(async () => { vi.advanceTimersByTime(USABLE_MS) })

    expect(screen.getByText(COPY.expired)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: RETRY })).toBeNull()
    expect(screen.getByLabelText(/refund amount \(cents\)/i)).toBeEnabled()
    expect(screen.getByRole('button', { name: /start otp refund/i })).toBeEnabled()
  })

  it('counts the TTL from the OTP verification, not from the timeout', async () => {
    let failFirst
    refund.mockImplementationOnce(() => new Promise((_resolve, reject) => { failFirst = reject }))
    await payOut()
    await act(async () => { vi.advanceTimersByTime(60_000) })
    await act(async () => { failFirst(timedOut()) })

    expect(await screen.findByText(/this approval is still valid for 3:(4\d|50)\./i)).toBeInTheDocument()
    await act(async () => { vi.advanceTimersByTime(USABLE_MS - 60_000) })
    expect(screen.queryByRole('button', { name: RETRY })).toBeNull()
  })

  it('sends nothing when the clock passed the TTL before the panel ticked', async () => {
    refund.mockRejectedValueOnce(timedOut())
    await payOut()
    const button = await screen.findByRole('button', { name: RETRY })
    vi.setSystemTime(Date.now() + USABLE_MS)

    await user.click(button)

    expect(await screen.findByText(COPY.expired)).toBeInTheDocument()
    expect(refund).toHaveBeenCalledTimes(1)
  })

  it('sends one request however often the button is clicked, and shows it busy', async () => {
    let settle
    refund.mockRejectedValueOnce(timedOut())
      .mockImplementationOnce(() => new Promise((resolve) => { settle = resolve }))
    await payOut()
    const button = await screen.findByRole('button', { name: RETRY })

    await user.dblClick(button)
    await user.click(button)

    expect(refund).toHaveBeenCalledTimes(2)
    const busy = screen.getByRole('button', { name: /retrying…/i })
    expect(busy).toBeDisabled()
    expect(busy).toHaveAttribute('aria-busy', 'true')
    await act(async () => { settle({ idempotentReplay: true }) })
    expect(await screen.findByText(COPY.replayed)).toBeInTheDocument()
    expect(refund).toHaveBeenCalledTimes(2)
  })

  it('says the approval was used up when billing finds it spent', async () => {
    refund.mockRejectedValueOnce(timedOut())
      .mockRejectedValueOnce(apiError(409, { code: 'REFUND_APPROVAL_USED' }))
    await payOut()
    await user.click(await screen.findByRole('button', { name: RETRY }))

    expect(await screen.findByText(COPY.approvalUsed)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: RETRY })).toBeNull()
  })

  it('keeps every refund control locked after "already used" until the approval window ends', async () => {
    refund.mockRejectedValueOnce(timedOut())
      .mockRejectedValueOnce(apiError(409, { code: 'REFUND_APPROVAL_USED' }))
    await payOut()
    await user.click(await screen.findByRole('button', { name: RETRY }))
    await screen.findByText(COPY.approvalUsed)

    expect(screen.getByText(/you can start a new refund in 4:\d\d\./i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /start otp refund/i })).toBeDisabled()
    expect(screen.getByLabelText(/refund amount \(cents\)/i)).toBeDisabled()
    expect(screen.queryByLabelText(/otp code/i)).toBeNull()

    await act(async () => { vi.advanceTimersByTime(USABLE_MS - 5_000) })
    expect(screen.getByText(COPY.approvalUsed)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /start otp refund/i })).toBeDisabled()

    await act(async () => { vi.advanceTimersByTime(5_000) })
    expect(screen.getByText(COPY.readyForNewRefund)).toBeInTheDocument()
    expect(screen.queryByText(COPY.approvalUsed)).toBeNull()
    expect(screen.queryByText(COPY.expired)).toBeNull()
    expect(screen.getByRole('button', { name: /start otp refund/i })).toBeEnabled()
    expect(refund).toHaveBeenCalledTimes(2)
  })

  it('treats a proxy 5xx without an admin-api code as an unknown outcome, never showing its HTML', async () => {
    refund.mockRejectedValueOnce(apiError(502, { detail: '<html><body>Bad Gateway</body></html>' },
      '<html><body>Bad Gateway</body></html>'))
      .mockRejectedValueOnce(apiError(504, { detail: '<html>Gateway Time-out</html>' },
        '<html>Gateway Time-out</html>'))
    await payOut()

    expect(await screen.findByText(COPY.proxyError)).toBeInTheDocument()
    expect(screen.queryByText(/bad gateway/i)).toBeNull()
    await user.click(screen.getByRole('button', { name: RETRY }))
    await act(async () => {})
    expect(screen.getByText(COPY.proxyError)).toBeInTheDocument()
    expect(screen.queryByText(/gateway time-out/i)).toBeNull()
    expect(refund.mock.calls[1]).toEqual(retrySend)
  })

  it('shows admin-api\'s coded 502 messages rather than the proxy copy', async () => {
    refund.mockRejectedValueOnce(apiError(502, { code: 'REFUND_STRIPE_REFUSED' },
      'Stripe refused the refund — nothing was paid.'))
    await payOut()

    expect(await screen.findByText('Stripe refused the refund — nothing was paid.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: RETRY })).toBeNull()
  })

  it('says the approval expired when admin-api refuses the same token on the retry', async () => {
    refund.mockRejectedValueOnce(timedOut())
      .mockRejectedValueOnce(apiError(403, { code: 'step_up_invalid' }))
    await payOut()
    await user.click(await screen.findByRole('button', { name: RETRY }))

    expect(await screen.findByText(COPY.expired)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: RETRY })).toBeNull()
  })

  it('shows billing\'s refusal verbatim on the retry', async () => {
    refund.mockRejectedValueOnce(timedOut())
      .mockRejectedValueOnce(apiError(502, { code: 'DOWNSTREAM_REJECTED',
        message: 'Nothing left to refund on this payment.' }, 'Nothing left to refund on this payment.'))
    await payOut()
    await user.click(await screen.findByRole('button', { name: RETRY }))

    expect(await screen.findByText('Nothing left to refund on this payment.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: RETRY })).toBeNull()
  })

  it('shows billing\'s refusal verbatim on the first try with no retry offered', async () => {
    refund.mockRejectedValueOnce(apiError(502, { code: 'DOWNSTREAM_REJECTED' }, 'Payment was charged back.'))
    await payOut()

    expect(await screen.findByText('Payment was charged back.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: RETRY })).toBeNull()
  })

  it('says nothing was sent when billing could not be reached, and offers the retry', async () => {
    refund.mockRejectedValueOnce(apiError(503, { code: 'REFUND_NOT_SENT' }))
      .mockResolvedValueOnce({ requestedCents: 874, walletDebitedCents: 874 })
    await payOut()

    expect(await screen.findByText(COPY.notSent)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: RETRY }))
    expect(await screen.findByText(COPY.paid)).toBeInTheDocument()
    // Nothing reached billing the first time, so this is not a retry of a possible refund.
    expect(refund.mock.calls[1]).toEqual(firstSend)
  })

  it('keeps the plain not-sent copy when no attempt could have landed', async () => {
    refund.mockRejectedValueOnce(apiError(503, { code: 'REFUND_NOT_SENT' }))
      .mockRejectedValueOnce(apiError(503, { code: 'REFUND_NOT_SENT' }))
    await payOut()
    await user.click(await screen.findByRole('button', { name: RETRY }))
    await act(async () => {})
    expect(screen.getByText(COPY.notSent)).toBeInTheDocument()
    expect(screen.queryByText(COPY.retryNotSent)).toBeNull()
  })

  it('warns the first attempt may have landed when a retry does not reach billing', async () => {
    refund.mockRejectedValueOnce(timedOut())
      .mockRejectedValueOnce(apiError(503, { code: 'REFUND_NOT_SENT' }))
      .mockRejectedValueOnce(apiError(503, { code: 'REFUND_NOT_SENT' }))
    await payOut()
    await user.click(await screen.findByRole('button', { name: RETRY }))
    expect(await screen.findByText(COPY.retryNotSent)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: RETRY })).toBeEnabled()
    // The earlier timeout still counts after a not-sent retry.
    await user.click(screen.getByRole('button', { name: RETRY }))
    await act(async () => {})
    expect(screen.getByText(COPY.retryNotSent)).toBeInTheDocument()
    expect(refund.mock.calls[2]).toEqual(retrySend)
  })

  it('warns the first attempt may have landed when a retry cannot reach admin-api', async () => {
    refund.mockRejectedValueOnce(timedOut())
      .mockRejectedValueOnce(apiError(0, { code: 'NETWORK_ERROR' }))
    await payOut()
    await user.click(await screen.findByRole('button', { name: RETRY }))
    expect(await screen.findByText(COPY.retryNetwork)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: RETRY })).toBeEnabled()
  })

  it('counts a browser network failure as possibly landed for the next retry', async () => {
    refund.mockRejectedValueOnce(apiError(0, { code: 'NETWORK_ERROR' }))
      .mockRejectedValueOnce(apiError(503, { code: 'REFUND_NOT_SENT' }))
    await payOut()
    await user.click(await screen.findByRole('button', { name: RETRY }))
    expect(await screen.findByText(COPY.retryNotSent)).toBeInTheDocument()
    expect(refund.mock.calls[1]).toEqual(retrySend)
  })

  it('says the approval was refused when billing refuses it on a first attempt', async () => {
    refund.mockRejectedValueOnce(apiError(403, { code: 'REFUND_STEP_UP_REFUSED' }))
    await payOut()
    expect(await screen.findByText(COPY.stepUpRefused)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: RETRY })).toBeNull()
  })

  it('says the approval was refused when admin-api refuses it on a first attempt', async () => {
    refund.mockRejectedValueOnce(apiError(403, { code: 'step_up_invalid' }, '{"code":"step_up_invalid"}'))
    await payOut()
    expect(await screen.findByText(COPY.stepUpRefused)).toBeInTheDocument()
  })

  it('locks every way to start another refund while a payout is in flight', async () => {
    let settle
    refund.mockImplementationOnce(() => new Promise((resolve) => { settle = resolve }))
    await payOut()

    expect(screen.queryByLabelText(/otp code/i)).toBeNull()
    expect(screen.getByRole('button', { name: /start otp refund/i })).toBeDisabled()
    expect(screen.getByLabelText(/refund amount \(cents\)/i)).toBeDisabled()
    await act(async () => { settle({ requestedCents: 874, walletDebitedCents: 874 }) })
    expect(await screen.findByText(COPY.paid)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /start otp refund/i })).toBeEnabled()
  })

  it('closes the OTP row and blocks a restore OTP while a retry is offered', async () => {
    refund.mockRejectedValueOnce(timedOut())
    renderUsers()
    await user.type(screen.getByLabelText(/restore after stripe reversed/i), 're_1234567890')
    expect(screen.getByRole('button', { name: /start otp restore/i })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: /start otp refund/i }))
    await user.type(screen.getByLabelText(/otp code/i), '123456')
    await user.click(screen.getByRole('button', { name: /verify & execute/i }))

    await screen.findByRole('button', { name: RETRY })
    expect(screen.queryByLabelText(/otp code/i)).toBeNull()
    expect(screen.queryByRole('button', { name: /verify & execute/i })).toBeNull()
    expect(screen.getByRole('button', { name: /start otp restore/i })).toBeDisabled()
    expect(otpStart).toHaveBeenCalledTimes(1)
  })

  it('says the admin server may have received it when the browser could not reach admin-api', async () => {
    refund.mockRejectedValueOnce(apiError(0, { code: 'NETWORK_ERROR' }))
    await payOut()

    expect(await screen.findByText(COPY.browserNetwork)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: RETRY })).toBeEnabled()
  })

  it('treats a browser timeout on the first try as an unknown outcome', async () => {
    refund.mockRejectedValueOnce(apiError(0, { code: 'CLIENT_TIMEOUT' }))
    await payOut()
    expect(await screen.findByText(COPY.outcomeUnknown)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: RETRY })).toBeEnabled()
  })

  it('offers the retry and says billing hit an error on a billing 5xx', async () => {
    refund.mockRejectedValueOnce(apiError(504,
      { code: 'REFUND_OUTCOME_UNKNOWN', cause: 'billing_error' }, 'server text'))
      .mockResolvedValueOnce({ idempotentReplay: true })
    await payOut()

    expect(await screen.findByText(COPY.billingError)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: RETRY }))
    expect(await screen.findByText(COPY.replayed)).toBeInTheDocument()
    expect(refund.mock.calls[1]).toEqual(retrySend)
  })

  it('keeps the billing-error copy when the retry hits a billing error too', async () => {
    const billingError = () => apiError(504, { code: 'REFUND_OUTCOME_UNKNOWN', cause: 'billing_error' })
    refund.mockRejectedValueOnce(timedOut()).mockRejectedValueOnce(billingError())
    await payOut()
    await user.click(await screen.findByRole('button', { name: RETRY }))

    expect(await screen.findByText(COPY.billingError)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: RETRY })).toBeEnabled()
  })

  it('shows admin-api\'s message quoting billing for a conflict that may follow Stripe', async () => {
    const quoted = 'Billing could not finish the refund — it may have gone through. Check the '
      + 'payment before retrying. Billing said: Cannot credit payout against request'
    refund.mockRejectedValueOnce(apiError(504,
      { code: 'REFUND_OUTCOME_UNKNOWN', cause: 'billing_conflict', message: quoted }, quoted))
    await payOut()

    expect(await screen.findByText(quoted)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: RETRY })).toBeEnabled()
  })

  it('says Stripe declined and nothing was paid, with no retry', async () => {
    refund.mockRejectedValueOnce(apiError(502, { code: 'REFUND_DECLINED' }))
    await payOut()

    expect(await screen.findByText(COPY.stripeDeclined)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: RETRY })).toBeNull()
  })

  it('shows a refusal without billing\'s words as admin-api phrased it', async () => {
    const copy = 'Billing refused the refund (HTTP 400). Nothing was paid.'
    refund.mockRejectedValueOnce(apiError(502, { code: 'DOWNSTREAM_REJECTED', message: copy }, copy))
    await payOut()

    expect(await screen.findByText(copy)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: RETRY })).toBeNull()
  })

  it('verifies the OTP once however often Verify is clicked', async () => {
    let settle
    otpVerify.mockImplementationOnce(() => new Promise((resolve) => { settle = resolve }))
    refund.mockResolvedValueOnce({ requestedCents: 874, walletDebitedCents: 874 })
    renderUsers()
    await user.click(screen.getByRole('button', { name: /start otp refund/i }))
    await user.type(screen.getByLabelText(/otp code/i), '123456')
    await user.dblClick(screen.getByRole('button', { name: /verify & execute/i }))

    await act(async () => { settle({ verified: true, stepUpToken: TOKEN }) })
    expect(await screen.findByText(COPY.paid)).toBeInTheDocument()
    expect(otpVerify).toHaveBeenCalledTimes(1)
    expect(refund).toHaveBeenCalledTimes(1)
  })

  it('never writes the request or token to browser storage', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    refund.mockRejectedValueOnce(timedOut())
    await payOut()
    await screen.findByRole('button', { name: RETRY })
    expect(setItem).not.toHaveBeenCalled()
    setItem.mockRestore()
  })
})
