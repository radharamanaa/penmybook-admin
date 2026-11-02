import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

vi.mock('../api.js', () => ({ api: { listUsers: vi.fn() } }))

import Signups from './Signups.jsx'

const ROW = {
  userId: '019200aa-0000-7000-8000-0000000000aa',
  email: 'reader@example.com',
  accountStatus: 'SUSPENDED_FOR_REVIEW',
  createdAt: '2026-11-02T09:30:00Z',
}

const renderScreen = () => render(<MemoryRouter><Signups /></MemoryRouter>)
const lastCall = (api) => api.listUsers.mock.calls.at(-1)[0]

describe('Signups', () => {
  let api
  beforeEach(async () => {
    ;({ api } = await import('../api.js'))
    vi.clearAllMocks()
    api.listUsers.mockResolvedValue({ users: [ROW], total: 1 })
  })

  it('lists users with their signup time, status and a link to manage them', async () => {
    renderScreen()

    expect(await screen.findByText('reader@example.com')).toBeInTheDocument()
    expect(screen.getByText(new Date(ROW.createdAt).toLocaleString())).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'Held for review' })).toBeInTheDocument()
    expect(screen.getByText('1 user')).toBeInTheDocument()
    const manage = screen.getByRole('link', { name: 'Manage' }).getAttribute('href')
    expect(manage).toBe(`/users?ownerId=${ROW.userId}&ownerEmail=reader%40example.com`)
    expect(lastCall(api)).toEqual({
      email: '', status: '', signedUpFrom: '', signedUpBefore: '', page: 0, size: 50,
    })
  })

  it('filters while typing, with one request after a pause rather than one per key', async () => {
    const user = userEvent.setup()
    renderScreen()
    await screen.findByText('reader@example.com')

    await user.type(screen.getByLabelText('Search'), ' gmail ')

    await waitFor(() => expect(lastCall(api).email).toBe('gmail'))
    expect(api.listUsers).toHaveBeenCalledTimes(2)
  })

  it('applies status and dates at once, with the to date covering that whole day', async () => {
    const user = userEvent.setup()
    renderScreen()
    await screen.findByText('reader@example.com')

    await user.selectOptions(screen.getByLabelText('Status'), 'WAITLISTED')
    await waitFor(() => expect(lastCall(api).status).toBe('WAITLISTED'))
    await user.type(screen.getByLabelText('Signed up from'), '2026-11-02')
    await user.type(screen.getByLabelText('Signed up to'), '2026-11-02')

    await waitFor(() => expect(lastCall(api).signedUpBefore).not.toBe(''))
    const from = new Date(lastCall(api).signedUpFrom)
    const before = new Date(lastCall(api).signedUpBefore)
    expect(from.getHours()).toBe(0)
    expect(from.getDate()).toBe(2)
    expect(before.getHours()).toBe(0)
    expect(before.getDate()).toBe(3)
  })

  it('does not let a slow older reply replace a newer one', async () => {
    const user = userEvent.setup()
    renderScreen()
    await screen.findByText('reader@example.com')
    let finishOlder
    api.listUsers.mockReturnValueOnce(new Promise((resolve) => { finishOlder = resolve }))
    api.listUsers.mockResolvedValueOnce({ users: [{ ...ROW, email: 'newer@example.com' }], total: 1 })

    await user.selectOptions(screen.getByLabelText('Status'), 'ACTIVE')
    await user.selectOptions(screen.getByLabelText('Status'), 'BANNED')
    await screen.findByText('newer@example.com')
    finishOlder({ users: [{ ...ROW, email: 'older@example.com' }], total: 1 })
    await Promise.resolve()

    expect(screen.queryByText('older@example.com')).not.toBeInTheDocument()
    expect(screen.getByText('newer@example.com')).toBeInTheDocument()
  })

  it('pages through the list and goes back to the first page on a new filter', async () => {
    const user = userEvent.setup()
    api.listUsers.mockResolvedValue({ users: [ROW], total: 120 })
    renderScreen()
    expect(await screen.findByText('Page 1 of 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(lastCall(api).page).toBe(1))

    await user.selectOptions(screen.getByLabelText('Status'), 'ACTIVE')
    await waitFor(() => expect(lastCall(api)).toMatchObject({ status: 'ACTIVE', page: 0 }))
  })

  it('stays on the page when the search only gains a space', async () => {
    const user = userEvent.setup()
    api.listUsers.mockResolvedValue({ users: [ROW], total: 120 })
    renderScreen()
    await screen.findByText('Page 1 of 3')
    await user.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(lastCall(api).page).toBe(1))
    const calls = api.listUsers.mock.calls.length

    await user.type(screen.getByLabelText('Search'), ' ')
    await new Promise((resolve) => { setTimeout(resolve, 400) })

    expect(api.listUsers).toHaveBeenCalledTimes(calls)
    expect(lastCall(api).page).toBe(1)
  })

  it('moves back when the list shrinks and the page no longer exists', async () => {
    const user = userEvent.setup()
    api.listUsers.mockResolvedValue({ users: [ROW], total: 51 })
    renderScreen()
    await screen.findByText('Page 1 of 2')
    api.listUsers.mockImplementation(async ({ page }) => (
      page === 0 ? { users: [ROW], total: 50 } : { users: [], total: 50 }))

    await user.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => expect(lastCall(api).page).toBe(0))
    expect(await screen.findByText('reader@example.com')).toBeInTheDocument()
  })

  it('says so when nothing matches, and hides paging', async () => {
    api.listUsers.mockResolvedValue({ users: [], total: 0 })
    renderScreen()

    expect(await screen.findByText('No users match these filters.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Next' })).not.toBeInTheDocument()
  })

  it('shows a failure, and no old rows under it', async () => {
    const user = userEvent.setup()
    renderScreen()
    await screen.findByText('reader@example.com')
    api.listUsers.mockRejectedValue(new Error('the list could not be loaded'))

    await user.selectOptions(screen.getByLabelText('Status'), 'BANNED')

    expect(await screen.findByRole('alert')).toHaveTextContent('the list could not be loaded')
    expect(screen.queryByText('reader@example.com')).not.toBeInTheDocument()
  })

  it('survives a date too far ahead for the browser to hold', async () => {
    renderScreen()
    await screen.findByText('reader@example.com')

    fireEvent.change(screen.getByLabelText('Signed up from'), { target: { value: '20266-11-02' } })

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Signups' })).toBeInTheDocument())
    expect(lastCall(api).signedUpFrom).toBe('')
  })
})
