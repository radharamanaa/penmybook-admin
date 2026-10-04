// Codes and cents fields open the number keypad on a phone, and OTP codes
// can be filled from the emailed code.
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

vi.mock('../api.js', () => ({
  api: { searchUsers: vi.fn(), otpStart: vi.fn() },
}))

import Users from './Users.jsx'

describe('phone keypads', () => {
  it('uses the number keypad for Users cents and OTP fields', async () => {
    const { api } = await import('../api.js')
    api.searchUsers.mockResolvedValue([{ userId: 'u1', email: 'a@b.co', accountStatus: 'ACTIVE' }])
    api.otpStart.mockResolvedValue({ challengeId: 'c1' })
    render(<MemoryRouter><Users /></MemoryRouter>)
    await userEvent.type(screen.getByLabelText(/Email contains/i), 'a@')
    await userEvent.click(screen.getByRole('button', { name: /^Search$/i }))
    await userEvent.click(screen.getByRole('button', { name: /Manage/i }))

    const centsFields = screen.getAllByRole('spinbutton')
    expect(centsFields.length).toBeGreaterThanOrEqual(3)
    centsFields.forEach((field) => expect(field).toHaveAttribute('inputmode', 'numeric'))
    expect(document.querySelector('#refund-cents')).toHaveAttribute('inputmode', 'numeric')

    await userEvent.type(centsFields[0], '100')
    await userEvent.click(screen.getByRole('button', { name: /Start OTP top-up/i }))
    const otpField = await screen.findByLabelText(/OTP code/i)
    expect(otpField).toHaveAttribute('inputmode', 'numeric')
    expect(otpField).toHaveAttribute('autocomplete', 'one-time-code')
  })
})
