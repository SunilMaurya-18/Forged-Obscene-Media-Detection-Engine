import assert from 'node:assert/strict'
import { test } from 'node:test'
import { actionLabel, c2paStatus, frameBars, isVideoFile, mediaProblem } from './media.js'
import { STATEMENT_MAX, appealStage, cell, exposureMeter, normalizeNoticeNumber, pct, periodOptions } from './appeals.js'

test('videos are recognised by type or extension, with their own size limit', () => {
  assert.ok(isVideoFile({ type: 'video/mp4', name: 'x' }))
  assert.ok(isVideoFile({ type: '', name: 'clip.WEBM' }))
  assert.ok(!isVideoFile({ type: 'image/png', name: 'x.png' }))
  assert.equal(mediaProblem({ type: 'video/mp4', name: 'a.mp4', size: 40 * 1024 * 1024 }), null)
  assert.match(mediaProblem({ type: 'image/png', name: 'a.png', size: 40 * 1024 * 1024 }), /25 MB/)
  assert.match(mediaProblem({ type: 'application/pdf', name: 'a.pdf', size: 10 }), /not an image or video/)
})

test('frame strip bars carry band and peak, not just colour', () => {
  const bars = frameBars({ frames: [[0, 0.1, 0.0], [0.5, 0.6, 0.1], [1, 0.9, 0.2]], peak: { index: 2 } }, { forgeryReview: 0.5, forgeryAutoFlag: 0.85 })
  assert.deepEqual(bars.map((b) => b.band), ['ok', 'review', 'flag'])
  assert.deepEqual(bars.map((b) => b.peak), [false, false, true])
  assert.deepEqual(frameBars(null, {}), [])
})

test('C2PA labels', () => {
  assert.equal(c2paStatus('VALID_UNTRUSTED').tone, 'warn')
  assert.equal(c2paStatus('INVALID').tone, 'bad')
  assert.equal(c2paStatus('SOMETHING').label, 'SOMETHING')
  assert.equal(actionLabel('c2pa.color_adjustments'), 'color adjustments')
})

test('notice numbers are normalised the same way as the backend', () => {
  assert.equal(normalizeNoticeNumber('upl 7k3q 9xpa'), 'UPL-7K3Q-9XPA')
  assert.equal(normalizeNoticeNumber('7K3Q9XPA'), 'UPL-7K3Q-9XPA')
  assert.equal(normalizeNoticeNumber('FMD-7K3Q-9XPA'), null, 'complaint case numbers are a different series')
  assert.equal(normalizeNoticeNumber('UPL-OK3Q-9XPA'), null)
  assert.equal(STATEMENT_MAX, 1000)
})

test('appeal stages distinguish a granted appeal still awaiting second review', () => {
  assert.equal(appealStage(null), null)
  assert.equal(appealStage({ status: 'OPEN' }), 'OPEN')
  assert.equal(appealStage({ status: 'GRANTED', reinstated: false }), 'GRANTED_PENDING')
  assert.equal(appealStage({ status: 'GRANTED', reinstated: true }), 'GRANTED')
  assert.equal(appealStage({ status: 'UPHELD' }), 'UPHELD')
})

test('transparency helpers', () => {
  const opts = periodOptions(new Date(Date.UTC(2026, 8, 28)))
  assert.deepEqual(opts.map((o) => o.value), ['last-30d', '2026-09', '2026-08', '2026-Q3', '2026'])
  assert.equal(pct(0.8734), '87.3%')
  assert.equal(pct(1), '100%')
  assert.equal(pct(null), 'n/a')
  assert.equal(cell(null, 5), '<5')
  assert.equal(cell(0, 5), '0')
  assert.equal(cell(12, 5), '12')
})

test('exposure meter warns in the last 20% and not during a break', () => {
  assert.deepEqual(exposureMeter({ exposures: 8, limit: 10, onBreak: false }), { ratio: 0.8, warn: true })
  assert.equal(exposureMeter({ exposures: 10, limit: 10, onBreak: true }).warn, false)
  assert.equal(exposureMeter(null).ratio, 0)
})
