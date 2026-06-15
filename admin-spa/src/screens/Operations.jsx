import { useCallback, useEffect, useState } from 'react'
import { api } from '../api.js'

/** Operator levers (bucket A) + the live generation status panel. */
export default function Operations() {
  const [settings, setSettings] = useState(null)
  const [status, setStatus] = useState(null)
  const [cap, setCap] = useState('')
  const [error, setError] = useState(null)
  const [ok, setOk] = useState(null)

  const load = useCallback(async () => {
    try {
      const [s, st] = await Promise.all([api.getSettings(), api.getStatus()])
      setSettings(s)
      setStatus(st)
      setCap(String(s.max_concurrent_generations))
    } catch (err) {
      setError(err.message)
    }
  }, [])

  useEffect(() => {
    load()
    const t = setInterval(load, 15000)
    return () => clearInterval(t)
  }, [load])

  async function patch(changes) {
    setError(null)
    setOk(null)
    try {
      const updated = await api.patchSettings(changes)
      setSettings(updated)
      setCap(String(updated.max_concurrent_generations))
      setOk('Applied — BookForge confirmed the change.')
    } catch (err) {
      setError(err.message)
    }
  }

  if (!settings) return <p className="sub">{error ?? 'Loading…'}</p>

  return (
    <>
      <h1>Operations</h1>
      <p className="sub">
        Live operator levers. Changes push to BookForge first; nothing is recorded
        until BookForge confirms.
      </p>
      {error && <div className="msg error">{error}</div>}
      {ok && <div className="msg ok">{ok}</div>}

      <div className="panel">
        <div className="row">
          <div>
            <label>Generation paused</label>
            <span className={`badge ${settings.generation_paused ? 'off' : 'on'}`}>
              {settings.generation_paused ? 'PAUSED' : 'running'}
            </span>
          </div>
          <button
            className={settings.generation_paused ? '' : 'danger'}
            onClick={() => patch({ generation_paused: !settings.generation_paused })}
          >
            {settings.generation_paused ? 'Resume generation' : 'Pause all generation'}
          </button>
        </div>
        <div className="row">
          <div>
            <label>LLM quota gate</label>
            <span className={`badge ${settings.llm_quota_available ? 'on' : 'off'}`}>
              {settings.llm_quota_available ? 'available' : 'EXHAUSTED (worker-flipped)'}
            </span>
          </div>
          {!settings.llm_quota_available && (
            <button onClick={() => patch({ llm_quota_available: true })}>
              Quota restored — re-enable
            </button>
          )}
        </div>
        <div className="row">
          <div>
            <label htmlFor="cap">Max concurrent generations (1–50)</label>
            <input id="cap" type="number" min="1" max="50" value={cap}
                   className={cap !== String(settings.max_concurrent_generations) ? 'dirty' : ''}
                   onChange={(e) => setCap(e.target.value)} />
          </div>
          <button
            disabled={cap === String(settings.max_concurrent_generations)}
            onClick={() => patch({ max_concurrent_generations: Number(cap) })}
          >
            Apply cap
          </button>
        </div>
        <p className="sub" style={{ marginTop: 12 }}>
          Last changed by <code>{settings.updated_by}</code> at{' '}
          <code>{settings.updated_at}</code>
        </p>
      </div>

      {status && (
        <div className="panel">
          <h2 style={{ marginTop: 0 }}>Live status</h2>
          <div className="stats">
            <div><div className="stat">{status.active_generation_slots}</div>
              <div className="stat-label">slots held</div></div>
            <div><div className="stat">{status.pending_tasks}</div>
              <div className="stat-label">pending tasks</div></div>
            <div><div className="stat">{status.processing_tasks}</div>
              <div className="stat-label">processing</div></div>
          </div>
          <h2>Books by lifecycle status</h2>
          <table>
            <tbody>
              {Object.entries(status.counts_by_status ?? {}).map(([k, v]) => (
                <tr key={k}><td className="mono">{k}</td><td>{v}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}
