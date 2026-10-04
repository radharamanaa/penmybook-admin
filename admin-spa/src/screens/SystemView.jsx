import { useEffect, useState } from 'react'
import { api } from '../api.js'

/**
 * System (read-only): every env default the runtime knows about, with the
 * stored override when one exists. Knobs NOT in a live section (deep engine
 * tuning, model deployments) change via deploy — this view is the inventory.
 */
export default function SystemView() {
  const [config, setConfig] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    api.getConfig().then(setConfig).catch((err) => setError(err.message))
  }, [])

  if (error) return <p className="sub">{error}</p>
  if (!config) return <p className="sub">Loading…</p>

  const defaults = config.env_defaults ?? {}
  const overrides = config.overrides ?? {}
  const keys = Object.keys(defaults).sort()

  return (
    <>
      <h1>System (read-only)</h1>
      <p className="sub">
        Effective values across every registered knob. Anything without a live
        section (see docs/admin-control-plane/PARAMETERS.md) changes via deploy.
      </p>
      <div className="panel">
        <div className="table-wrap">
          <table>
            <thead><tr><th>Parameter</th><th>Effective value</th><th>Source</th></tr></thead>
            <tbody>
              {keys.map((k) => {
                const overridden = overrides[k] !== undefined
                const value = overridden ? overrides[k] : defaults[k]
                return (
                  <tr key={k}>
                    <td><code>{k}</code></td>
                    <td className="mono">{value === null || value === undefined ? '—' : String(value)}</td>
                    <td>
                      <span className={`badge ${overridden ? 'on' : 'neutral'}`}>
                        {overridden ? 'admin override' : 'env default'}
                      </span>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}
