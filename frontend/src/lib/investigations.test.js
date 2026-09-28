import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  eventLabel,
  groupByDay,
  matchLabel,
  normalizeInvestigationNumber,
  normalizePlatform,
  postRefProblem,
} from './investigations.js'

test('investigation numbers are normalised the same way as the backend', () => {
  assert.equal(normalizeInvestigationNumber('INV-7K3Q-9XPA'), 'INV-7K3Q-9XPA')
  assert.equal(normalizeInvestigationNumber(' inv 7k3q 9xpa '), 'INV-7K3Q-9XPA')
  assert.equal(normalizeInvestigationNumber('7K3Q9XPA'), 'INV-7K3Q-9XPA')
  assert.equal(normalizeInvestigationNumber('FMD-7K3Q-9XPA'), null, 'complaint numbers are not investigations')
  assert.equal(normalizeInvestigationNumber('INV-OK3Q-9XPA'), null)
  assert.equal(normalizeInvestigationNumber(''), null)
})

test('platform names follow the server rules', () => {
  assert.equal(normalizePlatform(' Pixel Gram '), 'pixel-gram')
  assert.equal(normalizePlatform('vid_share'), 'vid-share')
  assert.equal(normalizePlatform('chirp.example'), 'chirp.example')
  assert.equal(normalizePlatform(''), null)
  assert.equal(normalizePlatform('-leading'), null)
  assert.equal(normalizePlatform('x'.repeat(49)), null)
})

test('post IDs must be opaque: no links, handles or spaces', () => {
  assert.equal(postRefProblem('pg-7Q2X91'), null)
  assert.equal(postRefProblem('ch:4410982731'), null)
  assert.match(postRefProblem('https://pixelgram.example/p/1'), /not a link/)
  assert.match(postRefProblem('pixelgram.example/p/1'), /not a link/)
  assert.match(postRefProblem('@someone'), /username/)
  assert.match(postRefProblem('two words'), /spaces/)
  assert.match(postRefProblem(''), /Enter/)
  assert.match(postRefProblem('x'.repeat(129)), /128/)
})

test('match labels explain how a sighting was linked', () => {
  assert.equal(matchLabel('PHASH', 0), 'Perceptual hash · identical')
  assert.equal(matchLabel('PHASH', 3), 'Perceptual hash · 3/64 bits differ')
  assert.equal(matchLabel('PROVENANCE', null), 'Provenance ID')
  assert.equal(matchLabel('SOMETHING', null), 'SOMETHING')
})

test('event labels and day grouping', () => {
  assert.equal(eventLabel({ type: 'SIGHTING_LINKED' }), 'Sighting linked')
  assert.equal(eventLabel({ type: 'REUPLOAD_DETECTED' }), 'Reupload detected')
  const groups = groupByDay(
    [
      { at: '2026-09-27T09:00:00Z', type: 'OPENED' },
      { at: '2026-09-28T12:00:00Z', type: 'NOTE_ADDED' },
      { at: '2026-09-28T11:00:00Z', type: 'SIGHTING_LINKED' },
    ],
    'en-GB',
  )
  assert.equal(groups.length, 2)
  assert.deepEqual(
    groups[0].entries.map((e) => e.type),
    ['NOTE_ADDED', 'SIGHTING_LINKED'],
  )
  assert.equal(groupByDay(null).length, 0)
})
