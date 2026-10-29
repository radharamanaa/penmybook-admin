import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

const refundRequestQueue = vi.hoisted(() => vi.fn())
const refundResiduals = vi.hoisted(() => vi.fn())
vi.mock('../api.js', () => ({
  api: {
    refundRequestQueue: (...a) => refundRequestQueue(...a),
    refundResiduals: (...a) => refundResiduals(...a),
  },
}))

import RefundRequests from './RefundRequests.jsx'

function renderQueue() {
  return render(
    <MemoryRouter>
      <RefundRequests />
    </MemoryRouter>,
  )
}

describe('RefundRequests honest promo copy (S5)', () => {
  beforeEach(() => {
    refundRequestQueue.mockResolvedValue({ requests: [], degraded: false })
    refundResiduals.mockResolvedValue({ residuals: [], degraded: false })
  })

  it('keeps snapshot vs live visible and does not claim promo never rides the card', async () => {
    refundRequestQueue.mockResolvedValue({
      requests: [{
        id: 'r1',
        ownerType: 'USER',
        ownerId: 'u1',
        status: 'PENDING',
        requestedAmountCents: 10000,
        snapshotRefundableCents: 10000,
        liveRefundableCents: 8000,
        refundedCents: 0,
        residualCents: 0,
        reason: 'please',
        createdAt: new Date().toISOString(),
      }],
      degraded: false,
    })
    renderQueue()
    expect(await screen.findByRole('columnheader', { name: /Quoted refundable/i })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: /Live refundable/i })).toBeInTheDocument()
    expect(screen.getByText(/leftover promo inside remaining/i)).toBeInTheDocument()
    expect(screen.queryByText(/never sent to a card/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/forfeit bonus we can identify/i)).not.toBeInTheDocument()
  })
})

describe('RefundRequests queue filters (S7)', () => {
  beforeEach(() => {
    refundRequestQueue.mockResolvedValue({ requests: [], degraded: false })
    refundResiduals.mockResolvedValue({ residuals: [], degraded: false })
  })

  it('exposes PAYING and SETTLED with the staff labels', async () => {
    renderQueue()
    expect(await screen.findByRole('button', { name: /in-flight card refund/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /fully paid out/i })).toBeInTheDocument()
  })

  it('queries PAYING when staff pick the in-flight filter', async () => {
    renderQueue()
    await userEvent.click(await screen.findByRole('button', { name: /in-flight card refund/i }))
    expect(refundRequestQueue).toHaveBeenCalledWith('PAYING', 50)
  })

  it('does not expose dead APPROVING or PARTIALLY_SETTLED filters', async () => {
    renderQueue()
    await screen.findByRole('button', { name: /in-flight card refund/i })
    expect(screen.queryByRole('button', { name: /APPROVING/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /PARTIALLY SETTLED/i })).not.toBeInTheDocument()
  })

  it('deep-links an APPROVED row to Users naming refundRequestId', async () => {
    refundRequestQueue.mockResolvedValue({
      requests: [{
        id: 'req-approved',
        ownerType: 'USER',
        ownerId: 'u1',
        ownerEmail: 'a@b.co',
        status: 'APPROVED',
        requestedAmountCents: 2000,
        snapshotRefundableCents: 2000,
        liveRefundableCents: 2000,
        refundedCents: 0,
        residualCents: 0,
        reason: 'please',
        createdAt: new Date().toISOString(),
      }],
      degraded: false,
    })
    renderQueue()
    const payout = await screen.findByRole('link', { name: /pay out/i })
    expect(payout).toHaveAttribute(
      'href',
      '/users?refundRequestId=req-approved&ownerType=USER&ownerId=u1&ownerEmail=a%40b.co',
    )
  })
})

// Refund-flow test (prod, 28 Oct 2026): U1 an "everything" ask showed "—", U4 staff could not see
// what was approved, P1 "Pay out" named no payment, G2 a blank approval approved the whole balance.
describe('RefundRequests approved rows (U1, U4, P1, G2)', () => {
  const NOW = new Date().toISOString()
  const PAY_A = 'aaaaaaaa-0000-0000-0000-00000000000a'
  const PAY_B = 'bbbbbbbb-0000-0000-0000-00000000000b'
  const PAY_OLD = 'cccccccc-0000-0000-0000-00000000000c'

  beforeEach(() => {
    refundResiduals.mockResolvedValue({ residuals: [], degraded: false })
  })

  it('shows "All refundable" for an everything request and the approved amount', async () => {
    refundRequestQueue.mockResolvedValue({
      degraded: false,
      requests: [{
        id: 'r1', ownerType: 'USER', ownerId: 'u1', status: 'APPROVED',
        requestedAmountCents: null, snapshotRefundableCents: 874, liveRefundableCents: 874,
        approvedAmountCents: 874, refundedCents: null, reason: 'test', createdAt: NOW,
        payments: [], suggestedPaymentId: null,
      }],
    })
    renderQueue()
    expect(await screen.findByRole('columnheader', { name: /^Approved$/ })).toBeInTheDocument()
    expect(screen.getByText('All refundable')).toBeInTheDocument()
    const approved = screen.getAllByRole('cell').find((c) => c.getAttribute('data-label') === 'Approved')
    expect(approved).toHaveTextContent('$8.74')
  })

  it('offers one pay-out per payment, filled with the amount still owed, suggested payment first', async () => {
    refundRequestQueue.mockResolvedValue({
      degraded: false,
      requests: [{
        id: 'r2', ownerType: 'USER', ownerId: 'u1', ownerEmail: 'a@b.co', status: 'APPROVED',
        requestedAmountCents: 1500, snapshotRefundableCents: 2000, liveRefundableCents: 2000,
        approvedAmountCents: 1500, refundedCents: null, reason: 'test', createdAt: NOW,
        suggestedPaymentId: PAY_B,
        payments: [
          { paymentId: PAY_A, remainingCents: 1000, inWindow: true, createdAt: NOW },
          { paymentId: PAY_B, remainingCents: 1000, inWindow: true, createdAt: NOW },
          { paymentId: PAY_OLD, remainingCents: 1000, inWindow: false, createdAt: NOW },
        ],
      }],
    })
    renderQueue()
    const links = await screen.findAllByRole('link', { name: /pay out \$/i })
    expect(links.map((l) => l.textContent)).toEqual([
      'Pay out $10.00 from …000b',
      'Pay out $5.00 from …000a',
    ])
    expect(links[0].getAttribute('href')).toContain(`paymentId=${PAY_B}`)
    expect(links[0].getAttribute('href')).toContain('amountCents=1000')
    expect(links[0].getAttribute('href')).toContain('refundRequestId=r2')
    expect(links[1].getAttribute('href')).toContain('amountCents=500')
    expect(screen.getByText(/past the 120-day card-refund limit/i)).toBeInTheDocument()
  })

  it('keeps a plain pay-out link when every payment is past the 120-day limit', async () => {
    refundRequestQueue.mockResolvedValue({
      degraded: false,
      requests: [{
        id: 'r3', ownerType: 'USER', ownerId: 'u1', status: 'APPROVED',
        requestedAmountCents: 1000, snapshotRefundableCents: 1000, liveRefundableCents: 1000,
        approvedAmountCents: 1000, refundedCents: null, reason: 'old', createdAt: NOW,
        suggestedPaymentId: null,
        payments: [{ paymentId: PAY_OLD, remainingCents: 1000, inWindow: false, createdAt: NOW }],
      }],
    })
    renderQueue()
    const link = await screen.findByRole('link', { name: /^pay out$/i })
    expect(link.getAttribute('href')).toContain('refundRequestId=r3')
    expect(link.getAttribute('href')).not.toContain('paymentId=')
  })

  it('tells staff a blank approval approves what the user asked', async () => {
    refundRequestQueue.mockResolvedValue({ requests: [], degraded: false })
    renderQueue()
    expect(await screen.findByText(/leave blank for the amount the user asked/i)).toBeInTheDocument()
  })
})
