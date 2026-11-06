import { useEffect, useState } from 'react'
import { api } from '../api.js'

/** Row key = action, or action:category for per-genre chapter prices. */
const ACTIONS = [
  { value: 'CHAPTER_GENERATE:technical', action: 'CHAPTER_GENERATE', category: 'technical', label: 'Chapter — technical (¢/subtopic)' },
  { value: 'CHAPTER_GENERATE:science_fiction', action: 'CHAPTER_GENERATE', category: 'science_fiction', label: 'Chapter — science fiction (¢/subtopic)' },
  { value: 'CHAPTER_GENERATE:literary_fiction', action: 'CHAPTER_GENERATE', category: 'literary_fiction', label: 'Chapter — literary fiction (¢/subtopic)' },
  { value: 'CHAPTER_GENERATE:screenplay', action: 'CHAPTER_GENERATE', category: 'screenplay', label: 'Chapter — screenplay (¢/subtopic)' },
  { value: 'CHAPTER_GENERATE:childrens', action: 'CHAPTER_GENERATE', category: 'childrens', label: "Chapter — children's (¢/subtopic)" },
  { value: 'COVER_GENERATE', action: 'COVER_GENERATE', category: null, label: 'Cover generation' },
  { value: 'CHAPTER_EDIT', action: 'CHAPTER_EDIT', category: null, label: 'Chapter edit overflow' },
  { value: 'COVER_REGEN', action: 'COVER_REGEN', category: null, label: 'Cover regen overflow' },
  { value: 'PUBLISH_DIGITAL', action: 'PUBLISH_DIGITAL', category: null, label: 'Publish — digital PDF' },
  { value: 'PUBLISH_PAPERBACK', action: 'PUBLISH_PAPERBACK', category: null, label: 'Publish — paperback' },
  { value: 'PUBLISH_KINDLE', action: 'PUBLISH_KINDLE', category: null, label: 'Publish — Kindle' },
  { value: 'PUBLISH_EPUB', action: 'PUBLISH_EPUB', category: null, label: 'Publish — EPUB' },
  // Owner, 2026-11-05: settable from $0 to $10 only (entitlements refuses more).
  { value: 'PUBLISH_LINKEDIN', action: 'PUBLISH_LINKEDIN', category: null, label: 'Publish — LinkedIn PDF ($0–$10)', maxCents: 1000 },
]

const dollars = (cents) =>
  cents === null || cents === undefined || cents === '' || Number.isNaN(Number(cents))
    ? '—'
    : `$${(Number(cents) / 100).toFixed(2)}`

/** The first edit above its row's ceiling, as a message; null when every edit fits. */
export function ceilingError(rows, drafts) {
  const over = rows.find((a) => a.maxCents !== undefined && Number(drafts[a.value]) > a.maxCents)
  return over ? `${over.label}: the highest allowed price is ${dollars(over.maxCents)}.` : null
}

/**
 * Inline price editor: each action's price is edited directly in the table (cents,
 * with the $ updating live). Leave Org ID empty to edit the GLOBAL default; set it
 * to save the edits as that org's overrides (admin-api → entitlements pricing_rules).
 */
export default function Pricing() {
  const [current, setCurrent] = useState(null) // { ACTION or ACTION:genre: cents }
  const [drafts, setDrafts] = useState({}) // { key: centsString } — raw while editing
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

  function centsFor(key) {
    return drafts[key] !== undefined ? drafts[key] : current?.[key] ?? ''
  }
  function rowChanged(key) {
    const d = drafts[key]
    if (d === undefined || String(d).trim() === '') return false
    return Number(d) !== Number(current?.[key])
  }
  const dirty = ACTIONS.filter((a) => rowChanged(a.value))

  async function apply() {
    setError(null)
    setResult(null)
    if (!dirty.length) return
    const tooHigh = ceilingError(dirty, drafts)
    if (tooHigh) {
      setError(tooHigh)
      return
    }
    try {
      for (const a of dirty) {
        const cents = Number(drafts[a.value])
        if (orgId.trim()) {
          await api.upsertOrgPricing(orgId.trim(), a.action, cents, a.category)
        } else {
          await api.upsertGlobalPricing(a.action, cents, a.category)
        }
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
        Chapter generation is priced per subtopic by genre. $0 is legal (digital publish is
        free, D13). Changes are versioned, audited, and live within the 30s pricing cache.
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

        <div className="table-wrap">
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
                      <input type="number" inputMode="numeric" min="0" max={a.maxCents ?? 100000} step="1"
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
        </div>

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
