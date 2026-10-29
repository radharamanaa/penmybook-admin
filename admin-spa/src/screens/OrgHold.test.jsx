import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

const otpStart = vi.hoisted(() => vi.fn())
const otpVerify = vi.hoisted(() => vi.fn())
const orgWalletState = vi.hoisted(() => vi.fn())
const unfreezeOrgWallet = vi.hoisted(() => vi.fn())

vi.mock('../api.js', () => ({
  api: {
    otpStart: (...a) => otpStart(...a),
    otpVerify: (...a) => otpVerify(...a),
    orgWalletState: (...a) => orgWalletState(...a),
    unfreezeOrgWallet: (...a) => unfreezeOrgWallet(...a),
  },
}))

import OrgHold from './OrgHold.jsx'

const ORG = '33333333-3333-3333-3333-333333333333'
const FROZEN = {
  orgId: ORG, walletExists: true, balanceCents: 0,
  walletFrozen: true, walletFrozenReason: 'dispute:du_org', unrecoveredCents: 300,
}

async function load() {
  render(<OrgHold />)
  await userEvent.type(screen.getByLabelText(/org id/i), ORG)
  await userEvent.click(screen.getByRole('button', { name: /check org wallet/i }))
}

describe('OrgHold — lifting an org wallet freeze (F3)', () => {
  beforeEach(() => {
    for (const fn of [otpStart, otpVerify, orgWalletState, unfreezeOrgWallet]) fn.mockReset()
    orgWalletState.mockResolvedValue(FROZEN)
    otpStart.mockResolvedValue({ challengeId: 'ch-1' })
    otpVerify.mockResolvedValue({ verified: true, stepUpToken: 'tok-abc' })
    unfreezeOrgWallet.mockResolvedValue({ orgId: ORG, lifted: true })
  })

  it('shows the freeze and the money a dispute could not take back', async () => {
    await load()

    expect(orgWalletState).toHaveBeenCalledWith(ORG)
    expect(await screen.findByText(/dispute:du_org/)).toBeInTheDocument()
    expect(screen.getByText(/\$3\.00 could not be taken back/i)).toBeInTheDocument()
    expect(screen.getByText(/reactivate each member/i)).toBeInTheDocument()
  })

  it('unfreezes with an OTP bound to this org wallet', async () => {
    await load()
    const start = await screen.findByRole('button', { name: /start otp unfreeze org wallet/i })
    expect(start).toBeDisabled()

    await userEvent.type(screen.getByLabelText(/why are you lifting the hold/i), 'dispute won in Stripe')
    await userEvent.click(start)
    expect(otpStart).toHaveBeenCalledWith('wallet_unfreeze', ORG, { ownerType: 'ORG', ownerId: ORG })

    orgWalletState.mockResolvedValue({ ...FROZEN, walletFrozen: false, walletFrozenReason: null })
    await userEvent.type(screen.getByLabelText(/otp code/i), '123456')
    await userEvent.click(screen.getByRole('button', { name: /verify & execute/i }))

    expect(otpVerify).toHaveBeenCalledWith('ch-1', '123456', 'wallet_unfreeze', ORG,
      { ownerType: 'ORG', ownerId: ORG })
    expect(unfreezeOrgWallet).toHaveBeenCalledWith(ORG, 'dispute won in Stripe', 'tok-abc')
    expect(await screen.findByText(/org wallet unfrozen/i)).toBeInTheDocument()
    expect(screen.getByText(/not frozen/i)).toBeInTheDocument()
  })

  it('says so when the org wallet is not frozen', async () => {
    orgWalletState.mockResolvedValue({ ...FROZEN, walletFrozen: false, walletFrozenReason: null,
      unrecoveredCents: 0 })
    await load()

    expect(await screen.findByText(/not frozen/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /unfreeze org wallet/i })).not.toBeInTheDocument()
  })
})
