import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

const otpStart = vi.hoisted(() => vi.fn())
const otpVerify = vi.hoisted(() => vi.fn())
const refund = vi.hoisted(() => vi.fn())

vi.mock('../api.js', () => ({
  api: {
    searchUsers: vi.fn(),
    otpStart: (...a) => otpStart(...a),
    otpVerify: (...a) => otpVerify(...a),
    refund: (...a) => refund(...a),
    accountState: async () => ({ accountStatus: 'ACTIVE', walletFrozen: false, unrecoveredCents: 0 }),
  },
}))

import Users from './Users.jsx'

const REQUEST_ID = '22222222-2222-2222-2222-222222222222'
const PAYMENT_ID = '11111111-1111-1111-1111-111111111111'

function renderPayout() {
  return render(
    <MemoryRouter initialEntries={[
      `/users?refundRequestId=${REQUEST_ID}&ownerType=USER&ownerId=u1&ownerEmail=a@b.co`
        + `&paymentId=${PAYMENT_ID}&amountCents=874`,
    ]}>
      <Users />
    </MemoryRouter>,
  )
}

async function startAndExecute() {
  await userEvent.click(screen.getByRole('button', { name: /start otp refund/i }))
  await userEvent.type(screen.getByLabelText(/otp code/i), '123456')
  await userEvent.click(screen.getByRole('button', { name: /verify & execute/i }))
}

// Q13 (owner, 30 Oct 2026): staff can freeze the account as part of paying out a refund request.
// The choice is bound into the step-up params, so the approval covers exactly what was chosen.
describe('Users payout: Also freeze this account', () => {
  beforeEach(() => {
    otpStart.mockReset()
    otpVerify.mockReset()
    refund.mockReset()
    otpStart.mockResolvedValue({ challengeId: 'ch-1' })
    otpVerify.mockResolvedValue({ verified: true, stepUpToken: 'tok-abc' })
    refund.mockResolvedValue({})
  })

  it('is unticked by default and leaves the freeze out of the approval', async () => {
    renderPayout()
    expect(screen.getByLabelText('Also freeze this account')).not.toBeChecked()

    await startAndExecute()

    expect(otpStart).toHaveBeenCalledWith('refund', 'u1', {
      paymentId: PAYMENT_ID, refundAmountCents: 874, refundRequestId: REQUEST_ID,
    })
    expect(otpVerify.mock.calls[0][4]).not.toHaveProperty('freezeAccount')
    expect(refund.mock.calls[0][7]).toBe(false)
  })

  it('binds a ticked freeze into the OTP and sends it with the payout', async () => {
    renderPayout()
    await userEvent.click(screen.getByLabelText('Also freeze this account'))

    await startAndExecute()

    const bound = {
      paymentId: PAYMENT_ID, refundAmountCents: 874, refundRequestId: REQUEST_ID, freezeAccount: true,
    }
    expect(otpStart).toHaveBeenCalledWith('refund', 'u1', bound)
    expect(otpVerify).toHaveBeenCalledWith('ch-1', '123456', 'refund', 'u1', bound)
    expect(refund).toHaveBeenCalledWith(
      'u1', PAYMENT_ID, 874, 'admin refund', 'tok-abc', REQUEST_ID, false, true,
    )
  })

  it('cannot be changed once the OTP has started, so the approval matches what is sent', async () => {
    renderPayout()
    await userEvent.click(screen.getByRole('button', { name: /start otp refund/i }))
    expect(screen.getByLabelText('Also freeze this account')).toBeDisabled()
  })
})
