import { render, screen, fireEvent, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { stubPhoneLayout } from './test/phoneLayout.js'
import { AdminShell } from './AdminShell.jsx'

const SECTIONS = [{ key: 'tiers', label: 'Tiers & caps' }]

function renderShell(path = '/') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<AdminShell adminEmail="ops@x.io" onLogout={() => {}} sections={SECTIONS} />}>
          <Route path="/" element={<h1>Operations page</h1>} />
          <Route path="/analytics" element={<h1>Analytics page</h1>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}
const menuButton = () => screen.getByRole('button', { name: /open menu/i })
const isOpen = (c) => c.querySelector('.layout').classList.contains('nav-open')

afterEach(() => {
  document.body.style.position = ''
  document.body.style.top = ''
  document.body.style.width = ''
})

it('shows no top bar on desktop', () => {
  stubPhoneLayout(false)
  renderShell()
  expect(screen.queryByRole('button', { name: /open menu/i })).toBeNull()
})

it('opens and closes the drawer from the menu button, moving focus in and back', async () => {
  stubPhoneLayout(true)
  const { container } = renderShell()
  await userEvent.click(menuButton())
  expect(isOpen(container)).toBe(true)
  expect(menuButton()).toHaveAttribute('aria-expanded', 'true')
  expect(document.activeElement).toBe(screen.getByRole('link', { name: 'Operations' }))
  expect(container.querySelector('main')).toHaveAttribute('inert')
  expect(document.body.style.position).toBe('fixed')
  await userEvent.click(menuButton())
  expect(isOpen(container)).toBe(false)
  expect(document.activeElement).toBe(menuButton())
  expect(container.querySelector('main')).not.toHaveAttribute('inert')
  expect(document.body.style.position).toBe('')
})

it('closes on the scrim, on Escape, and on a link tap including the current page', async () => {
  stubPhoneLayout(true)
  const { container } = renderShell()
  await userEvent.click(menuButton())
  fireEvent.click(container.querySelector('.scrim'))
  expect(isOpen(container)).toBe(false)

  await userEvent.click(menuButton())
  fireEvent.keyDown(document, { key: 'Escape' })
  expect(isOpen(container)).toBe(false)

  await userEvent.click(menuButton())
  await userEvent.click(screen.getByRole('link', { name: 'Operations' }))
  expect(isOpen(container)).toBe(false)

  await userEvent.click(menuButton())
  await userEvent.click(screen.getByRole('link', { name: 'Analytics' }))
  expect(isOpen(container)).toBe(false)
  expect(screen.getByRole('heading', { name: 'Analytics page' })).toBeInTheDocument()
})

it('ignores Escape when the drawer is closed', () => {
  stubPhoneLayout(true)
  renderShell()
  const before = document.activeElement
  fireEvent.keyDown(document, { key: 'Escape' })
  expect(document.activeElement).toBe(before)
})

it('closes and releases the scroll lock when the viewport becomes desktop', async () => {
  const vp = stubPhoneLayout(true)
  const { container } = renderShell()
  await userEvent.click(menuButton())
  expect(document.body.style.position).toBe('fixed')
  act(() => vp.setMatches(false))
  expect(isOpen(container)).toBe(false)
  expect(document.body.style.position).toBe('')
})
