import assert from 'node:assert/strict'
import { test } from 'node:test'
import { canReset, secondsUntil, spacedPin, storyComplete } from './demo.js'

test('secondsUntil counts down and goes negative once the deadline passes', () => {
  const now = Date.parse('2026-09-28T10:00:00Z')
  assert.equal(secondsUntil('2026-09-28T10:30:00Z', now), 1800)
  assert.equal(secondsUntil('2026-09-28T09:48:00Z', now), -720)
  assert.equal(secondsUntil(null, now), null)
  assert.equal(secondsUntil('not a date', now), null)
})

test('a story is complete only with all five parts', () => {
  const story = { flagged: {}, appeal: {}, report: {}, atRisk: {}, breached: {} }
  assert.equal(storyComplete(story), true)
  assert.equal(storyComplete({ ...story, appeal: undefined }), false)
  assert.equal(storyComplete(null), false)
})

test('visitors reset only a public demo; seniors reset any demo', () => {
  const senior = { roles: ['ROLE_MODERATOR', 'ROLE_SENIOR_MODERATOR'] }
  const moderator = { roles: ['ROLE_MODERATOR'] }
  assert.equal(canReset({ enabled: true, publicMode: true }, null), true)
  assert.equal(canReset({ enabled: true, publicMode: false }, null), false)
  assert.equal(canReset({ enabled: true, publicMode: false }, moderator), false)
  assert.equal(canReset({ enabled: true, publicMode: false }, senior), true)
  assert.equal(canReset({ enabled: false, publicMode: true }, senior), false)
})

test('PINs are spaced for reading', () => {
  assert.equal(spacedPin('482913'), '482 913')
  assert.equal(spacedPin('12'), '12')
  assert.equal(spacedPin(undefined), '')
})
