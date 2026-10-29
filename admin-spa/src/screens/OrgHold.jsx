import { useState } from 'react'
import { api } from '../api.js'

const HOLD_NOTE_MIN_LENGTH = 8

function formatDollars(cents) {
  return `$${(cents / 100).toFixed(2)}`
}

/**
 * An org wallet freeze (a chargeback on an org top-up) and its OTP lift (F3). The same
 * `wallet_unfreeze` step-up as a user wallet, bound to {ownerType: ORG, ownerId: orgId}. The hold
 * also suspends every member; staff reactivate each member from their own Manage panel.
 */
export default function OrgHold() {
  const [orgId, setOrgId] = useState('')
  const [state, setState] = useState(null)
  const [note, setNote] = useState('')
  const [otp, setOtp] = useState(null) // {challengeId, params, note}
  const [code, setCode] = useState('')
  const [ok, setOk] = useState(null)
  const [error, setError] = useState(null)

  async function check(targetOrgId = orgId.trim()) {
    setError(null)
    try {
      setState(await api.orgWalletState(targetOrgId))
    } catch (err) {
      setState(null)
      setError(err.message)
    }
  }

  async function startOtp() {
    const params = { ownerType: 'ORG', ownerId: state.orgId }
    try {
      const resp = await api.otpStart('wallet_unfreeze', state.orgId, params)
      setOtp({ challengeId: resp.challengeId, params, note: note.trim() })
      setCode('')
    } catch (err) { setError(err.message) }
  }

  async function verifyAndExecute() {
    const target = state.orgId
    try {
      const v = await api.otpVerify(otp.challengeId, code, 'wallet_unfreeze', target, otp.params)
      if (!v.verified) { setError(`Code rejected: ${v.failureReason ?? 'invalid'}`); return }
      const r = await api.unfreezeOrgWallet(target, otp.note, v.stepUpToken)
      setOk(r.lifted ? 'Org wallet unfrozen.' : 'The org wallet was not frozen.')
      setError(null)
      setOtp(null)
    } catch (err) {
      setError(err.message)
    }
    // Re-read either way: a lift whose reply was lost may still have happened.
    await check(target)
  }

  const noteReady = note.trim().length >= HOLD_NOTE_MIN_LENGTH

  return (
    <div className="panel">
      <h2 style={{ marginTop: 0 }}>Org wallet hold</h2>
      {error && <div className="msg error">{error}</div>}
      {ok && <div className="msg ok">{ok}</div>}
      <form className="row" onSubmit={(e) => { e.preventDefault(); check() }}>
        <div>
          <label htmlFor="org-hold-id">Org ID</label>
          <input id="org-hold-id" value={orgId} onChange={(e) => setOrgId(e.target.value)} required />
        </div>
        <button>Check org wallet</button>
      </form>

      {state && !state.walletFrozen && <p className="sub">The org wallet is not frozen.</p>}
      {state && state.walletFrozen && (
        <div className="msg error" role="status">
          <p style={{ marginTop: 0 }}>
            Org wallet frozen (<code>{state.walletFrozenReason}</code>)
          </p>
          {state.unrecoveredCents > 0 && (
            <p>
              {formatDollars(state.unrecoveredCents)} could not be taken back by a refund or dispute
              (already spent). Unfreezing does not settle it.
            </p>
          )}
          <p>The hold also suspended every member: reactivate each member from Users &amp; money.</p>
          <div className="row">
            <div>
              <label htmlFor="org-hold-note">Why are you lifting the hold?</label>
              <input id="org-hold-note" value={note} onChange={(e) => setNote(e.target.value)}
                     placeholder="e.g. dispute du_… won in Stripe" />
            </div>
            <button disabled={!noteReady} onClick={startOtp}>Start OTP unfreeze org wallet</button>
          </div>
        </div>
      )}

      {otp && (
        <div className="row" style={{ marginTop: 14 }}>
          <div>
            <label htmlFor="org-hold-otp">OTP code for <code>wallet_unfreeze</code> (check email / dev DB)</label>
            <input id="org-hold-otp" inputMode="numeric" autoComplete="one-time-code" value={code}
                   onChange={(e) => setCode(e.target.value)} maxLength={6} />
          </div>
          <button disabled={code.length < 6} onClick={verifyAndExecute}>Verify & execute</button>
          <button className="ghost" onClick={() => setOtp(null)}>Cancel</button>
        </div>
      )}
    </div>
  )
}
