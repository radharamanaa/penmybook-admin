import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('../api.js', () => ({
  api: { listTrustedSites: vi.fn(), putTrustedSite: vi.fn(), deleteTrustedSite: vi.fn() },
}))

import TrustedSites from './TrustedSites.jsx'

const SUSPENDED = { site: 'docs.example.org', action: 'suspend', reason: 'spot check flagged', who: 'spot_check', when: '2026-10-14T00:00:00+00:00' }

describe('Trusted sites', () => {
  let api
  beforeEach(async () => {
    ;({ api } = await import('../api.js'))
    vi.clearAllMocks()
    api.listTrustedSites.mockResolvedValue({ overrides: [SUSPENDED] })
  })

  it('lists the changes in force with who made them and why', async () => {
    render(<TrustedSites />)
    await waitFor(() => expect(screen.getByText('docs.example.org')).toBeTruthy())
    expect(screen.getByText('Suspended')).toBeTruthy()
    expect(screen.getByText('spot check flagged')).toBeTruthy()
    expect(screen.getByText('spot_check')).toBeTruthy()
  })

  it('says so when there are no changes', async () => {
    api.listTrustedSites.mockResolvedValue({ overrides: [] })
    render(<TrustedSites />)
    await waitFor(() => expect(screen.getByText(/No changes\. Every site is as the shipped list says\./)).toBeTruthy())
  })

  it('suspends a site with a reason and says what happens next', async () => {
    api.putTrustedSite.mockResolvedValue({ applied: { site: 'bad.example.org', action: 'suspend' } })
    render(<TrustedSites />)
    await userEvent.type(screen.getByLabelText('Site'), ' Bad.Example.org ')
    await userEvent.type(screen.getByLabelText('Reason'), 'served a flagged page')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(api.putTrustedSite).toHaveBeenCalledWith('Bad.Example.org', 'suspend', 'served a flagged page'))
    expect(await screen.findByText(/bad\.example\.org is suspended\./)).toBeTruthy()
    expect(api.listTrustedSites).toHaveBeenCalledTimes(2)
  })

  it('adds a trusted site', async () => {
    api.putTrustedSite.mockResolvedValue({ applied: { site: 'docs.new.example', action: 'trust' } })
    render(<TrustedSites />)
    await userEvent.type(screen.getByLabelText('Site'), 'docs.new.example')
    await userEvent.selectOptions(screen.getByLabelText('Change'), 'trust')
    await userEvent.type(screen.getByLabelText('Reason'), 'official documentation')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(api.putTrustedSite).toHaveBeenCalledWith('docs.new.example', 'trust', 'official documentation'))
    expect(await screen.findByText('docs.new.example is trusted from now on.')).toBeTruthy()
  })

  it('shows what PenMyBook said when a change is refused', async () => {
    api.putTrustedSite.mockRejectedValue(new Error('site must be a plain host name such as docs.example.org'))
    render(<TrustedSites />)
    await userEvent.type(screen.getByLabelText('Site'), 'not a site')
    await userEvent.type(screen.getByLabelText('Reason'), 'testing')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText(/must be a plain host name/)).toBeTruthy()
  })

  it('removes a change only after it is confirmed in place', async () => {
    api.deleteTrustedSite.mockResolvedValue({ removed: 'docs.example.org' })
    render(<TrustedSites />)
    await userEvent.click(await screen.findByRole('button', { name: 'Remove' }))
    expect(api.deleteTrustedSite).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Confirm removal' }))
    await waitFor(() => expect(api.deleteTrustedSite).toHaveBeenCalledWith('docs.example.org'))
    expect(await screen.findByText(/The change for docs\.example\.org is removed\./)).toBeTruthy()
  })

  it('keeps a change when removal is not confirmed', async () => {
    render(<TrustedSites />)
    await userEvent.click(await screen.findByRole('button', { name: 'Remove' }))
    await userEvent.click(screen.getByRole('button', { name: 'Keep' }))
    expect(api.deleteTrustedSite).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Remove' })).toBeTruthy()
  })
})
