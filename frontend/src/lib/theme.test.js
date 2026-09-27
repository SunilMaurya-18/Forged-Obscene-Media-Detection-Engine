import assert from 'node:assert/strict'
import { beforeEach, test } from 'node:test'
import { THEME_KEY, initialTheme, saveTheme } from './theme.js'

let store
let prefersLight

beforeEach(() => {
  store = new Map()
  prefersLight = false
  globalThis.localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => store.set(k, String(v)),
  }
  globalThis.window = { matchMedia: (q) => ({ matches: q.includes('light') && prefersLight }) }
})

test('first visit follows the system preference', () => {
  assert.equal(initialTheme(), 'dark')
  prefersLight = true
  assert.equal(initialTheme(), 'light')
})

test('a saved choice wins over the system preference', () => {
  prefersLight = true
  saveTheme('dark')
  assert.equal(store.get(THEME_KEY), 'dark')
  assert.equal(initialTheme(), 'dark')
})

test('an unknown saved value is ignored', () => {
  store.set(THEME_KEY, 'sepia')
  assert.equal(initialTheme(), 'dark')
})
