import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  MAX_CASES,
  dateInputValue,
  expiryInstant,
  expiryProblem,
  filingProblem,
  groupRequests,
  packageDownloadable,
  parseCaseNumbers,
  timeLeft,
} from './handover.js'

const NOW = new Date('2026-09-29T10:00:00Z')

test('case numbers can be pasted in any form, once each', () => {
  assert.deepEqual(parseCaseNumbers('INV-7K3Q-9XPA, inv 2b4d6f8h\n7k3q9xpa'), {
    cases: ['INV-7K3Q-9XPA', 'INV-2B4D-6F8H'],
    invalid: [],
  })
  assert.deepEqual(parseCaseNumbers('FMD-7K3Q-9XPA INV-7K3Q-9XPA').invalid, ['FMD-7K3Q-9XPA'])
  assert.deepEqual(parseCaseNumbers(''), { cases: [], invalid: [] })
})

test('an expiry date means the end of that day, within the allowed term', () => {
  const iso = expiryInstant('2026-10-29')
  const d = new Date(iso)
  assert.equal(d.getDate(), 29)
  assert.equal(d.getHours(), 23)
  assert.equal(expiryInstant('29/10/2026'), null)
  assert.equal(expiryProblem(dateInputValue(NOW, 30), NOW), null)
  assert.match(expiryProblem(dateInputValue(NOW, -1), NOW), /more than an hour/)
  assert.match(expiryProblem(dateInputValue(NOW, 200), NOW), /at most 180 days/)
  assert.match(expiryProblem('', NOW), /Choose the date/)
})

test('the filing form says what is missing before anything is sent', () => {
  const ok = {
    authority: 'Cyber Crime Cell (fictional)',
    orderReference: 'DEMO/1',
    investigator: 'investigator',
    caseText: 'INV-7K3Q-9XPA',
    expiry: dateInputValue(NOW, 30),
  }
  assert.equal(filingProblem(ok, NOW), null)
  assert.match(filingProblem({ ...ok, authority: ' ' }, NOW), /authority/)
  assert.match(filingProblem({ ...ok, orderReference: '' }, NOW), /reference/)
  assert.match(filingProblem({ ...ok, investigator: '' }, NOW), /investigator/)
  assert.match(filingProblem({ ...ok, caseText: 'nope' }, NOW), /Not a case number: nope/)
  assert.match(filingProblem({ ...ok, caseText: '' }, NOW), /at least one case/)
  const many = Array.from({ length: MAX_CASES + 1 }, (_, i) => `INV-2222-22${'23456789ABCDEFGHJKMNPQRSTUVWXYZ'[i]}2`).join(' ')
  assert.match(filingProblem({ ...ok, caseText: many }, NOW), /at most 25 cases/)
})

test('time left reads naturally and ends at expired', () => {
  assert.equal(timeLeft('2026-10-09T10:00:00Z', NOW), '10 days left')
  assert.equal(timeLeft('2026-09-29T15:30:00Z', NOW), '5 hours left')
  assert.equal(timeLeft('2026-09-29T11:00:00Z', NOW), '1 hour left')
  assert.equal(timeLeft('2026-09-29T10:20:00Z', NOW), '20 minutes left')
  assert.equal(timeLeft('2026-09-29T09:59:00Z', NOW), 'expired')
  assert.equal(timeLeft(null, NOW), 'expired')
})

test('requests are grouped by what needs doing', () => {
  const g = groupRequests([
    { requestNumber: 'A', status: 'APPROVED' },
    { requestNumber: 'B', status: 'PENDING' },
    { requestNumber: 'C', status: 'EXPIRED' },
    { requestNumber: 'D', status: 'REVOKED' },
  ])
  assert.deepEqual(g.pending.map((r) => r.requestNumber), ['B'])
  assert.deepEqual(g.active.map((r) => r.requestNumber), ['A'])
  assert.deepEqual(g.closed.map((r) => r.requestNumber), ['C', 'D'])
})

test('a package downloads only inside its window', () => {
  assert.equal(packageDownloadable({ downloadExpiresAt: '2026-09-30T10:00:00Z' }, NOW), true)
  assert.equal(packageDownloadable({ downloadExpiresAt: '2026-09-29T09:00:00Z' }, NOW), false)
  assert.equal(packageDownloadable(null, NOW), false)
})
