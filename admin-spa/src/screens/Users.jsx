import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api } from '../api.js'

/**
 * User search + money ops. Grant/refund/restore-reversed are OTP step-up gated (D6): start a
 * challenge, type the emailed code (dev: read from otp_challenges), the minted
 * single-use token authorises exactly this action + params.
 *
 * Queue payout is a deep-link: `/users?refundRequestId=&ownerType=&ownerId=&ownerEmail=&paymentId=`.
 * When an APPROVED request id is present it is bound into the OTP and forwarded to billing.
 * Restore after Paddle reversed uses the same {@code refund} OTP action with {@code forceResolve}.
 */
export default function Users() {
  const [searchParams] = useSearchParams()
  const linkedRequestId = searchParams.get('refundRequestId') || ''
  const linkedPaymentId = searchParams.get('paymentId') || ''
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
  const [ok, setOk] = useState(null)

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
      {error && <div className="msg error">{error}</div>}
      {ok && <div className="msg ok">{ok}</div>}
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

      {selected && (
        <UserActions user={selected}
                     initialPaymentId={linkedPaymentId}
                     initialRefundRequestId={linkedRequestId}
                     refundRequestIdReadOnly={Boolean(linkedRequestId)}
                     onDone={(msg) => { setOk(msg); setError(null) }}
                     onError={(msg) => { setError(msg); setOk(null) }} />
      )}
    </>
  )
}

function UserActions({ user, initialPaymentId = '', initialRefundRequestId = '',
                       refundRequestIdReadOnly = false, onDone, onError }) {
  const [topUp, setTopUp] = useState('')
  const [grant, setGrant] = useState('')
  const [refundPayment, setRefundPayment] = useState(initialPaymentId)
  const [refundCents, setRefundCents] = useState('')
  const [refundRequestId, setRefundRequestId] = useState(initialRefundRequestId)
  const [reverseNote, setReverseNote] = useState('')
  // OTP flow state: which action is mid-challenge + the challenge id + code.
  const [otp, setOtp] = useState(null) // {action, challengeId, params}
  const [code, setCode] = useState('')

  async function startOtp(action, params) {
    try {
      const resp = await api.otpStart(action, user.userId, params)
      setOtp({ action, challengeId: resp.challengeId, params })
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
    } catch (err) { onError(err.message) }
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

      <div className="row">
        <div>
          <label>Wallet top-up (cents) — OTP required</label>
          <input type="number" min="1" value={topUp} onChange={(e) => setTopUp(e.target.value)} />
        </div>
        <button disabled={!topUp}
                onClick={() => startOtp('wallet_top_up', { amountCents: Number(topUp) })}>
          Start OTP top-up
        </button>
      </div>

      <div className="row">
        <div>
          <label>Grant credits (cents) — OTP required</label>
          <input type="number" min="1" value={grant} onChange={(e) => setGrant(e.target.value)} />
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
          <input id="refund-cents" type="number" min="1" value={refundCents}
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
        Paddle refund.
      </p>

      <div className="row" style={{ marginTop: 14 }}>
        <div>
          <label htmlFor="reverse-note">Restore after Paddle reversed (payment ID above)</label>
          <input id="reverse-note" value={reverseNote}
                 onChange={(e) => setReverseNote(e.target.value)}
                 placeholder="Paddle adjustment id / dashboard confirmed reverse" />
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
        Does not credit the card. Only restores the wallet after you have confirmed in Paddle
        that this refund adjustment is reversed. If the card refund still stands, this donates
        the claw. Webhooks never auto-restore.
      </p>

      {otp && (
        <div className="row" style={{ marginTop: 14 }}>
          <div>
            <label htmlFor="otp-code">OTP code for <code>{otp.action}</code> (check email / dev DB)</label>
            <input id="otp-code" value={code} onChange={(e) => setCode(e.target.value)} maxLength={6} />
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
