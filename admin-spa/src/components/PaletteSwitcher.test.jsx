import { render, screen } from '@testing-library/react'
import { stubPhoneLayout } from '../test/phoneLayout.js'
import { PaletteProvider } from '../PaletteProvider.jsx'
import { PaletteSwitcher } from './PaletteSwitcher.jsx'

const holder = () => screen.getByRole('button', { name: /theme/i }).parentElement

it('sits top-right on desktop', () => {
  stubPhoneLayout(false)
  render(<PaletteProvider><PaletteSwitcher /></PaletteProvider>)
  expect(holder().style.top).toBe('12px')
  expect(holder().style.bottom).toBe('')
})

it('sits bottom-right on a phone, above the drawer', () => {
  stubPhoneLayout(true)
  render(<PaletteProvider><PaletteSwitcher /></PaletteProvider>)
  expect(holder().style.bottom).toBe('16px')
  expect(holder().style.top).toBe('')
  expect(holder().style.zIndex).toBe('40')
})
