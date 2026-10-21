/**
 * Per-book paid-request counts and LLM tokens, from the curator's books list
 * (`calls` + `tokens_by_bucket` on each row). Counts only — calls are never priced.
 * An older curator or a book with no data sends neither; that renders a note, never a crash.
 */

const SYSTEM_LABELS = {
  firecrawl: 'Firecrawl',
  perplexity: 'Perplexity',
  tavily: 'Tavily',
  serper: 'Serper',
  content_safety: 'Azure Content Safety',
  prompt_shields: 'Prompt Shields',
}
const SYSTEM_ORDER = Object.keys(SYSTEM_LABELS)
const FAILED_SUFFIX = '_failed'
const MODE_SUFFIX = '_mode'

const BUCKET_LABELS = {
  generation: 'Writing',
  planning: 'Planning',
  editing: 'Editing',
  cover: 'Cover',
  code_notes: 'Code notes',
  package_judge: 'Package check',
  research: 'Research',
  guard: 'Safety checks',
}
const BUCKET_ORDER = Object.keys(BUCKET_LABELS)

export const NO_CALL_DATA = 'No call data recorded'
const NO_TOKEN_DATA = 'No token data recorded'

const isCount = (value) => Number.isInteger(value)
const fmtCount = (value) => (isCount(value) ? value.toLocaleString() : '—')
const isPlainObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value)

/** Positive `{mode: count}` entries of a stored mode map, largest first. */
function modeRows(modes) {
  if (!isPlainObject(modes)) return []
  return Object.entries(modes)
    .filter(([, count]) => isCount(count) && count > 0)
    .sort(([, a], [, b]) => b - a)
}

/** The systems a book sent requests to (or saw failures from), known systems first, then any new key. */
export function callRows(calls) {
  if (!isPlainObject(calls)) return []
  const systems = Object.keys(calls).filter(
    (key) => isCount(calls[key]) && !key.endsWith(FAILED_SUFFIX),
  )
  const ordered = [
    ...SYSTEM_ORDER.filter((key) => systems.includes(key)),
    ...systems.filter((key) => !SYSTEM_ORDER.includes(key)).sort(),
  ]
  return ordered
    .map((system) => ({
      system,
      label: SYSTEM_LABELS[system] ?? system,
      requests: calls[system],
      failed: isCount(calls[`${system}${FAILED_SUFFIX}`]) ? calls[`${system}${FAILED_SUFFIX}`] : 0,
      modes: modeRows(calls[`${system}${MODE_SUFFIX}`]),
    }))
    .filter((row) => row.requests > 0 || row.failed > 0)
}

/** The token buckets a book recorded, in display order, then any new bucket. */
export function tokenRows(tokensByBucket) {
  if (!isPlainObject(tokensByBucket)) return []
  const names = Object.keys(tokensByBucket).filter((name) => isPlainObject(tokensByBucket[name]))
  return [
    ...BUCKET_ORDER.filter((name) => names.includes(name)),
    ...names.filter((name) => !BUCKET_ORDER.includes(name)).sort(),
  ].map((name) => ({ name, label: BUCKET_LABELS[name] ?? name, tokens: tokensByBucket[name] }))
}

function CallsTable({ rows }) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr><th>System</th><th>Requests</th><th>Failed</th></tr>
        </thead>
        <tbody>
          {rows.flatMap((row) => [
            <tr key={row.system}>
              <td>{row.label}</td>
              <td>{fmtCount(row.requests)}</td>
              <td>{fmtCount(row.failed)}</td>
            </tr>,
            ...row.modes.map(([mode, count]) => (
              <tr key={`${row.system}:${mode}`}>
                <td className="sub-row">mode: <span className="mono">{mode}</span></td>
                <td>{fmtCount(count)}</td>
                <td>—</td>
              </tr>
            )),
          ])}
        </tbody>
      </table>
    </div>
  )
}

function TokensTable({ rows }) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr><th>Category</th><th>Input</th><th>Cached input</th><th>Output</th></tr>
        </thead>
        <tbody>
          {rows.flatMap(({ name, label, tokens }) => {
            const out = [
              <tr key={name}>
                <td>{label}</td>
                <td>{fmtCount(tokens.input_tokens)}</td>
                <td>{fmtCount(tokens.cached_input_tokens)}</td>
                <td>{fmtCount(tokens.output_tokens)}</td>
              </tr>,
            ]
            if (isCount(tokens.perplexity_input_tokens) || isCount(tokens.perplexity_output_tokens)) {
              out.push(
                <tr key={`${name}:perplexity`}>
                  <td className="sub-row">Perplexity</td>
                  <td>{fmtCount(tokens.perplexity_input_tokens)}</td>
                  <td>—</td>
                  <td>{fmtCount(tokens.perplexity_output_tokens)}</td>
                </tr>,
              )
            }
            return out
          })}
        </tbody>
      </table>
    </div>
  )
}

/** The expanded detail of one book row: calls by system + tokens by category. */
export default function BookUsageDetail({ calls, tokensByBucket }) {
  const calledSystems = callRows(calls)
  const buckets = tokenRows(tokensByBucket)
  if (calledSystems.length === 0 && buckets.length === 0) {
    return <p className="sub usage-detail-empty">{NO_CALL_DATA}</p>
  }
  return (
    <div className="usage-detail">
      <div>
        <h3>Calls by system</h3>
        {calledSystems.length > 0
          ? <CallsTable rows={calledSystems} />
          : <p className="sub">{NO_CALL_DATA}</p>}
      </div>
      <div>
        <h3>Tokens by category</h3>
        {buckets.length > 0
          ? <TokensTable rows={buckets} />
          : <p className="sub">{NO_TOKEN_DATA}</p>}
      </div>
    </div>
  )
}
