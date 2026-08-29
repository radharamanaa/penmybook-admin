import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'

const refundRequestQueue = vi.hoisted(() => vi.fn())
const refundResiduals = vi.hoisted(() => vi.fn())
vi.mock('../api.js', () => ({
  api: {
    refundRequestQueue: (...a) => refundRequestQueue(...a),
    refundResiduals: (...a) => refundResiduals(...a),
  },
}))

import RefundRequests from './RefundRequests.jsx'

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
    render(<RefundRequests />)
    expect(await screen.findByRole('columnheader', { name: /Quoted refundable/i })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: /Live refundable/i })).toBeInTheDocument()
    expect(screen.getByText(/leftover promo inside remaining/i)).toBeInTheDocument()
    expect(screen.queryByText(/never sent to a card/i)).not.toBeInTheDocument()
  })
})
