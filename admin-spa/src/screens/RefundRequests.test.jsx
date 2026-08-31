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
