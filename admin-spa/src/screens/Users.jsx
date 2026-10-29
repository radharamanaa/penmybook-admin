import { useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api } from '../api.js'
import { useScrollIntoViewOnPhone } from '../useScrollIntoViewOnPhone.js'
import OrgHold from './OrgHold.jsx'

/**
 * User search + money ops. Grant/refund/restore-reversed are OTP step-up gated (D6): start a
 * challenge, type the emailed code (dev: read from otp_challenges), the minted
 * single-use token authorises exactly this action + params.
 *
 * Queue payout is a deep-link: `/users?refundRequestId=&ownerType=&ownerId=&ownerEmail=&paymentId=`.
 * When an APPROVED request id is present it is bound into the OTP and forwarded to billing.
 * Restore after Stripe reversed uses the same {@code refund} OTP action with {@code forceResolve}.
 */
export default function Users() {
  const [searchParams] = useSearchParams()
  const linkedRequestId = searchParams.get('refundRequestId') || ''
  const linkedPaymentId = searchParams.get('paymentId') || ''
  const linkedAmountCents = searchParams.get('amountCents') || ''
  const linkedOwnerId = searchParams.get('ownerId') || ''
  const linkedEmail = searchParams.get('ownerEmail') || ''
  const linkedOwnerType = searchParams.get('ownerType') || ''
  const orgPayoutUnwired = Boolean(linkedRequestId && linkedOwnerType && linkedOwnerType !== 'USER')

  const [query, setQuery] = useState(linkedEmail)
  const [results, setResults] = useState([])
  const [selected, setSelected] = useState(() => (
    linkedOwnerId && !orgPayoutUnwired
      ? { userId: linkedOwnerId, email: linkedEmail || linkedOwnerId, accountStatus: 'ACTIVE' }
      : null
  ))
  const [error, setError] = useState(null)
  const actionsRef = useRef(null)
  useScrollIntoViewOnPhone(actionsRef, selected, 'start')
  // Money results show at the page top; on a phone the operator is scrolled down at the
  // OTP row, so each result (even a repeated one) brings the banner into view.
  const [resultSeq, setResultSeq] = useState(0)
  const resultRef = useRef(null)
  useScrollIntoViewOnPhone(resultRef, resultSeq, 'start')
  const [ok, setOk] = useState(null)

  function showResult(okMessage, errorMessage) {
    setOk(okMessage)
    setError(errorMessage)
    setResultSeq((n) => n + 1)
  }

  async function search(e) {
    e.preventDefault()
    setError(null)
    try {
      setResults(await api.searchUsers(query))
    } catch (err) {
      setError(err.message)
    }
  }

  async function admit(user) {
    setError(null)
    setOk(null)
    try {
      const r = await api.waitlistAdmit(user.userId)
      if (r.admitted) {
        setOk(`${user.email} admitted — account is now ACTIVE.`)
      } else if (r.reason === 'weekly_limit_reached') {
        setError(`This week's approval limit is reached — raise it on the Waitlist screen.`)
      } else {
        setOk(`${user.email} is already ${r.accountStatus ?? 'not waitlisted'}.`)
      }
      await search({ preventDefault() {} })
    } catch (err) {
      setError(err.message)
    }
  }

  function statusBadge(status) {
    if (!status || status === 'ACTIVE') return <span className="badge on">active</span>
    if (status === 'WAITLISTED') return <span className="badge neutral">waitlisted</span>
    return <span className="badge off">{status.toLowerCase().replaceAll('_', ' ')}</span>
  }

  return (
    <>
      <h1>Users & money</h1>
      <p className="sub">Search by email; wallet top-up, OTP-gated grants and refunds.</p>
      <div ref={resultRef}>
        {error && <div className="msg error">{error}</div>}
        {ok && <div className="msg ok">{ok}</div>}
      </div>
      {orgPayoutUnwired && (
        <div className="msg error" role="status">
          Open org payout is not wired on this screen — use request {linkedRequestId} from the org tools.
        </div>
      )}

      <form className="panel row" onSubmit={search}>
        <div>
          <label htmlFor="q">Email contains</label>
          <input id="q" value={query} onChange={(e) => setQuery(e.target.value)} required />
        </div>
        <button>Search</button>
      </form>

      {results.length > 0 && (
        <div className="panel">
          <div className="table-wrap">
            <table className="cards" role="table">
              <thead role="rowgroup">
                <tr role="row"><th role="columnheader">Email</th><th role="columnheader">Status</th><th role="columnheader">User ID</th><th role="columnheader" /></tr>
              </thead>
              <tbody role="rowgroup">
                {results.map((u) => (
                  <tr role="row" key={u.userId}>
                    <td role="cell" data-label="Email">{u.email}</td>
                    <td role="cell" data-label="Status">{statusBadge(u.accountStatus)}</td>
                    <td role="cell" data-label="User ID" className="mono">{u.userId}</td>
                    <td role="cell">
                      {u.accountStatus === 'WAITLISTED' && (
                        <button className="small" onClick={() => admit(u)}>Admit</button>
                      )}{' '}
                      <button className="small ghost" onClick={() => setSelected(u)}>Manage</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      <OrgHold />

      {selected && (
        <div ref={actionsRef}>
          <UserActions user={selected}
                       initialPaymentId={linkedPaymentId}
                       initialRefundCents={linkedAmountCents}
                       initialRefundRequestId={linkedRequestId}
                       refundRequestIdReadOnly={Boolean(linkedRequestId)}
                       onDone={(msg) => showResult(msg, null)}
                       onError={(msg) => showResult(null, msg)} />
        </div>
      )}
    </>
  )
}

const SUSPENDED_STATUSES = new Set(['SUSPENDED', 'SUSPENDED_FOR_REVIEW'])
const HOLD_NOTE_MIN_LENGTH = 8
// Why a SUSPENDED_FOR_REVIEW account is held, so staff never lift a content review believing it
// was a payment hold.
const HOLD_KIND_LABELS = {
  DISPUTE: 'card dispute',
  REFUND: 'refund hold',
  CONTENT: 'content review',
}

function formatDollars(cents) {
  return `$${(cents / 100).toFixed(2)}`
}

/**
 * Why the user is held (account suspension, wallet freeze, money a refund or dispute could not
 * take back) and the two OTP lifts (F3). Reactivation is offered only for a suspension:
 * entitlements refuses any other status anyway.
 */
function HoldPanel({ state, note, onNoteChange, onReactivate, onUnfreeze }) {
  if (!state) return null
  const suspended = SUSPENDED_STATUSES.has(state.accountStatus)
  const held = suspended || state.walletFrozen
  // Billing did not answer: the wallet part is unknown, so never say "no hold" (Sonnet review).
  const walletUnknown = state.walletDegraded === true
  if (!held && !walletUnknown) {
    return <p className="sub">No hold on this account.</p>
  }
  if (!held) {
    return <p className="sub">Account is active. Wallet: unknown — billing did not answer.</p>
  }
  const noteReady = note.trim().length >= HOLD_NOTE_MIN_LENGTH
  return (
    <div className="msg error" role="status">
      <p style={{ marginTop: 0 }}>
        Account: <span className="hold-status">
          {(state.accountStatus ?? 'unknown').toLowerCase().replaceAll('_', ' ')}
        </span>
        {suspended && HOLD_KIND_LABELS[state.suspensionKind] && (
          <> — <strong>{HOLD_KIND_LABELS[state.suspensionKind]}</strong></>
        )}
        {state.walletFrozen && <> · Wallet frozen (<code>{state.walletFrozenReason}</code>)</>}
        {walletUnknown && <> · Wallet: unknown — billing did not answer</>}
      </p>
      {state.unrecoveredCents > 0 && (
        <p>
          {formatDollars(state.unrecoveredCents)} could not be taken back by a refund or dispute
          (already spent). Unfreezing does not settle it.
        </p>
      )}
      <div className="row">
        <div>
          <label htmlFor="hold-note">Why are you lifting the hold?</label>
          <input id="hold-note" value={note} onChange={(e) => onNoteChange(e.target.value)}
                 placeholder="e.g. dispute du_… won in Stripe" />
        </div>
        {suspended && (
          <button disabled={!noteReady} onClick={onReactivate}>Start OTP reactivate account</button>
        )}
        {state.walletFrozen && (
          <button disabled={!noteReady} onClick={onUnfreeze}>Start OTP unfreeze wallet</button>
        )}
      </div>
    </div>
  )
}

function UserActions({ user, initialPaymentId = '', initialRefundCents = '', initialRefundRequestId = '',
                       refundRequestIdReadOnly = false, onDone, onError }) {
  const [topUp, setTopUp] = useState('')
  const [grant, setGrant] = useState('')
  const [refundPayment, setRefundPayment] = useState(initialPaymentId)
  const [refundCents, setRefundCents] = useState(initialRefundCents)
  const [refundRequestId, setRefundRequestId] = useState(initialRefundRequestId)
  const [reverseNote, setReverseNote] = useState('')
  // OTP flow state: which action is mid-challenge + the challenge id + code. `note` rides along
  // for the hold lifts but is NOT in `params`: params are exactly what the server hashes.
  const [otp, setOtp] = useState(null) // {action, challengeId, params, note}
  const [code, setCode] = useState('')
  const otpRowRef = useRef(null)
  useScrollIntoViewOnPhone(otpRowRef, otp)
  const [holdState, setHoldState] = useState(null)
  const [holdError, setHoldError] = useState(null)
  const [holdNote, setHoldNote] = useState('')

  // Depends on the user only: the parent's onDone/onError are new functions on every render, so
  // reporting through them here would refetch on every parent render.
  const loadHoldState = useCallback(async () => {
    try {
      setHoldState(await api.accountState(user.userId))
      setHoldError(null)
    } catch (err) {
      setHoldState(null)
      setHoldError(err.message)
    }
  }, [user.userId])

  useEffect(() => { loadHoldState() }, [loadHoldState])

  async function startOtp(action, params, note = null) {
    try {
      const resp = await api.otpStart(action, user.userId, params)
      setOtp({ action, challengeId: resp.challengeId, params, note })
      setCode('')
    } catch (err) { onError(err.message) }
  }

  async function verifyAndExecute() {
    try {
      const v = await api.otpVerify(otp.challengeId, code, otp.action, user.userId, otp.params)
      if (!v.verified) { onError(`Code rejected: ${v.failureReason ?? 'invalid'}`); return }
      if (otp.action === 'grant_credit') {
        await api.grantCredits(user.userId, otp.params.amountCents, v.stepUpToken)
        onDone(`Granted ${otp.params.amountCents}¢ to ${user.email}`)
      } else if (otp.action === 'wallet_top_up') {
        await api.walletTopUp(user.userId, otp.params.amountCents, 'admin top-up', v.stepUpToken)
        onDone(`Topped up ${otp.params.amountCents}¢ for ${user.email}`)
      } else if (otp.action === 'unsuspend') {
        const r = await api.unsuspend(user.userId, otp.note, v.stepUpToken)
        onDone(`${user.email} is ${r.accountStatus} again.`)
        await loadHoldState()
      } else if (otp.action === 'wallet_unfreeze') {
        const r = await api.unfreezeWallet(user.userId, otp.note, v.stepUpToken)
        onDone(r.lifted ? `Wallet unfrozen for ${user.email}.` : `The wallet of ${user.email} was not frozen.`)
        await loadHoldState()
      } else if (otp.params.forceResolve) {
        const r = await api.forceResolveReversed(
          user.userId, otp.params.paymentId, otp.params.note, v.stepUpToken)
        onDone(`Restored ${r.restoredCents}¢ to wallet for payment ${otp.params.paymentId}. Card is unchanged.`)
      } else {
        await api.refund(user.userId, otp.params.paymentId, otp.params.refundAmountCents,
          'admin refund', v.stepUpToken, otp.params.refundRequestId)
        onDone(`Refund issued for payment ${otp.params.paymentId}`)
      }
      setOtp(null)
    } catch (err) {
      onError(err.message)
      // A refused or timed-out lift may still have changed the hold (billing unfroze, then the
      // reply was lost): re-read it so the panel never offers a lift that is already done.
      if (otp.action === 'unsuspend' || otp.action === 'wallet_unfreeze') await loadHoldState()
    }
  }

  async function togglePlatformAdmin() {
    try {
      await api.setPlatformAdmin(user.userId, !user.isPlatformAdmin)
      onDone(`is_platform_admin → ${!user.isPlatformAdmin} for ${user.email}`)
    } catch (err) { onError(err.message) }
  }

  return (
    <div className="panel">
      <h2 style={{ marginTop: 0 }}>Manage <code>{user.email}</code></h2>

      {holdError && (
        <div className="msg error">Could not read this user&apos;s holds: {holdError}</div>
      )}
      <HoldPanel state={holdState} note={holdNote} onNoteChange={setHoldNote}
                 onReactivate={() => startOtp('unsuspend', {}, holdNote.trim())}
                 onUnfreeze={() => startOtp('wallet_unfreeze',
                   { ownerType: 'USER', ownerId: user.userId }, holdNote.trim())} />

      <div className="row">
        <div>
          <label>Wallet top-up (cents) — OTP required</label>
          <input type="number" inputMode="numeric" min="1" value={topUp} onChange={(e) => setTopUp(e.target.value)} />
        </div>
        <button disabled={!topUp}
                onClick={() => startOtp('wallet_top_up', { amountCents: Number(topUp) })}>
          Start OTP top-up
        </button>
      </div>

      <div className="row">
        <div>
          <label>Grant credits (cents) — OTP required</label>
          <input type="number" inputMode="numeric" min="1" value={grant} onChange={(e) => setGrant(e.target.value)} />
        </div>
        <button disabled={!grant}
                onClick={() => startOtp('grant_credit', { amountCents: Number(grant) })}>
          Start OTP grant
        </button>
      </div>

      <div className="row">
        <div>
          <label htmlFor="refund-payment">Refund — payment ID</label>
          <input id="refund-payment" value={refundPayment}
                 onChange={(e) => setRefundPayment(e.target.value)} />
        </div>
        <div>
          <label htmlFor="refund-cents">Refund amount (cents)</label>
          <input id="refund-cents" type="number" inputMode="numeric" min="1" value={refundCents}
                 onChange={(e) => setRefundCents(e.target.value)} />
        </div>
        <div>
          <label htmlFor="refund-request-id">Refund request id</label>
          <input id="refund-request-id" value={refundRequestId} readOnly={refundRequestIdReadOnly}
                 onChange={(e) => { if (!refundRequestIdReadOnly) setRefundRequestId(e.target.value) }} />
        </div>
        <button disabled={!refundPayment || !refundCents}
                onClick={() => {
                  const params = {
                    paymentId: refundPayment,
                    refundAmountCents: Number(refundCents),
                  }
                  if (refundRequestId) params.refundRequestId = refundRequestId
                  startOtp('refund', params)
                }}>
          Start OTP refund
        </button>
      </div>
      <p className="sub">
        Uses live remaining on the payment. Leftover promo inside remaining can ride the
        Stripe refund.
      </p>

      <div className="row" style={{ marginTop: 14 }}>
        <div>
          <label htmlFor="reverse-note">Restore after Stripe reversed (payment ID above)</label>
          <input id="reverse-note" value={reverseNote}
                 onChange={(e) => setReverseNote(e.target.value)}
                 placeholder="Stripe refund id (re_…)" />
        </div>
        <button disabled={!refundPayment || reverseNote.trim().length < 8}
                onClick={() => startOtp('refund', {
                  paymentId: refundPayment,
                  forceResolve: true,
                  note: reverseNote.trim(),
                })}>
          Start OTP restore (reversed)
        </button>
      </div>
      <p className="sub">
        Does not credit the card. Only restores the wallet after you have confirmed in Stripe
        that this refund was reversed and the wallet was not put back. A failed refund is
        already returned. If the card refund still stands, this donates
        the claw. Webhooks never auto-restore.
      </p>

      {otp && (
        <div ref={otpRowRef} className="row" style={{ marginTop: 14 }}>
          <div>
            <label htmlFor="otp-code">OTP code for <code>{otp.action}</code> (check email / dev DB)</label>
            <input id="otp-code" inputMode="numeric" autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value)} maxLength={6} />
          </div>
          <button disabled={code.length < 6} onClick={verifyAndExecute}>Verify & execute</button>
          <button className="ghost" onClick={() => setOtp(null)}>Cancel</button>
        </div>
      )}

      <div className="row" style={{ marginTop: 14 }}>
        <button className="ghost" onClick={togglePlatformAdmin}>
          {user.isPlatformAdmin ? 'Demote from platform admin' : 'Promote to platform admin'}
        </button>
      </div>
    </div>
  )
}
