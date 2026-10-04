// Vitest setup for admin-spa.
//
// Deliberately smaller than frontend/'s: this SPA holds its auth token in a
// module-level variable (decision D7 — never localStorage), so there is no
// storage to polyfill, and it has zero UI dependencies, so there is nothing
// to mock.
import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

afterEach(() => {
  cleanup()
})

// jsdom has neither; the shell and scroll-into-view use both.
if (!window.matchMedia) {
  window.matchMedia = (query) => ({
    matches: false, media: query, onchange: null,
    addEventListener() {}, removeEventListener() {},
    addListener() {}, removeListener() {}, dispatchEvent() { return false },
  })
}
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = vi.fn()
}
// jsdom logs "Not implemented" for scrollTo; the drawer's scroll lock calls it.
window.scrollTo = vi.fn()
