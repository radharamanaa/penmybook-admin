import { useEffect, useRef, useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { usePhoneLayout } from './usePhoneLayout.js'

const REFUND_QUEUE_ENABLED = import.meta.env.VITE_ENABLE_REFUND_QUEUE === 'true'

// iOS ignores overflow:hidden on body; pinning it is the reliable lock.
function lockBodyScroll() {
  const y = window.scrollY
  const s = document.body.style
  s.position = 'fixed'
  s.width = '100%'
  s.top = `-${y}px`
  return () => {
    s.position = ''
    s.width = ''
    s.top = ''
    window.scrollTo(0, y)
  }
}

// The logged-in layout. On a phone the sidebar is a drawer opened from the top bar.
export function AdminShell({ adminEmail, onLogout, sections }) {
  const phone = usePhoneLayout()
  const [open, setOpen] = useState(false)
  const menuRef = useRef(null)
  const navRef = useRef(null)
  const { pathname } = useLocation()
  const drawerOpen = phone && open

  const close = () => setOpen(false)

  useEffect(() => { setOpen(false) }, [pathname])
  useEffect(() => { if (!phone) setOpen(false) }, [phone])

  useEffect(() => {
    if (!drawerOpen) return undefined
    const unlock = lockBodyScroll()
    navRef.current?.querySelector('a')?.focus()
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      unlock()
      menuRef.current?.focus()
    }
  }, [drawerOpen])

  const link = (to, label, end) => (
    <NavLink to={to} end={end} onClick={close}>{label}</NavLink>
  )

  return (
    <div className={drawerOpen ? 'layout nav-open' : 'layout'}>
      {phone && (
        <header className="topbar">
          <button
            ref={menuRef}
            type="button"
            className="ghost menu-btn"
            aria-label="Open menu"
            aria-expanded={drawerOpen}
            aria-controls="admin-nav"
            onClick={() => setOpen((v) => !v)}
          >
            ☰
          </button>
          <span className="brand">PenMyBook admin</span>
        </header>
      )}
      <nav id="admin-nav" aria-label="Admin navigation" className="sidebar" ref={navRef}>
        <div className="brand">PenMyBook<small>admin control plane</small></div>
        <div className="group">Operate</div>
        {link('/', 'Operations', true)}
        {link('/analytics', 'Analytics')}
        {link('/feedback', 'Feedback')}
        {link('/spend', 'Spend')}
        {REFUND_QUEUE_ENABLED && link('/refund-requests', 'Refund requests')}
        <div className="group">Configure</div>
        {sections.map((s) => (
          <NavLink key={s.key} to={`/config/${s.key}`} onClick={close}>{s.label}</NavLink>
        ))}
        {link('/pricing', 'Pricing & billing')}
        {link('/system', 'System (read-only)')}
        <div className="group">People</div>
        {link('/users', 'Users & money')}
        {link('/waitlist', 'Waitlist')}
        <div className="group">Govern</div>
        {link('/accounts', 'Admin accounts')}
        {link('/audit', 'Audit log')}
        <div className="foot">
          <div className="mono" style={{ marginBottom: 8 }}>{adminEmail}</div>
          <button className="ghost small" onClick={onLogout}>Sign out</button>
        </div>
      </nav>
      {drawerOpen && <div className="scrim" aria-hidden="true" onClick={close} />}
      <main className="main" inert={drawerOpen ? '' : undefined}>
        <Outlet />
      </main>
    </div>
  )
}
