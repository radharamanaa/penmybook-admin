import { useCallback, useEffect, useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import { api } from '../api.js'

const TITLES = {
  tiers: 'Tiers & caps',
  safety: 'Content safety',
  moderation: 'Moderation & access',
  budgets: 'Generation & research budgets',
  cost_rates: 'Cost-accounting rates',
}

const BLURBS = {
  tiers: 'Chapter limits, planning token caps, conversation caps, publish attempt cap.',
  safety:
    'Azure severity thresholds, the kill switch, the semantic gate, Tier-2 + prompt shield. ' +
    'Pushes propagate near-instantly (cache bust).',
  moderation:
    'Waitlist mode + the graduated guardrail ban: strikes per chat → chat lock; locked chats → suspend for review.',
  budgets:
    'The cost levers: Perplexity tier (≈5.6×/query between sonar and sonar-pro), research query budgets, ' +
    'image caps, retries. TTL propagation (≤ ~35s).',
  cost_rates: 'Azure $/M token meter rates used by the analytics cost math. Forward-only (D14).',
}

/**
 * Generic, registry-driven section editor: renders every knob the curator's
 * ingestion endpoint declares for this section, showing override vs env
 * default. The SPA never hardcodes the knob list — the backend registry
 * (internal_admin_config._SECTION_KEYS) is the single source of truth.
 */
export default function ConfigSection() {
  const { section } = useParams()
  const [config, setConfig] = useState(null)
  const [draft, setDraft] = useState({})
  const [error, setError] = useState(null)
  const [ok, setOk] = useState(null)

  const load = useCallback(async () => {
    setError(null)
    setDraft({})
    try {
      setConfig(await api.getConfig())
    } catch (err) {
      setError(err.message)
    }
  }, [])

  useEffect(() => { load() }, [load, section])

  const keys = useMemo(() => config?.sections?.[section] ?? [], [config, section])

  if (error && !config) return <p className="sub">{error}</p>
  if (!config) return <p className="sub">Loading…</p>

  const overrides = config.overrides ?? {}
  const defaults = config.env_defaults ?? {}

  function effective(key) {
    return overrides[key] !== undefined ? overrides[key] : defaults[key]
  }

  // A draft value really differs from the effective value? Number-typed knobs
  // (the cost-rate boxes) hold a RAW STRING so decimals can be typed, so compare
  // them numerically rather than by identity.
  function isChanged(key) {
    if (draft[key] === undefined) return false
    const v = draft[key]
    const meta = config.metadata?.[key]
    if (meta?.type === 'number' && typeof v === 'string') {
      if (v.trim() === '') return false
      return Number(v) !== Number(effective(key))
    }
    return v !== effective(key)
  }

  // Coerce a draft value to its wire type at save (number boxes hold strings).
  function coerce(key, v) {
    const meta = config.metadata?.[key]
    return meta?.type === 'number' && typeof v === 'string' ? Number(v) : v
  }

  // Every knob renders as a <select> — no free-form input. The backend metadata
  // supplies the type + the curated option set (enum members, true/false, or
  // operator-friendly numeric steps within the knob's bounds).
  function fieldFor(key) {
    const meta = config.metadata?.[key] ?? {}
    const value = draft[key] !== undefined ? draft[key] : effective(key)
    const dirty = isChanged(key) ? 'dirty' : ''

    if (meta.type === 'bool') {
      return (
        <select className={dirty} value={String(value ?? false)}
                onChange={(e) => setDraft({ ...draft, [key]: e.target.value === 'true' })}>
          <option value="true">true</option>
          <option value="false">false</option>
        </select>
      )
    }

    const opts = meta.options ?? []
    if (opts.length) {
      const isNum = meta.type === 'number'
      // If the effective value isn't one of the curated options (e.g. a hand-set
      // override or an env default off the list), surface it so it isn't lost.
      const inList = opts.map(String).includes(String(value))
      return (
        <select className={dirty} value={String(value ?? '')}
                onChange={(e) => setDraft({ ...draft, [key]: isNum ? Number(e.target.value) : e.target.value })}>
          {!inList && value !== null && value !== undefined && value !== '' && (
            <option value={String(value)}>{String(value)} (current)</option>
          )}
          {opts.map((o) => (
            <option key={String(o)} value={String(o)}>{String(o)}</option>
          ))}
        </select>
      )
    }

    // Bounded number box — cost-rate $ values (type number, no preset options).
    // Raw string is stored so decimals type cleanly; coerced to a number at save.
    if (meta.type === 'number') {
      return (
        <input type="number" className={dirty}
               min={meta.min} max={meta.max} step="0.01"
               value={value ?? ''}
               onChange={(e) => setDraft({ ...draft, [key]: e.target.value })} />
      )
    }

    // Fallback only if metadata is missing entirely.
    return (
      <input value={value ?? ''} className={dirty}
             onChange={(e) => setDraft({ ...draft, [key]: e.target.value })} />
    )
  }

  async function save() {
    setError(null)
    setOk(null)
    const changes = {}
    for (const k of Object.keys(draft)) {
      if (isChanged(k)) changes[k] = coerce(k, draft[k])
    }
    if (!Object.keys(changes).length) return
    try {
      await api.putConfigSection(section, changes)
      setOk(`Applied ${Object.keys(changes).length} change(s) — BookForge confirmed.`)
      await load()
    } catch (err) {
      setError(err.message)
    }
  }

  const dirtyCount = Object.keys(draft).filter((k) => isChanged(k)).length

  return (
    <>
      <h1>{TITLES[section] ?? section}</h1>
      <p className="sub">{BLURBS[section] ?? ''}</p>
      {error && <div className="msg error">{error}</div>}
      {ok && <div className="msg ok">{ok}</div>}
      <div className="panel">
        <div className="settings-grid">
          <div className="head">Setting</div>
          <div className="head">Value</div>
          {keys.map((key) => (
            <SettingRow key={key} name={key} field={fieldFor(key)}
                        meta={config.metadata?.[key]}
                        isOverridden={overrides[key] !== undefined}
                        envDefault={defaults[key]} />
          ))}
        </div>
        <button onClick={save} disabled={dirtyCount === 0}>
          Apply {dirtyCount > 0 ? `${dirtyCount} change(s)` : 'changes'}
        </button>
      </div>
    </>
  )
}

// The operator sees a plain-language one-liner; the raw param key (used for the
// API), its allowed values/range, and the env default live behind the ℹ️.
function SettingRow({ name, field, meta, isOverridden, envDefault }) {
  const [showInfo, setShowInfo] = useState(false)
  const label = meta?.description || name
  const allowed =
    meta?.type === 'enum'
      ? `Options: ${(meta.options || []).join(' · ')}`
      : meta?.type === 'bool'
        ? 'Options: true · false'
        : meta?.min !== undefined
          ? `Range: ${meta.min} – ${meta.max}`
          : null
  const def = envDefault === null || envDefault === undefined ? '—' : String(envDefault)
  return (
    <>
      <div className="setting-cell">
        <div className="setting-label">
          <span>{label}</span>
          <button type="button" className="info-btn" title={`${name} — ${label}`}
                  aria-label={`Details for ${label}`} aria-expanded={showInfo}
                  onClick={() => setShowInfo((s) => !s)}>i</button>
          {isOverridden && <span className="badge neutral">overridden</span>}
        </div>
        {showInfo && (
          <div className="info-pop">
            <div><code>{name}</code></div>
            {allowed && <div className="info-meta">{allowed}</div>}
            <div className="info-meta">Default: <span className="mono">{def}</span></div>
          </div>
        )}
      </div>
      <div className="value-cell">{field}</div>
    </>
  )
}
