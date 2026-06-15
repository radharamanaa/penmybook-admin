import { useEffect, useState } from 'react'
import { api } from '../api.js'

const ACTIONS = [
  { value: 'CHAPTER_GENERATE', label: 'Chapter generation' },
  { value: 'COVER_GENERATE', label: 'Cover generation' },
  { value: 'CHAPTER_EDIT', label: 'Chapter edit overflow' },
  { value: 'COVER_REGEN', label: 'Cover regen overflow' },
  { value: 'PUBLISH_DIGITAL', label: 'Publish — digital PDF' },
  { value: 'PUBLISH_PAPERBACK', label: 'Publish — paperback' },
  { value: 'PUBLISH_KINDLE', label: 'Publish — Kindle' },
]

const dollars = (cents) =>
  cents === null || cents === undefined || cents === '' || Number.isNaN(Number(cents))
    ? '—'
    : `$${(Number(cents) / 100).toFixed(2)}`

/**
 * Inline price editor: each action's price is edited directly in the table (cents,
 * with the $ updating live). Leave Org ID empty to edit the GLOBAL default; set it
 * to save the edits as that org's overrides (admin-api → entitlements pricing_rules).
 */
export default function Pricing() {
  const [current, setCurrent] = useState(null) // { ACTION: cents }
  const [drafts, setDrafts] = useState({}) // { ACTION: centsString } — raw while editing
  const [orgId, setOrgId] = useState('')
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const [loadErr, setLoadErr] = useState(null)

  async function loadCurrent() {
    try {
      setCurrent(await api.getPricing())
      setLoadErr(null)
    } catch (err) {
      setCurrent({})
      setLoadErr(err?.message || 'request failed')
    }
  }
  useEffect(() => { loadCurrent() }, [])

  function centsFor(action) {
    return drafts[action] !== undefined ? drafts[action] : current?.[action] ?? ''
  }
  function rowChanged(action) {
    const d = drafts[action]
    if (d === undefined || String(d).trim() === '') return false
    return Number(d) !== Number(current?.[action])
  }
  const dirty = ACTIONS.filter((a) => rowChanged(a.value))

  async function apply() {
    setError(null)
    setResult(null)
    if (!dirty.length) return
    try {
      for (const a of dirty) {
        const cents = Number(drafts[a.value])
        if (orgId.trim()) await api.upsertOrgPricing(orgId.trim(), a.value, cents)
        else await api.upsertGlobalPricing(a.value, cents)
      }
      setResult({ count: dirty.length, org: orgId.trim() || null })
      setDrafts({})
      await loadCurrent()
    } catch (err) {
      setError(err.message)
    }
  }

  return (
    <>
      <h1>Pricing &amp; billing</h1>
      <p className="sub">
        Customer prices, resolved per charge: org override → global rule → config default.
        $0 is legal (digital publish is free, D13). Changes are versioned, audited, and live
        within the 30s pricing cache. Discounts are deferred (D10).
      </p>

      {loadErr && (
        <div className="msg error">
          Couldn't load current prices — {loadErr}.{' '}
          <button type="button" className="ghost small" onClick={loadCurrent}>Retry</button>
        </div>
      )}
      {error && <div className="msg error">{error}</div>}
      {result && (
        <div className="msg ok">
          Applied {result.count} price change(s)
          {result.org ? <> for org <code>{result.org}</code></> : ' (global)'} — PenMyBook confirmed.
        </div>
      )}

      <div className="panel">
        <div style={{ maxWidth: 460, marginBottom: 14 }}>
          <label htmlFor="org" style={{ marginTop: 0 }}>Org ID — leave empty to edit the GLOBAL default</label>
          <input id="org" placeholder="optional org UUID — set to save edits as that org's overrides"
                 value={orgId} onChange={(e) => setOrgId(e.target.value)} />
        </div>

        <table>
          <thead>
            <tr><th>Action</th><th>Price</th><th style={{ width: 170 }}>Cents</th></tr>
          </thead>
          <tbody>
            {ACTIONS.map((a) => {
              const v = centsFor(a.value)
              const changed = rowChanged(a.value)
              return (
                <tr key={a.value}>
                  <td>{a.label}</td>
                  <td style={changed ? { color: 'var(--accent)', fontWeight: 600 } : undefined}>
                    {current == null ? '…' : dollars(v)}
                  </td>
                  <td>
                    <input type="number" min="0" max="100000" step="1"
                           className={changed ? 'dirty' : ''}
                           value={current == null ? '' : v}
                           onChange={(e) => setDrafts({ ...drafts, [a.value]: e.target.value })}
                           style={{ maxWidth: 140 }} />
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>

        <p className="sub" style={{ fontSize: '0.78rem', margin: '10px 0 14px' }}>
          {orgId.trim()
            ? <>Edits save as overrides for org <code>{orgId.trim()}</code>; the table shows the global baseline.</>
            : <>Editing the global defaults. Per-org overrides (if any) win at charge time.</>}
        </p>

        <button onClick={apply} disabled={!dirty.length}>
          Apply {dirty.length ? `${dirty.length} change(s)` : 'changes'}
        </button>
      </div>
    </>
  )
}
