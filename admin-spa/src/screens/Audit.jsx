import { useCallback, useEffect, useState } from 'react'
import { api } from '../api.js'

/** The append-only admin audit trail (who changed which knob, old→new, when). */
export default function Audit() {
  const [entries, setEntries] = useState([])
  const [error, setError] = useState(null)

  const load = useCallback(async () => {
    try {
      setEntries(await api.listAudit(150))
    } catch (err) {
      setError(err.message)
    }
  }, [])

  useEffect(() => { load() }, [load])

  return (
    <>
      <h1>Audit log</h1>
      <p className="sub">
        Every login, config push, account change and money action — newest first.
        Money ops additionally audit downstream in entitlements.
      </p>
      {error && <div className="msg error">{error}</div>}
      <div className="panel">
        <div className="table-wrap">
          <table>
            <thead>
              <tr><th>When</th><th>Actor</th><th>Action</th><th>Target</th><th>Outcome</th><th>Change</th></tr>
            </thead>
            <tbody>
              {entries.map((e, i) => (
                <tr key={i}>
                  <td className="mono">{e.ts}</td>
                  <td>{e.actor_email}</td>
                  <td><code>{e.action}</code></td>
                  <td className="mono">{e.target}</td>
                  <td>
                    <span className={`badge ${e.outcome === 'allow' ? 'on' : 'off'}`}>{e.outcome}</span>
                  </td>
                  <td className="mono" style={{ maxWidth: 320, overflowWrap: 'anywhere' }}>
                    {e.old || e.new
                      ? `${JSON.stringify(e.old ?? {})} → ${JSON.stringify(e.new ?? {})}`
                      : e.detail}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}
