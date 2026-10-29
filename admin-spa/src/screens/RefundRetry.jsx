import { useEffect, useRef, useState } from 'react'
import { CLIENT_TIMEOUT, NETWORK_ERROR } from '../requestErrors.js'
import { useScrollIntoViewOnPhone } from '../useScrollIntoViewOnPhone.js'

/**
 * "Retry with the same approval" for a staff payout whose outcome is not known.
 *
 * Billing keys a payout on the step-up challenge id and looks up an earlier refund under that key
 * BEFORE it checks the approval. So resending the identical request with the SAME token either
 * returns the first refund (it landed; nothing more is paid) or pays once (it did not). A fresh OTP
 * is a new key, which billing treats as a new refund — the double-payment this panel prevents.
 *
 * The request and token live in component state only (never browser storage) and are offered
 * only while the token is inside its TTL.
 */

// Mirrors admin-api StepUpTokenIssuer.TOKEN_TTL_SECONDS; counted from when the OTP was verified.
export const STEP_UP_TOKEN_TTL_MS = 300_000
// Stop offering the retry a little early so a last-second click is not refused as expired.
export const RETRY_SAFETY_MARGIN_MS = 10_000
const CLOCK_TICK_MS = 1_000
const MS_PER_SECOND = 1_000
const SECONDS_PER_MINUTE = 60
const STEP_UP_INVALID = 'step_up_invalid'
const HTTP_FORBIDDEN = 403
const HTTP_SERVER_ERROR_MIN = 500

export const REFUND_REASON = 'admin refund'

export const REFUND_COPY = {
  outcomeUnknown: 'The refund may have gone through — check the payment before retrying.',
  retryTimedOut:
    'The retry timed out too — the refund may have gone through. Check the payment before retrying.',
  notSent: 'Billing could not be reached — nothing was sent. It is safe to retry.',
  retryNotSent:
    'This retry did not reach billing — the first attempt may still have gone through. '
    + 'Check the payment, or retry with the same approval (it cannot pay twice).',
  browserNetwork:
    'Could not reach the admin server — the refund was most likely not sent. '
    + 'Retrying with the same approval is safe: it cannot pay twice.',
  retryNetwork:
    'This retry most likely did not reach the admin server — the first attempt may still have '
    + 'gone through. Check the payment, or retry with the same approval (it cannot pay twice).',
  replayed: 'That refund had already gone through — nothing more was paid.',
  expired: 'The approval has expired — check the payment in Stripe, then start a new refund.',
  approvalUsed:
    'This approval was already used — the first attempt may still be going through. '
    + 'Wait a minute and check the payment in Stripe before starting a new refund.',
  stepUpRefused: 'The approval was refused — start the refund again with a new code.',
  billingError:
    'Billing hit an error — the refund may have gone through. Check the payment before retrying.',
  stripeDeclined: 'Stripe declined the refund — nothing was paid.',
  proxyError:
    'The admin server did not answer properly — the refund may have gone through. '
    + 'Check the payment before retrying.',
  readyForNewRefund: 'You can start a new refund now — check the payment in Stripe first.',
}

// admin-api's `cause` on a REFUND_OUTCOME_UNKNOWN answer.
const CAUSE_BILLING_ERROR = 'billing_error'
const CAUSE_BILLING_CONFLICT = 'billing_conflict'

function outcomeUnknownCopy(err, isRetry) {
  const cause = err?.body?.cause
  if (cause === CAUSE_BILLING_ERROR) return REFUND_COPY.billingError
  // Billing's own words are part of admin-api's message for a conflict.
  if (cause === CAUSE_BILLING_CONFLICT && err?.message) return err.message
  return isRetry ? REFUND_COPY.retryTimedOut : REFUND_COPY.outcomeUnknown
}

/** The success line for a payout receipt: a replay paid nothing, a fresh refund names its amount. */
export function refundSuccessCopy(receipt, request) {
  if (receipt?.idempotentReplay) return REFUND_COPY.replayed
  const cents = Number.isFinite(receipt?.requestedCents) ? receipt.requestedCents : request.amountCents
  return `Refund issued for payment ${request.paymentId}: ${cents}¢.`
}

/**
 * What a failed payout means for staff. `retryable` failures keep the same approval on offer;
 * every other one ends it and shows `message`. `priorMayHaveLanded` is true when an earlier
 * attempt with this approval may have refunded the card; `mayHaveLanded` says whether, after this
 * failure, any attempt may have.
 */
export function classifyRefundFailure(err, { isRetry, priorMayHaveLanded }) {
  const code = err?.body?.code
  if (code === 'REFUND_OUTCOME_UNKNOWN' || code === CLIENT_TIMEOUT
      || (err?.status === 0 && code == null)) {
    // admin-api or the browser stopped waiting for a request that was sent.
    return { retryable: true, mayHaveLanded: true, message: outcomeUnknownCopy(err, isRetry) }
  }
  if (code === 'REFUND_NOT_SENT') {
    return {
      retryable: true,
      mayHaveLanded: priorMayHaveLanded,
      message: priorMayHaveLanded ? REFUND_COPY.retryNotSent : REFUND_COPY.notSent,
    }
  }
  if (err?.status === 0 && code === NETWORK_ERROR) {
    // The browser cannot tell whether the request got out, so count it as possibly landed.
    return {
      retryable: true,
      mayHaveLanded: true,
      message: priorMayHaveLanded ? REFUND_COPY.retryNetwork : REFUND_COPY.browserNetwork,
    }
  }
  if (code === 'REFUND_APPROVAL_USED') {
    // The first attempt may still land: keep every refund control locked until the approval's
    // window has passed, with no retry (billing would refuse the same approval again).
    return { retryable: false, holdUntilExpiry: true, message: REFUND_COPY.approvalUsed }
  }
  if (code === 'REFUND_STEP_UP_REFUSED') return { retryable: false, message: REFUND_COPY.stepUpRefused }
  if (code === 'REFUND_DECLINED') return { retryable: false, message: REFUND_COPY.stripeDeclined }
  if (err?.status === HTTP_FORBIDDEN && code === STEP_UP_INVALID) {
    // admin-api's own check. The same token passed it a moment ago, so on a retry only its
    // expiry can fail; on a first attempt the code was not good for this refund.
    return {
      retryable: false,
      message: isRetry ? REFUND_COPY.expired : REFUND_COPY.stepUpRefused,
    }
  }
  if (err?.status >= HTTP_SERVER_ERROR_MIN && code == null) {
    // Not admin-api's own answer (every one of those carries a code): a proxy's 502/504 page
    // during a roll, or a crash. The request may have reached billing. Never show its HTML.
    return { retryable: true, mayHaveLanded: true, message: REFUND_COPY.proxyError }
  }
  return { retryable: false, message: err?.message ?? String(err) }
}

/** The pending retry for a payout whose outcome is not known, built from the timed-out attempt. */
export function pendingRefundRetry(request, verifiedAtMs, failure) {
  return {
    request,
    usableUntilMs: verifiedAtMs + STEP_UP_TOKEN_TTL_MS - RETRY_SAFETY_MARGIN_MS,
    message: failure.message,
    mayHaveLanded: failure.mayHaveLanded,
  }
}

/** True while the pending retry's approval can still be sent. */
export function retryStillUsable(pending, nowMs) {
  return pending != null && nowMs < pending.usableUntilMs
}

function formatTimeLeft(ms) {
  const totalSeconds = Math.max(0, Math.ceil(ms / MS_PER_SECOND))
  const minutes = Math.floor(totalSeconds / SECONDS_PER_MINUTE)
  const seconds = String(totalSeconds % SECONDS_PER_MINUTE).padStart(2, '0')
  return `${minutes}:${seconds}`
}

/** A clock that ticks once a second while `running`, so the time left and expiry stay live. */
export function useNow(running) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!running) return undefined
    setNow(Date.now())
    const timer = setInterval(() => setNow(Date.now()), CLOCK_TICK_MS)
    return () => clearInterval(timer)
  }, [running])
  return now
}

export function RefundRetryPanel({ pending, now, retrying, onRetry }) {
  const panelRef = useRef(null)
  useScrollIntoViewOnPhone(panelRef, pending)
  if (!pending) return null
  if (!retryStillUsable(pending, now)) {
    return (
      <div ref={panelRef} className="msg error" role="alert">
        <p style={{ margin: 0 }}>
          {pending.blocked ? REFUND_COPY.readyForNewRefund : REFUND_COPY.expired}
        </p>
      </div>
    )
  }
  if (pending.blocked) {
    return (
      <div ref={panelRef} className="msg error" role="alert">
        <p style={{ marginTop: 0 }}>{pending.message}</p>
        <p>You can start a new refund in {formatTimeLeft(pending.usableUntilMs - now)}.</p>
      </div>
    )
  }
  return (
    <div ref={panelRef} className="msg error" role="alert">
      <p style={{ marginTop: 0 }}>{pending.message}</p>
      <p>This approval is still valid for {formatTimeLeft(pending.usableUntilMs - now)}.</p>
      <button disabled={retrying} aria-busy={retrying} onClick={onRetry}>
        {retrying ? 'Retrying…' : 'Retry with the same approval'}
      </button>
    </div>
  )
}
