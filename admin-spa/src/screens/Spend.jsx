import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api.js'
import { addUsageCounts, formatSpendRows } from './spendFormats.js'

/**
 * Spend dashboard — EXACT cents off the money ledgers (entitlements consumptions +
 * billing wallets/transactions/payments); the one estimate (all-phase LLM cost) is
 * labelled as such. Lanes-only model: global tiles split individual vs org accounts
 * as wholes; the drilldown looks up one user (email search, personal lane) or one
 * org (org id, the org as a whole) — no per-member breakdown.
 */

const fmtUsd = (cents) => `$${((cents ?? 0) / 100).toFixed(2)}`
const fmtCount = (n) => (n ?? 0).toLocaleString()
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function Stat({ value, label }) {
  return (
    <div>
      <div className="stat">{value}</div>
      <div className="stat-label">{label}</div>
    </div>
  )
}

function MoneyPanel({ title, money }) {
  return (
    <div className="panel">
      <h2 style={{ marginTop: 0 }}>{title}</h2>
      <div className="stats">
        <Stat value={fmtUsd(money.balanceCents)} label="wallet balance" />
        <Stat value={fmtUsd(money.netSpentCents)} label="net spent" />
        <Stat value={fmtUsd(money.grossSpentCents)} label="gross spent" />
        <Stat value={fmtUsd(money.consumeRefundCents)} label="refunded back" />
        <Stat value={fmtUsd(money.moneyInCents)} label="money in" />
        <Stat value={fmtCount(money.walletChargeCount)} label="wallet charges" />
      </div>
      {(money.stripeCashGrossCents > 0 || money.paymentClawbackCents > 0) && (
        <div className="stats" style={{ marginTop: 10 }}>
          <Stat value={fmtUsd(money.stripeCashGrossCents)} label="card cash collected" />
          <Stat value={fmtUsd(money.stripeCardRefundedCents)} label="returned to card" />
          <Stat value={fmtUsd(money.paymentClawbackCents)} label="wallet clawed back" />
        </div>
      )}
    </div>
  )
}

function FormatSpendPanel({ title, usage, byAction }) {
  const rows = formatSpendRows(usage, byAction)
  return (
    <div className="panel">
      <h2 style={{ marginTop: 0 }}>{title}</h2>
      <p className="sub">
        Unique books that have that bind profile, plus customer wallet spend for
        that publish action (gross, before refunds). A book in two formats
        counts in both rows; a $0 digital first-publish still counts as a book.
      </p>
      <table>
        <thead>
          <tr>
            <th>Format</th>
            <th>Books</th>
            <th>Charges</th>
            <th>Customer $</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.label}>
              <td>{row.label}</td>
              <td>{fmtCount(row.books)}</td>
              <td>{fmtCount(row.chargeCount)}</td>
              <td>{fmtUsd(row.spentCents)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function ByActionPanel({ byAction }) {
  if (!byAction || byAction.length === 0) return null
  return (
    <div className="panel">
      <h2 style={{ marginTop: 0 }}>Spend by action</h2>
      <table>
        <thead>
          <tr><th>Action</th><th>Charges</th><th>Spent (gross)</th></tr>
        </thead>
        <tbody>
          {byAction.map((row) => (
            <tr key={row.action}>
              <td className="mono">{row.action?.toLowerCase().replaceAll('_', ' ')}</td>
              <td>{fmtCount(row.chargeCount)}</td>
              <td>{fmtUsd(row.spentCents)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function FlowsPanel({ flows }) {
  if (!flows) return null
  const rows = [
    ['Last 7 days', flows.weekLoadedCents, flows.weekSpentCents],
    ['Last 30 days', flows.monthLoadedCents, flows.monthSpentCents],
    ['Last 365 days', flows.yearLoadedCents, flows.yearSpentCents],
    ['All time', flows.allTimeLoadedCents, flows.allTimeSpentCents],
  ]
  return (
    <div className="panel">
      <h2 style={{ marginTop: 0 }}>Money flows</h2>
      <p className="sub">Wallet money loaded vs spent per rolling window (refunds excluded from both).</p>
      <table>
        <thead>
          <tr><th>Window</th><th>Loaded</th><th>Spent</th></tr>
        </thead>
        <tbody>
          {rows.map(([label, loaded, spent]) => (
            <tr key={label}>
              <td>{label}</td>
              <td>{fmtUsd(loaded)}</td>
              <td>{fmtUsd(spent)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function relativeTime(iso) {
  if (!iso) return 'never'
  const ms = Date.now() - new Date(iso).getTime()
  if (Number.isNaN(ms)) return 'unknown'
  const mins = Math.round(ms / 60000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins}m ago`
  const hours = Math.round(mins / 60)
  if (hours < 48) return `${hours}h ago`
  return `${Math.round(hours / 24)}d ago`
}

function PaymentsHealthPanel({ health, error }) {
  if (error) {
    return (
      <div className="panel">
        <h2 style={{ marginTop: 0 }}>Payments health</h2>
        <div className="msg error">{error}</div>
      </div>
    )
  }
  if (!health) return null
  const stuckRefunds = health.stuckRefundCount ?? 0
  return (
    <div className="panel">
      <h2 style={{ marginTop: 0 }}>Payments health</h2>
      <p className="sub">
        Paddle integration signal — stuck card refunds, uncredited top-ups, and webhook recency.
      </p>
      <div className="stats">
        <div>
          <div className="stat" style={stuckRefunds > 0 ? { color: '#b42318' } : undefined}>
            {fmtCount(stuckRefunds)}
          </div>
          <div className="stat-label">stuck refunds</div>
        </div>
        <Stat
          value={fmtCount(health.stuckTopupCount)}
          label={`stuck top-ups (${health.stuckTopupThresholdMinutes ?? 15}m+, uncredited)`}
        />
        <Stat value={fmtCount(health.abandonedTopupCount)} label="abandoned top-ups" />
        <Stat value={relativeTime(health.lastCompletedWebhookAt)} label="last webhook" />
        <Stat value={fmtCount(health.completedWebhooks24h)} label="webhooks (24h)" />
      </div>
    </div>
  )
}

/** One usage column (books/chapters figures for a lane or an owner). */
function usageRows(usage) {
  return [
    ['books', fmtCount(usage.books_total)],
    ['generation finished', fmtCount(usage.books_generation_finished)],
    ['published', fmtCount(usage.books_published)],
    ['digital PDF', fmtCount(usage.books_digital)],
    ['printed (KDP)', fmtCount(usage.books_printed)],
    ['kindle', fmtCount(usage.books_kindle)],
    ['epub', fmtCount(usage.books_epub)],
    ['generated chapters', fmtCount(usage.generated_chapters_total)],
    ['avg chapters/book', usage.avg_generated_chapters_per_book ?? 0],
    ['est. LLM cost (all phases)*', fmtUsd(Math.round((usage.estimated_llm_cost_usd ?? 0) * 100))],
    ['Firecrawl scrapes', fmtCount(usage.firecrawl_calls)],
    ['Firecrawl chars', fmtCount(usage.firecrawl_chars)],
  ]
}

function LanesPanel({ lanes }) {
  if (!lanes) return null
  const left = usageRows(lanes.individual)
  const right = usageRows(lanes.org)
  return (
    <div className="panel">
      <h2 style={{ marginTop: 0 }}>Books by account lane</h2>
      <table>
        <thead>
          <tr><th /><th>Individual accounts</th><th>Org accounts</th></tr>
        </thead>
        <tbody>
          {left.map(([label, value], i) => (
            <tr key={label}>
              <td>{label}</td>
              <td>{value}</td>
              <td>{right[i][1]}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="sub">*token-based estimate at current rates — everything else is an exact count.</p>
    </div>
  )
}

function UsagePanel({ usage }) {
  if (!usage) return null
  return (
    <div className="panel">
      <h2 style={{ marginTop: 0 }}>Books & chapters</h2>
      <div className="stats">
        {usageRows(usage).map(([label, value]) => (
          <Stat key={label} value={value} label={label} />
        ))}
      </div>
      <p className="sub">*token-based estimate at current rates — everything else is an exact count.</p>
    </div>
  )
}

export default function Spend() {
  const [global, setGlobal] = useState(null)
  const [flows, setFlows] = useState(null)
  const [lanes, setLanes] = useState(null)
  const [paymentsHealth, setPaymentsHealth] = useState(null)
  const [paymentsHealthError, setPaymentsHealthError] = useState(null)
  const [mode, setMode] = useState('user') // 'user' | 'org'
  const [query, setQuery] = useState('')
  const [results, setResults] = useState([])
  const [noMatches, setNoMatches] = useState(false)
  const [subject, setSubject] = useState(null) // { kind, id, label }
  const [money, setMoney] = useState(null)
  const [usage, setUsage] = useState(null)
  const [usageError, setUsageError] = useState(null)
  const [moneyError, setMoneyError] = useState(null)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  // Stale-response guard: only the LATEST openSubject call may write panel state —
  // without this, a slow earlier lookup can overwrite a faster later one and show
  // one subject's dollars under another subject's header.
  const lookupSeq = useRef(0)

  const loadGlobal = useCallback(async () => {
    const [g, f, l, h] = await Promise.allSettled([
      api.spendGlobal(), api.spendFlows(), api.usageLanes(), api.paymentsHealth(),
    ])
    if (g.status === 'fulfilled') setGlobal(g.value)
    if (f.status === 'fulfilled') setFlows(f.value)
    if (l.status === 'fulfilled') setLanes(l.value)
    if (h.status === 'fulfilled') {
      setPaymentsHealth(h.value)
      setPaymentsHealthError(null)
    } else {
      setPaymentsHealth(null)
      setPaymentsHealthError(h.reason?.message ?? 'payments health unavailable')
    }
    const failed = [g, f, l].filter((r) => r.status === 'rejected')
    if (failed.length > 0) setError(failed[0].reason?.message ?? 'failed to load global figures')
  }, [])

  useEffect(() => { loadGlobal() }, [loadGlobal])

  async function openSubject(kind, id, label) {
    const seq = ++lookupSeq.current
    setError(null)
    setMoneyError(null)
    setUsageError(null)
    setBusy(true)
    setSubject({ kind, id, label })
    setMoney(null)
    setUsage(null)
    // The two legs hit independent backends (admin-api money vs curator usage) —
    // settle them independently so one being down never blanks the other's panel.
    const [moneyResp, usageResp] = await Promise.allSettled([
      kind === 'user' ? api.userSpend(id) : api.orgSpend(id),
      kind === 'user' ? api.usageByUser(id) : api.usageByOrg(id),
    ])
    if (seq !== lookupSeq.current) return // a newer lookup superseded this one
    if (moneyResp.status === 'fulfilled') setMoney(moneyResp.value)
    else setMoneyError(moneyResp.reason?.message ?? 'money figures unavailable')
    if (usageResp.status === 'fulfilled') setUsage(usageResp.value)
    else setUsageError(usageResp.reason?.message ?? 'usage figures unavailable')
    setBusy(false)
  }

  async function lookup(e) {
    e.preventDefault()
    setError(null)
    setNoMatches(false)
    setResults([])
    const q = query.trim()
    if (!q) return
    if (mode === 'org') {
      if (!UUID_RE.test(q)) {
        setError('Org lookup needs a UUID (e.g. 1f0d…-…). Paste the org id.')
        return
      }
      await openSubject('org', q.toLowerCase(), q.toLowerCase())
      return
    }
    setBusy(true)
    try {
      const found = await api.searchUsers(q)
      if (found.length === 0) {
        setNoMatches(true)
        setBusy(false)
      } else if (found.length === 1) {
        await openSubject('user', found[0].userId, found[0].email)
      } else {
        setResults(found)
        setBusy(false)
      }
    } catch (err) {
      setError(err.message)
      setBusy(false)
    }
  }

  return (
    <>
      <h1>Spend</h1>
      <p className="sub">
        Exact wallet money from the billing/entitlements ledgers — balances, spend
        (net of refunds), money flows, and what was made. The only estimate is the
        starred LLM-cost figure.
      </p>
      {error && <div className="msg error">{error}</div>}

      {global && (
        <div className="panel stats">
          <Stat value={fmtUsd(global.totalBalanceCents)} label="held in wallets" />
          <Stat value={fmtUsd(global.userBalanceCents)} label="personal wallets" />
          <Stat value={fmtUsd(global.orgBalanceCents)} label="org wallets" />
          <Stat value={fmtUsd(global.netSpentCents)} label="net spent (all time)" />
          <Stat value={fmtUsd(global.moneyInCents)} label="money in (all time)" />
          <Stat value={fmtUsd(global.stripeCashGrossCents)} label="card cash collected" />
        </div>
      )}
      <FlowsPanel flows={flows} />
      <PaymentsHealthPanel health={paymentsHealth} error={paymentsHealthError} />
      <LanesPanel lanes={lanes} />
      {global && (
        <FormatSpendPanel
          title="Publish by format (platform)"
          usage={addUsageCounts(lanes?.individual, lanes?.org)}
          byAction={global.byAction}
        />
      )}
      {global && <ByActionPanel byAction={global.byAction} />}

      <form className="panel row" onSubmit={lookup}>
        <div>
          <label>Look up</label>
          <div className="row" style={{ gap: 6 }}>
            <button type="button" className={mode === 'user' ? 'small' : 'ghost small'}
                    onClick={() => setMode('user')}>User</button>
            <button type="button" className={mode === 'org' ? 'small' : 'ghost small'}
                    onClick={() => setMode('org')}>Org</button>
          </div>
        </div>
        <div>
          <label htmlFor="spend-q">{mode === 'user' ? 'Email contains' : 'Org ID'}</label>
          <input id="spend-q" value={query} onChange={(e) => setQuery(e.target.value)} required />
        </div>
        <button disabled={busy}>{busy ? 'Loading…' : 'Look up'}</button>
      </form>

      {noMatches && <div className="msg">No users matched that email.</div>}

      {results.length > 1 && (
        <div className="panel">
          <table>
            <thead>
              <tr><th>Email</th><th>User ID</th><th /></tr>
            </thead>
            <tbody>
              {results.map((u) => (
                <tr key={u.userId}>
                  <td>{u.email}</td>
                  <td className="mono">{u.userId}</td>
                  <td>
                    <button className="small" disabled={busy}
                            onClick={() => openSubject('user', u.userId, u.email)}>Open</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {subject && (
        <>
          {moneyError && <div className="msg error">Money figures: {moneyError}</div>}
          {money && (
            <>
              <MoneyPanel
                title={`${subject.kind === 'user' ? 'User (personal lane)' : 'Org (as a whole)'} · ${subject.label}${money.walletExists ? '' : ' (no wallet provisioned)'}`}
                money={money}
              />
              <ByActionPanel byAction={money.byAction} />
            </>
          )}
          {usageError && <div className="msg error">Usage figures: {usageError}</div>}
          {(money || usage) && (
            <FormatSpendPanel
              title="Publish by format"
              usage={usage}
              byAction={money?.byAction}
            />
          )}
          <UsagePanel usage={usage} />
        </>
      )}
    </>
  )
}
