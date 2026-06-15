import { useState } from 'react'
import { paletteList } from '../palettes'
import { usePalette } from '../PaletteProvider'

// Plain-CSS port of the authors' site PaletteSwitcher (that one is Tailwind;
// the admin console is not). Fixed top-right, recolors every screen live.
export function PaletteSwitcher() {
  const { paletteId, setPaletteId, palette } = usePalette()
  const [open, setOpen] = useState(false)

  return (
    <div style={{ position: 'fixed', top: 12, right: 12, zIndex: 1000, textAlign: 'right' }}>
      <button
        onClick={() => setOpen(!open)}
        style={{
          background: 'var(--panel)',
          color: 'var(--text)',
          border: '1px solid var(--border)',
          borderRadius: 6,
          padding: '6px 12px',
          fontSize: '0.75rem',
          fontWeight: 600,
          letterSpacing: '0.06em',
          textTransform: 'uppercase',
          cursor: 'pointer',
        }}
      >
        {open ? 'Hide' : 'Theme'}
      </button>

      {open && (
        <div
          style={{
            marginTop: 8,
            width: 272,
            background: 'var(--panel)',
            border: '1px solid var(--border)',
            borderRadius: 8,
            padding: 16,
            boxShadow: '0 8px 28px rgba(0,0,0,0.35)',
            textAlign: 'left',
          }}
        >
          <div style={{ marginBottom: 12 }}>
            <div style={{ fontSize: '0.62rem', textTransform: 'uppercase', letterSpacing: '0.15em', color: 'var(--muted)' }}>
              Palette
            </div>
            <div style={{ fontSize: '1.15rem', marginTop: 2, color: 'var(--text)' }}>{palette.name}</div>
            <div style={{ fontSize: '0.75rem', marginTop: 2, fontStyle: 'italic', color: 'var(--muted)' }}>
              {palette.tagline}
            </div>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {paletteList.map((p) => {
              const active = p.id === paletteId
              return (
                <button
                  key={p.id}
                  onClick={() => setPaletteId(p.id)}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 12,
                    padding: '8px 10px',
                    textAlign: 'left',
                    cursor: 'pointer',
                    background: active ? 'var(--panel-2)' : 'transparent',
                    border: `1px solid ${active ? 'var(--accent)' : 'transparent'}`,
                    borderRadius: 6,
                  }}
                >
                  <div style={{ display: 'flex', gap: 2, flexShrink: 0 }}>
                    {p.swatches.map((c, i) => (
                      <div
                        key={i}
                        style={{
                          width: 16,
                          height: 32,
                          background: c,
                          borderRadius:
                            i === 0 ? '3px 0 0 3px' : i === p.swatches.length - 1 ? '0 3px 3px 0' : 0,
                          border: '1px solid rgba(128,128,128,0.25)',
                        }}
                      />
                    ))}
                  </div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: '0.85rem', fontWeight: 600, color: 'var(--text)' }}>{p.name}</div>
                    <div style={{ fontSize: '0.7rem', color: 'var(--muted)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {p.tagline}
                    </div>
                  </div>
                </button>
              )
            })}
          </div>

          <div style={{ marginTop: 14, paddingTop: 12, borderTop: '1px solid var(--border)' }}>
            <div style={{ fontSize: '0.62rem', textTransform: 'uppercase', letterSpacing: '0.15em', color: 'var(--muted)', marginBottom: 4 }}>
              Saved automatically
            </div>
            <div style={{ fontSize: '0.7rem', color: 'var(--muted)' }}>
              Applies across every admin screen.
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
