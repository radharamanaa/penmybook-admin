import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

const otpStart = vi.hoisted(() => vi.fn())
const otpVerify = vi.hoisted(() => vi.fn())
const searchUsers = vi.hoisted(() => vi.fn())
const accountState = vi.hoisted(() => vi.fn())
const unsuspend = vi.hoisted(() => vi.fn())
const unfreezeWallet = vi.hoisted(() => vi.fn())
const refund = vi.hoisted(() => vi.fn())

vi.mock('../api.js', () => ({
  api: {
    searchUsers: (...a) => searchUsers(...a),
    otpStart: (...a) => otpStart(...a),
    otpVerify: (...a) => otpVerify(...a),
    accountState: (...a) => accountState(...a),
    unsuspend: (...a) => unsuspend(...a),
    unfreezeWallet: (...a) => unfreezeWallet(...a),
    refund: (...a) => refund(...a),
  },
}))

import Users from './Users.jsx'

const HELD = {
  userId: 'u1',
  accountStatus: 'SUSPENDED_FOR_REVIEW',
  suspensionKind: 'DISPUTE',
  walletExists: true,
  balanceCents: 0,
  walletFrozen: true,
  walletFrozenReason: 'dispute:du_1',
  unrecoveredCents: 126,
}

async function manage() {
  render(
    <MemoryRouter>
      <Users />
    </MemoryRouter>,
  )
  await userEvent.type(screen.getByLabelText(/email contains/i), 'a@')
  await userEvent.click(screen.getByRole('button', { name: /^search$/i }))
  await userEvent.click(screen.getByRole('button', { name: /manage/i }))
}

describe('Users — lifting a chargeback or review hold (F3)', () => {
  beforeEach(() => {
    for (const fn of [otpStart, otpVerify, searchUsers, accountState, unsuspend, unfreezeWallet, refund]) {
      fn.mockReset()
    }
    searchUsers.mockResolvedValue([{ userId: 'u1', email: 'a@b.co', accountStatus: 'SUSPENDED_FOR_REVIEW' }])
    accountState.mockResolvedValue(HELD)
    otpStart.mockResolvedValue({ challengeId: 'ch-1' })
    otpVerify.mockResolvedValue({ verified: true, stepUpToken: 'tok-abc' })
    unsuspend.mockResolvedValue({ userId: 'u1', accountStatus: 'ACTIVE' })
    unfreezeWallet.mockResolvedValue({ userId: 'u1', lifted: true })
  })

  it('shows why the user is held, including money a refund could not take back', async () => {
    await manage()

    expect(accountState).toHaveBeenCalledWith('u1')
    expect(await screen.findByText(/suspended for review/i, { selector: '.hold-status' })).toBeInTheDocument()
    expect(screen.getByText(/dispute:du_1/)).toBeInTheDocument()
    expect(screen.getByText(/\$1\.26 could not be taken back/i)).toBeInTheDocument()
    expect(screen.getByText('card dispute')).toBeInTheDocument()
  })

  it('names a content review so it is not mistaken for a payment hold', async () => {
    accountState.mockResolvedValue({ ...HELD, suspensionKind: 'CONTENT', walletFrozen: false })
    await manage()

    expect(await screen.findByText('content review')).toBeInTheDocument()
  })

  it('reactivates with a note, binding no params into the OTP', async () => {
    await manage()
    const reactivate = await screen.findByRole('button', { name: /start otp reactivate account/i })
    expect(reactivate).toBeDisabled()

    await userEvent.type(screen.getByLabelText(/why are you lifting the hold/i), 'dispute won in Stripe')
    await userEvent.click(reactivate)
    expect(otpStart).toHaveBeenCalledWith('unsuspend', 'u1', {})

    await userEvent.type(screen.getByLabelText(/otp code/i), '123456')
    await userEvent.click(screen.getByRole('button', { name: /verify & execute/i }))

    expect(otpVerify).toHaveBeenCalledWith('ch-1', '123456', 'unsuspend', 'u1', {})
    expect(unsuspend).toHaveBeenCalledWith('u1', 'dispute won in Stripe', 'tok-abc')
    expect(refund).not.toHaveBeenCalled()
    expect(await screen.findByText(/a@b\.co is ACTIVE again/i)).toBeInTheDocument()
  })

  it('unfreezes the wallet with the OTP bound to this wallet only', async () => {
    await manage()
    await userEvent.type(await screen.findByLabelText(/why are you lifting the hold/i), 'dispute won in Stripe')
    await userEvent.click(screen.getByRole('button', { name: /start otp unfreeze wallet/i }))
    expect(otpStart).toHaveBeenCalledWith('wallet_unfreeze', 'u1', { ownerType: 'USER', ownerId: 'u1' })

    await userEvent.type(screen.getByLabelText(/otp code/i), '123456')
    await userEvent.click(screen.getByRole('button', { name: /verify & execute/i }))

    expect(unfreezeWallet).toHaveBeenCalledWith('u1', 'dispute won in Stripe', 'tok-abc')
    expect(await screen.findByText(/wallet unfrozen for a@b\.co/i)).toBeInTheDocument()
  })

  it('re-reads the hold after a failed lift so a lift that landed is not offered again', async () => {
    unfreezeWallet.mockRejectedValue(new Error('downstream_rejected'))
    await manage()
    await userEvent.type(await screen.findByLabelText(/why are you lifting the hold/i), 'dispute won in Stripe')
    await userEvent.click(screen.getByRole('button', { name: /start otp unfreeze wallet/i }))
    accountState.mockResolvedValue({ ...HELD, walletFrozen: false, walletFrozenReason: null })
    await userEvent.type(screen.getByLabelText(/otp code/i), '123456')
    await userEvent.click(screen.getByRole('button', { name: /verify & execute/i }))

    expect(await screen.findByText(/downstream_rejected/)).toBeInTheDocument()
    expect(accountState).toHaveBeenCalledTimes(2)
    expect(screen.queryByRole('button', { name: /unfreeze wallet/i })).not.toBeInTheDocument()
  })

  it('does not crash when the account status is unknown', async () => {
    accountState.mockResolvedValue({ ...HELD, accountStatus: null })
    await manage()

    expect(await screen.findByText(/unknown/i, { selector: '.hold-status' })).toBeInTheDocument()
  })

  it('still offers reactivation when billing is down, and never says there is no hold', async () => {
    accountState.mockResolvedValue({ ...HELD, walletFrozen: false, walletFrozenReason: null,
      unrecoveredCents: 0, walletDegraded: true })
    await manage()

    expect(await screen.findByText(/wallet: unknown — billing did not answer/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /start otp reactivate account/i })).toBeInTheDocument()
    expect(screen.queryByText(/no hold on this account/i)).not.toBeInTheDocument()
  })

  it('does not claim "no hold" for an active account when billing is down', async () => {
    accountState.mockResolvedValue({ ...HELD, accountStatus: 'ACTIVE', suspensionKind: null,
      walletFrozen: false, walletFrozenReason: null, unrecoveredCents: 0, walletDegraded: true })
    await manage()

    expect(await screen.findByText(/wallet: unknown — billing did not answer/i)).toBeInTheDocument()
    expect(screen.queryByText(/no hold on this account/i)).not.toBeInTheDocument()
  })

  it('offers no lift when nothing is held', async () => {
    accountState.mockResolvedValue({ ...HELD, accountStatus: 'ACTIVE', walletFrozen: false,
      walletFrozenReason: null, unrecoveredCents: 0 })
    await manage()

    expect(await screen.findByText(/no hold on this account/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /reactivate account/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /unfreeze wallet/i })).not.toBeInTheDocument()
  })

  it('does not offer reactivation for a banned account', async () => {
    accountState.mockResolvedValue({ ...HELD, accountStatus: 'BANNED' })
    await manage()

    expect(await screen.findByRole('button', { name: /start otp unfreeze wallet/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /reactivate account/i })).not.toBeInTheDocument()
  })
})
