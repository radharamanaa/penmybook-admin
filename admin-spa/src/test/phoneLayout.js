// Install a matchMedia whose phone query matches `matches`; flip it with setMatches.
export function stubPhoneLayout(matches) {
  let current = matches
  const listeners = new Set()
  window.matchMedia = (query) => ({
    get matches() { return current },
    media: query, onchange: null,
    addEventListener: (_t, fn) => listeners.add(fn),
    removeEventListener: (_t, fn) => listeners.delete(fn),
    addListener: (fn) => listeners.add(fn),
    removeListener: (fn) => listeners.delete(fn),
    dispatchEvent() { return false },
  })
  return {
    setMatches(next) {
      current = next
      listeners.forEach((fn) => fn({ matches: next, media: '(max-width: 767px)' }))
    },
  }
}
