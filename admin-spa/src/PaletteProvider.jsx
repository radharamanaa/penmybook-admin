import { createContext, useContext, useEffect, useState } from 'react'
import { palettes } from './palettes'

// Same behaviour as the authors' site (frontend/src/PaletteProvider.jsx): the
// chosen palette is applied to :root as CSS vars and persisted to localStorage,
// so every admin screen recolors live and the choice sticks across sessions.
const PaletteContext = createContext(null)

const STORAGE_KEY = 'bookforge-admin-palette'

export function PaletteProvider({ children }) {
  const [paletteId, setPaletteId] = useState(() => {
    // Wrap the WHOLE init: localStorage access throws in private-mode browsers
    // (Safari/Firefox), which would otherwise crash the provider → white screen.
    try {
      if (typeof window === 'undefined') return 'dark' // SSR guard
      const stored = window.localStorage.getItem(STORAGE_KEY)
      if (stored && palettes[stored]) return stored
      // First run: honour the OS dark/light preference, then the choice sticks.
      // dark OS -> Forge dark, light OS -> Studio (matches the authors' site).
      const prefersDark =
        window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
      return prefersDark ? 'dark' : 'studio'
    } catch {
      return 'dark'
    }
  })

  useEffect(() => {
    const palette = palettes[paletteId]
    if (!palette) return
    const root = document.documentElement
    Object.entries(palette.vars).forEach(([key, value]) => {
      root.style.setProperty(key, value)
    })
    try {
      window.localStorage.setItem(STORAGE_KEY, paletteId)
    } catch {
      // private mode / quota exceeded — the CSS vars are already applied; just
      // skip persistence (palette resets to the default on next load).
    }
  }, [paletteId])

  return (
    <PaletteContext.Provider value={{ paletteId, setPaletteId, palette: palettes[paletteId] }}>
      {children}
    </PaletteContext.Provider>
  )
}

export const usePalette = () => useContext(PaletteContext)
