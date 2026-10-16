// Refund requests, Users, and Waitlist reflow into cards on a phone: every data
// cell carries its column name in data-label, action cells carry none.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

vi.mock('../api.js', () => ({
  api: {
    searchUsers: vi.fn(),
    waitlistConfig: vi.fn(),
    waitlistQueue: vi.fn(),
    listPreAdmits: vi.fn(),
    refundRequestQueue: vi.fn(),
    refundResiduals: vi.fn(),
  },
}))

import Users from './Users.jsx'
import Waitlist from './Waitlist.jsx'
import RefundRequests from './RefundRequests.jsx'

function assertCardTable(table) {
  expect(table).toHaveClass('cards')
  expect(table.parentElement).toHaveClass('table-wrap')
  const headers = [...table.querySelectorAll('thead th')].map((th) => th.textContent.trim())
  const rows = table.querySelectorAll('tbody tr')
  expect(rows.length).toBeGreaterThan(0)
  rows.forEach((tr) => {
    ;[...tr.children].forEach((td, i) => {
      if (headers[i]) expect(td.getAttribute('data-label')).toBe(headers[i])
      else expect(td.hasAttribute('data-label')).toBe(false)
    })
  })
}

const assertEveryTableWrapped = () =>
  document.querySelectorAll('table').forEach((t) => expect(t.parentElement).toHaveClass('table-wrap'))

let api
beforeEach(async () => {
  ;({ api } = await import('../api.js'))
  vi.clearAllMocks()
})

describe('card tables', () => {
  it('Users search results', async () => {
    api.searchUsers.mockResolvedValue([{ userId: 'u1', email: 'a@b.co', accountStatus: 'ACTIVE' }])
    render(<MemoryRouter><Users /></MemoryRouter>)
    await userEvent.type(screen.getByLabelText(/Email contains/i), 'a@')
    await userEvent.click(screen.getByRole('button', { name: /^Search$/i }))
    await screen.findByText('a@b.co')
    assertCardTable(document.querySelector('table'))
    assertEveryTableWrapped()
  })

  it('Waitlist pending and waiting', async () => {
    api.waitlistConfig.mockResolvedValue({ enabled: true, weeklyApprovalLimit: 5, admittedThisWeek: 1 })
    api.waitlistQueue.mockResolvedValue([{ userId: 'u2', email: 'wait@x.io', createdAt: '2026-09-01T00:00:00Z' }])
    api.listPreAdmits.mockResolvedValue([{
      id: 'p1', email: 'pending@example.com', admittedBy: 'admin-1',
      createdAt: '2026-09-03T10:00:00Z', expiresAt: '2026-10-03T10:00:00Z',
    }])
    render(<Waitlist />)
    await screen.findByText('pending@example.com')
    await screen.findByText('wait@x.io')
    const tables = document.querySelectorAll('table')
    expect(tables.length).toBe(2)
    tables.forEach(assertCardTable)
  })

  it('Refund requests and residuals', async () => {
    api.refundRequestQueue.mockResolvedValue({
      requests: [{
        id: 'r1', ownerType: 'USER', ownerId: 'u1', status: 'PENDING',
        requestedAmountCents: 1000, snapshotRefundableCents: 1000, liveRefundableCents: 800,
        refundedCents: 0, residualCents: 0, reason: 'please', createdAt: new Date().toISOString(),
      }],
      degraded: false,
    })
    api.refundResiduals.mockResolvedValue({
      residuals: [{
        id: 's1', ownerType: 'USER', ownerId: 'u1', amountCents: 200, paymentId: 'pay-1',
        providerPaymentRef: 'pi_1', reason: 'left over', createdAt: new Date().toISOString(),
      }],
      degraded: false,
    })
    render(<MemoryRouter><RefundRequests /></MemoryRouter>)
    await screen.findByText('please')
    await screen.findByText('left over')
    expect(screen.getByRole('columnheader', { name: 'Stripe payment' })).toBeInTheDocument()
    expect(screen.getByText('pi_1')).toBeInTheDocument()
    const tables = document.querySelectorAll('table')
    expect(tables.length).toBe(2)
    tables.forEach(assertCardTable)
    expect(screen.getAllByRole('columnheader').length).toBeGreaterThan(0)
  })
})
