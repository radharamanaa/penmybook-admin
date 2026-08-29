import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api } from '../api.js'

/**
 * User search + money ops. Grant/refund are OTP step-up gated (D6): start a
 * challenge, type the emailed code (dev: read from otp_challenges), the minted
 * single-use token authorises exactly this action + params.
 *
 * Queue payout is a deep-link: `/users?refundRequestId=&ownerType=&ownerId=&ownerEmail=&paymentId=`.
 * When an APPROVED request id is present it is bound into the OTP and forwarded to billing.
 */
export default function Users() {
  const [searchParams] = useSearchParams()
  const linkedRequestId = searchParams.get('refundRequestId') || ''
  const linkedPaymentId = searchParams.get('paymentId') || ''
  const linkedOwnerId = searchParams.get('ownerId') || ''
  const linkedEmail = searchParams.get('ownerEmail') || ''

  const [query, setQuery] = useState(linkedEmail)
  const [results, setResults] = useState([])
  const [selected, setSelected] = useState(() => (
    linkedOwnerId
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

      <form className="panel row" onSubmit={search}>
        <div>
          <label htmlFor="q">Email contains</label>
          <input id="q" value={query} onChange={(e) => setQuery(e.target.value)} required />
        </div>
        <button>Search</button>
      </form>

      {results.length > 0 && (
        <div className="panel">
          <table>
            <thead>
              <tr><th>Email</th><th>Status</th><th>User ID</th><th /></tr>
            </thead>
            <tbody>
              {results.map((u) => (
                <tr key={u.userId}>
                  <td>{u.email}</td>
                  <td>{statusBadge(u.accountStatus)}</td>
                  <td className="mono">{u.userId}</td>
                  <td>
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
        Paddle refund; we forfeit bonus we can identify.
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
