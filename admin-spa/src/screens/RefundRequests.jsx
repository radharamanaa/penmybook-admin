import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api.js'

const STATUSES = [
  'PENDING', 'APPROVED', 'PAYING', 'SETTLED', 'REJECTED', 'WITHDRAWN',
]

const STATUS_LABEL = {
  PAYING: 'in-flight card refund',
  SETTLED: 'fully paid out',
}

const STATUS_BADGE = {
  PENDING: 'neutral',
  APPROVED: 'on',
  PAYING: 'warn',
  SETTLED: 'on',
  REJECTED: 'off',
  WITHDRAWN: 'neutral',
}

function fmtCents(cents) {
  if (cents == null) return '—'
  return `$${(cents / 100).toFixed(2)}`
}

function ageOf(iso) {
  if (!iso) return '—'
  const then = new Date(iso).getTime()
  if (Number.isNaN(then)) return iso
  const mins = Math.max(0, Math.round((Date.now() - then) / 60000))
  if (mins < 60) return `${mins}m`
  const hours = Math.round(mins / 60)
  if (hours < 48) return `${hours}h`
  return `${Math.round(hours / 24)}d`
}

/**
 * Staff refund-request queue plus unresolved residuals. Owner names come from
 * entitlements/org identity lookups; `identityDegraded` is the quiet banner when
 * those lookups fail.
 *
 * Three empty states must never look the same:
 *   - billing did not answer (`degraded`) — red banner, the data you see is wrong
 *   - identity lookup is down (`identityDegraded`) — quieter, UUIDs still trustworthy
 *   - queue is genuinely clear
 */
export default function RefundRequests() {
  const [status, setStatus] = useState('PENDING')
  const [envelope, setEnvelope] = useState(null)
  const [residualEnvelope, setResidualEnvelope] = useState(null)
  const [error, setError] = useState(null)
  const [ok, setOk] = useState(null)
  const [otp, setOtp] = useState(null) // { kind, action, challengeId, params, request? }
  const [code, setCode] = useState('')
  const [note, setNote] = useState('')
  const [amountDraft, setAmountDraft] = useState('')

  const load = useCallback(async () => {
    setError(null)
    try {
      const [req, residuals] = await Promise.all([
        api.refundRequestQueue(status, 50),
        api.refundResiduals(),
      ])
      setEnvelope(req)
      setResidualEnvelope(residuals)
    } catch (err) {
      setError(err.message)
      setEnvelope(null)
      setResidualEnvelope(null)
    }
  }, [status])

  useEffect(() => { load() }, [load])

  async function startOtp(request, decision) {
    const amountCents = decision === 'APPROVE'
      ? (amountDraft === '' ? null : Number(amountDraft))
      : null
    if (decision === 'APPROVE' && amountCents === 0) {
      setError('A zero approval would close the request having paid nothing.')
      return
    }
    const params = {
      requestId: request.id,
      decision,
      amountCents,
    }
    setError(null)
    setOk(null)
    try {
      // targetUserId MUST be null — copying Users.jsx's user.userId 403s every action.
      const resp = await api.otpStart('resolve_refund_request', null, params)
      setOtp({ kind: 'resolve', request, action: 'resolve_refund_request', challengeId: resp.challengeId, params })
      setCode('')
      setNote('')
    } catch (err) {
      setError(err.message)
    }
  }

  async function startSettleOtp(residual) {
    const params = { residualId: residual.id }
    setError(null)
    setOk(null)
    try {
      const resp = await api.otpStart('settle_refund_residual', null, params)
      setOtp({ kind: 'settle', action: 'settle_refund_residual', challengeId: resp.challengeId, params })
      setCode('')
    } catch (err) {
      setError(err.message)
    }
  }

  async function verifyAndExecute() {
    if (!otp) return
    if (otp.kind === 'resolve' && !note.trim()) return
    setError(null)
    setOk(null)
    try {
      const v = await api.otpVerify(
        otp.challengeId, code, otp.action, null, otp.params,
      )
      if (!v.verified) {
        setError(`Code rejected: ${v.failureReason ?? 'invalid'}`)
        return
      }
      if (otp.kind === 'settle') {
        await api.settleRefundResidual(otp.params.residualId, v.stepUpToken)
        setOk('Residual settled. Refresh if an APPROVED request should now be closed.')
      } else {
        // Hashed fields come from otp.params (the server's hash recompute), not the form.
        // Note is editable after OTP start because it is not in the hash.
        await api.resolveRefundRequest(
          otp.params.requestId,
          otp.params.decision,
          otp.params.amountCents,
          note.trim(),
          v.stepUpToken,
        )
        setOk(otp.params.decision === 'APPROVE'
          ? 'Approved — refresh if the row has left the default PENDING filter.'
          : 'Rejected.')
        setAmountDraft('')
      }
      setOtp(null)
      await load()
    } catch (err) {
      if (err.status === 502) {
        setError('Billing did not confirm. Refresh and check the status.')
        setOtp(null)
        await load()
        return
      }
      setError(err.message)
    }
  }

  const requests = envelope?.requests ?? []
  const degraded = envelope?.degraded === true
  const identityDegraded = envelope?.identityDegraded === true
  const residuals = residualEnvelope?.residuals ?? []
  const residualsDegraded = residualEnvelope?.degraded === true
  const executeDisabled = code.length < 6 || (otp?.kind === 'resolve' && !note.trim())

  return (
    <>
      <h1>Refund requests</h1>
      <p className="sub">
        Users ask; you decide the amount against the live refundable figure (snapshot is
        what we quoted). Leftover promo inside remaining can ride the Paddle refund.
        Identity names load when entitlements answers; a quiet banner shows if they do
        not. Residuals below are money that could not go back to a card.
      </p>
      {error && <div className="msg error">{error}</div>}
      {ok && <div className="msg ok">{ok}</div>}
      {degraded && (
        <div className="msg error">
          Billing did not answer. This is not an empty queue — do not treat the list as complete.
        </div>
      )}
      {!degraded && identityDegraded && (
        <div className="msg" style={{ background: 'var(--panel-2)', color: 'var(--muted)' }}>
          Owner names could not be loaded. UUIDs below are still the real owners.
        </div>
      )}

      <div className="row" style={{ marginTop: 8 }}>
        {STATUSES.map((s) => (
          <button
            key={s}
            type="button"
            className={status === s ? 'small' : 'ghost small'}
            onClick={() => setStatus(s)}
          >
            {STATUS_LABEL[s] ?? s.replaceAll('_', ' ')}
          </button>
        ))}
        <button type="button" className="ghost small" onClick={load}>Refresh</button>
      </div>

      {envelope == null && !error ? (
        <p className="sub">Loading…</p>
      ) : !degraded && requests.length === 0 ? (
        <p className="sub">Queue is clear — nobody is waiting under {status.replaceAll('_', ' ')}.</p>
      ) : requests.length > 0 ? (
        <table>
          <thead>
            <tr>
              <th>Owner</th>
              <th>Status</th>
              <th>Asked</th>
              <th>Quoted refundable</th>
              <th>Live refundable</th>
              <th>Refunded</th>
              <th>Residual</th>
              <th>Reason</th>
              <th>Age</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {requests.map((row) => (
              <tr key={row.id}>
                <td>
                  <div>{row.ownerName || `${row.ownerType}/${row.ownerId}`}</div>
                  {row.ownerEmail && <div className="sub">{row.ownerEmail}</div>}
                  {row.ownerName && (
                    <div className="mono" style={{ fontSize: 11 }}>{row.ownerType}/{row.ownerId}</div>
                  )}
                </td>
                <td>
                  <span className={`badge ${STATUS_BADGE[row.status] ?? 'neutral'}`}>
                    {row.status}
                  </span>
                </td>
                <td>{fmtCents(row.requestedAmountCents)}</td>
                <td>{fmtCents(row.snapshotRefundableCents)}</td>
                <td>{fmtCents(row.liveRefundableCents)}</td>
                <td>{fmtCents(row.refundedCents)}</td>
                <td>{fmtCents(row.residualCents)}</td>
                <td style={{ maxWidth: 240, whiteSpace: 'pre-wrap' }}>{row.reason || '—'}</td>
                <td className="mono">{ageOf(row.createdAt)}</td>
                <td>
                  {row.status === 'PENDING' && (
                    <div className="row" style={{ marginTop: 0 }}>
                      <button type="button" className="small" onClick={() => startOtp(row, 'APPROVE')}>
                        Approve
                      </button>
                      <button type="button" className="ghost small" onClick={() => startOtp(row, 'REJECT')}>
                        Reject
                      </button>
                    </div>
                  )}
                  {row.status === 'APPROVED' && (
                    <Link
                      className="small"
                      to={`/users?refundRequestId=${encodeURIComponent(row.id)}&ownerType=${encodeURIComponent(row.ownerType)}&ownerId=${encodeURIComponent(row.ownerId)}${row.ownerEmail ? `&ownerEmail=${encodeURIComponent(row.ownerEmail)}` : ''}`}
                    >
                      Pay out
                    </Link>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}

      <h2 style={{ marginTop: 28 }}>Unresolved residuals</h2>
      <p className="sub">
        Money that never made it back to a card. Settling a refund-request residual closes the
        request without adding wallet credit — the cash never left. Account-deletion residuals
        credit the wallet.
      </p>
      {residualsDegraded && (
        <div className="msg error">
          Billing did not answer the residual queue. This is not an empty list.
        </div>
      )}
      {residualEnvelope == null && !error ? (
        <p className="sub">Loading residuals…</p>
      ) : !residualsDegraded && residuals.length === 0 ? (
        <p className="sub">No unresolved residuals.</p>
      ) : residuals.length > 0 ? (
        <table>
          <thead>
            <tr>
              <th>Owner</th>
              <th>Amount</th>
              <th>Payment</th>
              <th>Paddle txn</th>
              <th>Reason</th>
              <th>Age</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {residuals.map((row) => (
              <tr key={row.id}>
                <td>
                  <div className="mono" style={{ fontSize: 11 }}>{row.ownerType}/{row.ownerId}</div>
                </td>
                <td>{fmtCents(row.amountCents)}</td>
                <td className="mono" style={{ fontSize: 11 }}>{row.paymentId || '—'}</td>
                <td className="mono" style={{ fontSize: 11 }}>{row.paddleTransactionId || '—'}</td>
                <td style={{ maxWidth: 280, whiteSpace: 'pre-wrap' }}>{row.reason || '—'}</td>
                <td className="mono">{ageOf(row.createdAt)}</td>
                <td>
                  <button type="button" className="small" onClick={() => startSettleOtp(row)}>
                    Settle
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}

      {otp && otp.kind === 'resolve' && (
        <div className="panel" style={{ marginTop: 16 }}>
          <h2 style={{ marginTop: 0 }}>
            Confirm {otp.params.decision.toLowerCase()} for <code>{otp.params.requestId}</code>
          </h2>
          <p className="sub">
            Amount and decision are the values bound into the OTP. The note is shown to the
            user verbatim and can still be edited.
          </p>
          <div className="row">
            <div>
              <label>Decision</label>
              <input value={otp.params.decision} readOnly />
            </div>
            <div>
              <label>Amount (cents; empty = everything live-refundable)</label>
              <input value={otp.params.amountCents ?? ''} readOnly />
            </div>
          </div>
          <div className="row">
            <div>
              <label>Note to the user</label>
              <input
                value={note}
                onChange={(e) => setNote(e.target.value)}
                maxLength={2000}
                placeholder="Returned to your original card"
              />
            </div>
            <div>
              <label>OTP code (check email / dev DB)</label>
              <input value={code} onChange={(e) => setCode(e.target.value)} maxLength={6} />
            </div>
          </div>
          <div className="row">
            <button
              type="button"
              disabled={executeDisabled}
              onClick={verifyAndExecute}
            >
              Verify &amp; execute
            </button>
            <button type="button" className="ghost" onClick={() => setOtp(null)}>Cancel</button>
          </div>
        </div>
      )}

      {otp && otp.kind === 'settle' && (
        <div className="panel" style={{ marginTop: 16 }}>
          <h2 style={{ marginTop: 0 }}>
            Confirm settle for <code>{otp.params.residualId}</code>
          </h2>
          <p className="sub">
            Settles this residual. Refund-request rows close the request without a wallet credit
            (the Paddle refusal never took the cash). The residual id is bound into the OTP.
          </p>
          <div className="row">
            <div>
              <label>OTP code (check email / dev DB)</label>
              <input value={code} onChange={(e) => setCode(e.target.value)} maxLength={6} />
            </div>
          </div>
          <div className="row">
            <button type="button" disabled={executeDisabled} onClick={verifyAndExecute}>
              Verify &amp; settle
            </button>
            <button type="button" className="ghost" onClick={() => setOtp(null)}>Cancel</button>
          </div>
        </div>
      )}

      {!otp && (
        <div className="panel" style={{ marginTop: 16 }}>
          <label>Approval amount (cents; leave blank for everything currently refundable)</label>
          <input
            type="number"
            min="1"
            value={amountDraft}
            onChange={(e) => setAmountDraft(e.target.value)}
            placeholder="blank = live refundable"
          />
        </div>
      )}
    </>
  )
}
