import { useCallback, useEffect, useState } from 'react'
import { api } from '../api.js'

/**
 * Closed-beta waitlist (D11; fair auto-admit 2026-06-28): the week has a single approval
 * budget. While slots remain AND nobody is already waiting, a new signup is auto-admitted
 * (ACTIVE) and consumes one slot; the operator admitting a waiting tester consumes one too —
 * both draw down the SAME weekly budget, so "slots remaining" falls on either event. Once the
 * slots are used up (or a queue has formed), new signups wait here until the operator drains
 * the queue or raises the limit. This screen owns that budget + a browse-and-admit list of
 * everyone currently waiting.
 */
export default function Waitlist() {
  const [config, setConfig] = useState(null)
  const [limitDraft, setLimitDraft] = useState('')
  const [entries, setEntries] = useState([])
  const [error, setError] = useState(null)
  const [ok, setOk] = useState(null)

  const load = useCallback(async () => {
    try {
      const [cfg, queue] = await Promise.all([api.waitlistConfig(), api.waitlistQueue(100)])
      setConfig(cfg)
      setLimitDraft(String(cfg.weeklyApprovalLimit))
      setEntries(queue)
    } catch (err) {
      setError(err.message)
    }
  }, [])

  useEffect(() => { load() }, [load])

  async function saveConfig(next) {
    setError(null)
    setOk(null)
    try {
      const cfg = await api.updateWaitlistConfig(next.enabled, next.weeklyApprovalLimit)
      setConfig(cfg)
      setLimitDraft(String(cfg.weeklyApprovalLimit))
      setOk('Waitlist settings updated.')
    } catch (err) {
      setError(err.message)
    }
  }

  async function admit(entry) {
    setError(null)
    setOk(null)
    try {
      const r = await api.waitlistAdmit(entry.userId)
      if (r.admitted) {
        setOk(`${entry.email} admitted — account is now ACTIVE.`)
      } else if (r.reason === 'weekly_limit_reached') {
        setError(`This week's approval limit is reached. Raise the limit to admit more.`)
      } else {
        setOk(`${entry.email} is already ${r.accountStatus ?? 'not waitlisted'} (no-op).`)
      }
      await load()
    } catch (err) {
      setError(err.message)
    }
  }

  if (!config) return <p className="sub">{error ?? 'Loading…'}</p>

  const remaining = Math.max(0, config.weeklyApprovalLimit - config.admittedThisWeek)

  return (
    <>
      <h1>Waitlist</h1>
      <p className="sub">
        Closed beta — while this week still has slots free and nobody is waiting, new signups
        are let straight in automatically; once the slots are used up (or a queue has formed)
        signups wait here. Both those auto-admissions and the approvals you make use the same
        weekly slots, so <strong>slots remaining</strong> drops on either (resets Sunday). To
        admit a specific waiting person, approve them here or search their email under{' '}
        <strong>Users &amp; money</strong>; raise the limit to admit more.
      </p>
      {error && <div className="msg error">{error}</div>}
      {ok && <div className="msg ok">{ok}</div>}

      <div className="panel">
        <h2 style={{ marginTop: 0 }}>This week</h2>
        <div className="stats">
          <div><div className="stat">{config.admittedThisWeek}</div>
            <div className="stat-label">admitted (incl. auto)</div></div>
          <div><div className="stat">{config.weeklyApprovalLimit}</div>
            <div className="stat-label">weekly limit</div></div>
          <div><div className="stat">{remaining}</div>
            <div className="stat-label">slots remaining</div></div>
          <div>
            <span className={`badge ${config.enabled ? 'on' : 'off'}`}>
              {config.enabled ? 'waitlist ON' : 'waitlist OFF (open signup)'}
            </span>
          </div>
        </div>
        <div className="row">
          <div>
            <label htmlFor="limit">Weekly approval limit</label>
            <input id="limit" type="number" min="0" value={limitDraft}
                   className={limitDraft !== String(config.weeklyApprovalLimit) ? 'dirty' : ''}
                   onChange={(e) => setLimitDraft(e.target.value)} />
          </div>
          <button
            disabled={limitDraft === String(config.weeklyApprovalLimit)}
            onClick={() => saveConfig({ weeklyApprovalLimit: Number(limitDraft) })}
          >
            Save limit
          </button>
          <button className="ghost"
                  onClick={() => saveConfig({ enabled: !config.enabled })}>
            {config.enabled ? 'Turn waitlist OFF (open signups)' : 'Turn waitlist ON'}
          </button>
        </div>
      </div>

      <div className="panel">
        <h2 style={{ marginTop: 0 }}>Waiting ({entries.length})</h2>
        {entries.length === 0 ? (
          <p className="sub" style={{ margin: 0 }}>Nobody is waiting.</p>
        ) : (
          <table>
            <thead>
              <tr><th>#</th><th>Email</th><th>Signed up</th><th /></tr>
            </thead>
            <tbody>
              {entries.map((e, i) => (
                <tr key={e.userId}>
                  <td>{i + 1}</td>
                  <td>{e.email}</td>
                  <td className="mono">{e.createdAt}</td>
                  <td>
                    <button className="small" disabled={remaining <= 0}
                            onClick={() => admit(e)}>Admit</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  )
}
