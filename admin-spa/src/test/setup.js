// Vitest setup for admin-spa.
//
// Deliberately smaller than frontend/'s: this SPA holds its auth token in a
// module-level variable (decision D7 — never localStorage), so there is no
// storage to polyfill, and it has zero UI dependencies, so there is nothing
// to mock.
import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

afterEach(() => {
  cleanup()
})
