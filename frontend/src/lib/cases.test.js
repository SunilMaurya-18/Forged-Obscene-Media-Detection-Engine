import assert from 'node:assert/strict'
import { test } from 'node:test'
import { DETAILS_MAX, composeDetails, contactChannel, isValidPin, normalizeCaseNumber } from './cases.js'

test('case numbers are normalised the same way as the backend', () => {
  assert.equal(normalizeCaseNumber('FMD-7K3Q-9XPA'), 'FMD-7K3Q-9XPA')
  assert.equal(normalizeCaseNumber(' fmd 7k3q 9xpa '), 'FMD-7K3Q-9XPA')
  assert.equal(normalizeCaseNumber('7K3Q9XPA'), 'FMD-7K3Q-9XPA')
  assert.equal(normalizeCaseNumber('FMD-7K3Q-9XP'), null, 'too short')
  assert.equal(normalizeCaseNumber('FMD-OK3Q-9XPA'), null, 'O is excluded to avoid confusion with 0')
  assert.equal(normalizeCaseNumber('FMD-1K3Q-9XPA'), null, '1 is excluded to avoid confusion with I')
  assert.equal(normalizeCaseNumber(''), null)
})

test('PINs are exactly six digits', () => {
  assert.ok(isValidPin('042917'))
  assert.ok(!isValidPin('42917'))
  assert.ok(!isValidPin('04291a'))
})

test('contact accepts email, phone or nothing', () => {
  assert.equal(contactChannel(''), '')
  assert.equal(contactChannel('  '), '')
  assert.equal(contactChannel('reporter@example.com'), 'EMAIL')
  assert.equal(contactChannel('+91 98765-43210'), 'SMS')
  assert.equal(contactChannel('(555) 010 9999'), 'SMS')
  assert.equal(contactChannel('not a contact'), null)
  assert.equal(contactChannel('12345'), null)
})

test('details combine both optional fields', () => {
  assert.equal(composeDetails('', ''), '')
  assert.equal(composeDetails(' a forum ', ''), 'Seen at: a forum')
  assert.equal(composeDetails('app', 'posted yesterday'), 'Seen at: app\n\nposted yesterday')
  assert.equal(DETAILS_MAX, 1000)
})
