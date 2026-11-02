import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api.js'

const PAGE_SIZE = 50
const SEARCH_PAUSE_MS = 300 // wait for a pause in typing, so each keystroke is not a request
const STATUSES = [
  ['ACTIVE', 'Active'],
  ['WAITLISTED', 'Waitlisted'],
  ['SUSPENDED', 'Suspended'],
  ['SUSPENDED_FOR_REVIEW', 'Held for review'],
  ['BANNED', 'Banned'],
  ['SOFT_DELETED', 'Deleted'],
]

const statusLabel = (status) => STATUSES.find(([value]) => value === status)?.[1] ?? status

/** The instant a date in the admin's own time zone starts, `daysLater` days on. */
function dayStart(date, daysLater = 0) {
  if (!date) return ''
  const start = new Date(`${date}T00:00:00`)
  start.setDate(start.getDate() + daysLater)
  // A date box accepts a year this far past what Date can hold: leave that filter off until it is valid.
  return Number.isNaN(start.getTime()) ? '' : start.toISOString()
}

function signedUpAt(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString()
}

/** Who signed up and when, newest first. Read-only; Manage opens the user on Users & money. */
export default function Signups() {
  const [search, setSearch] = useState('')
  const [email, setEmail] = useState('')
  const [status, setStatus] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [page, setPage] = useState(0)
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    if (search.trim() === email) return undefined
    const pause = setTimeout(() => {
      setEmail(search.trim())
      setPage(0)
    }, SEARCH_PAUSE_MS)
    return () => clearTimeout(pause)
  }, [search, email])

  useEffect(() => {
    let latest = true // a slower, older reply must not replace a newer one
    setError(null)
    api.listUsers({
      email,
      status,
      signedUpFrom: dayStart(from),
      signedUpBefore: dayStart(to, 1),
      page,
      size: PAGE_SIZE,
    }).then((list) => {
      if (latest) setResult(list)
    }).catch((err) => {
      if (!latest) return
      setError(err.message)
      setResult(null) // the old rows no longer match what is being asked for
    })
    return () => { latest = false }
  }, [email, status, from, to, page])

  const filterBy = (setFilter) => (event) => {
    setFilter(event.target.value)
    setPage(0)
  }

  const users = result?.users ?? []
  const total = result?.total ?? 0
  const lastPage = Math.max(Math.ceil(total / PAGE_SIZE) - 1, 0)

  // The list can shrink while it is open, leaving this page past the end.
  useEffect(() => {
    if (result && page > lastPage) setPage(lastPage)
  }, [result, page, lastPage])

  return (
    <>
      <h1>Signups</h1>
      <p className="sub">Everyone who has signed up, newest first. Times are in your own time zone.</p>
      {error && <div className="msg error" role="alert">{error}</div>}

      <div className="panel row">
        <div>
          <label htmlFor="search">Search</label>
          <input id="search" placeholder="Email" value={search} onChange={(e) => setSearch(e.target.value)} />
        </div>
        <div>
          <label htmlFor="status">Status</label>
          <select id="status" value={status} onChange={filterBy(setStatus)}>
            <option value="">All</option>
            {STATUSES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </div>
        <div>
          <label htmlFor="from">Signed up from</label>
          <input id="from" type="date" value={from} onChange={filterBy(setFrom)} />
        </div>
        <div>
          <label htmlFor="to">Signed up to</label>
          <input id="to" type="date" value={to} onChange={filterBy(setTo)} />
        </div>
      </div>

      {result && (
        <div className="panel">
          <p className="sub">{total.toLocaleString()} {total === 1 ? 'user' : 'users'}</p>
          {users.length === 0 ? <p>No users match these filters.</p> : (
            <div className="table-wrap">
              <table className="cards" role="table">
                <thead role="rowgroup">
                  <tr role="row">
                    <th role="columnheader">Signed up</th>
                    <th role="columnheader">Email</th>
                    <th role="columnheader">Status</th>
                    <th role="columnheader"></th>
                  </tr>
                </thead>
                <tbody role="rowgroup">
                  {users.map((user) => (
                    <tr role="row" key={user.userId}>
                      <td role="cell" data-label="Signed up">{signedUpAt(user.createdAt)}</td>
                      <td role="cell" data-label="Email">{user.email}</td>
                      <td role="cell" data-label="Status">{statusLabel(user.accountStatus)}</td>
                      <td role="cell">
                        <Link to={`/users?${new URLSearchParams({ ownerId: user.userId, ownerEmail: user.email })}`}>
                          Manage
                        </Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {lastPage > 0 && (
            <div className="row">
              <button className="small ghost" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
              <span className="mono">Page {page + 1} of {lastPage + 1}</span>
              <button className="small ghost" disabled={page >= lastPage} onClick={() => setPage(page + 1)}>Next</button>
            </div>
          )}
        </div>
      )}
    </>
  )
}
