import assert from 'node:assert/strict'
import { test } from 'node:test'
import { byUrgency, deadlineInfo, inTeamPool, similarIds } from './queue.js'

const at = (min) => new Date(Date.UTC(2026, 0, 1, 12, min)).toISOString()

test('queue defaults to nearest legal deadline first, then oldest upload', () => {
  const items = [
    { id: 1, createdAt: at(0) },
    { id: 2, createdAt: at(5), slaDeadline: at(300) },
    { id: 3, createdAt: at(1) },
    { id: 4, createdAt: at(9), slaDeadline: at(120) },
  ]
  assert.deepEqual([...items].sort(byUrgency).map((i) => i.id), [4, 2, 1, 3])
})

test('deadline chip wording', () => {
  const now = Date.parse(at(0))
  assert.deepEqual(deadlineInfo(null, now), { tone: 'none', text: 'No legal deadline' })
  assert.deepEqual(deadlineInfo(at(90), now), { tone: 'ok', text: 'Due in 1h 30m' })
  assert.deepEqual(deadlineInfo(at(20), now), { tone: 'soon', text: 'Due in 20m' })
  assert.deepEqual(deadlineInfo(at(-5), now), { tone: 'overdue', text: 'Overdue by 5m' })
})

test('near-duplicates are found by perceptual hash distance', () => {
  const base = { id: 1, phash: 'ffff0000ffff0000' }
  const items = [
    base,
    { id: 2, phash: 'ffff0000ffff0001' }, // 1 bit away
    { id: 3, phash: 'ffff0000ffff00ff' }, // 8 bits
    { id: 4, phash: '0000ffff0000ffff' }, // 64 bits
    { id: 5, phash: null },
  ]
  assert.deepEqual(similarIds(base, items), [2, 3])
  assert.deepEqual(similarIds(base, items, 2), [2])
})

test('an unclaimed item routed to a team belongs to that team and seniors', () => {
  const routed = { routedTeam: 'apac', assignedTo: null }
  assert.equal(inTeamPool(routed, { team: 'apac' }), true)
  assert.equal(inTeamPool(routed, { team: 'core' }), false)
  assert.equal(inTeamPool(routed, { team: 'core', senior: true }), true)
  assert.equal(inTeamPool({ ...routed, assignedTo: 'alex' }, { team: 'core' }), true)
  assert.equal(inTeamPool({ routedTeam: null }, { team: 'core' }), true)
})
