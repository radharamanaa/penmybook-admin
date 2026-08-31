import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

vi.mock('../api.js', () => ({
  api: { searchUsers: vi.fn() },
}))

import Users from './Users.jsx'

describe('Users refund copy (S5)', () => {
  it('tells staff leftover promo can ride a Paddle refund', async () => {
    const { api } = await import('../api.js')
    api.searchUsers.mockResolvedValue([
      { userId: 'u1', email: 'a@b.co', accountStatus: 'ACTIVE' },
    ])
    render(
      <MemoryRouter>
        <Users />
      </MemoryRouter>,
    )
    await userEvent.type(screen.getByLabelText(/Email contains/i), 'a@')
    await userEvent.click(screen.getByRole('button', { name: /^Search$/i }))
    await userEvent.click(screen.getByRole('button', { name: /Manage/i }))
    expect(screen.getByText(/leftover promo inside remaining/i)).toBeInTheDocument()
    expect(screen.queryByText(/never sent to a card/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/forfeit bonus we can identify/i)).not.toBeInTheDocument()
  })
})
