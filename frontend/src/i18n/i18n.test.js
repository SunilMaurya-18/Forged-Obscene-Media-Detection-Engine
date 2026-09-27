import assert from 'node:assert/strict'
import { test } from 'node:test'
import en from '../i18n/en.json' with { type: 'json' }
import hi from '../i18n/hi.json' with { type: 'json' }

function keys(obj, prefix = '') {
  return Object.entries(obj).flatMap(([k, v]) => (v && typeof v === 'object' ? keys(v, `${prefix}${k}.`) : [`${prefix}${k}`]))
}

test('Hindi covers every English end-user string', () => {
  const missing = keys(en).filter((k) => !keys(hi).includes(k))
  assert.deepEqual(missing, [])
})

test('every verdict has a distinct label, so colour is never the only cue', () => {
  for (const lang of [en, hi]) {
    const labels = ['LIKELY_FORGED', 'NEEDS_REVIEW', 'LOOKS_GENUINE'].map((v) => lang.verdict[v].label)
    assert.equal(new Set(labels).size, 3)
    const stages = ['IN_REVIEW', 'REMOVED', 'NO_ACTION'].map((s) => lang.status.stages[s].title)
    assert.equal(new Set(stages).size, 3)
  }
})

test('placeholders survive translation', () => {
  const vars = (s) => [...String(s).matchAll(/\{\{(\w+)\}\}/g)].map((m) => m[1]).sort().join(',')
  const flat = (obj) => Object.fromEntries(keys(obj).map((k) => [k, k.split('.').reduce((o, p) => o[p], obj)]))
  const e = flat(en)
  const h = flat(hi)
  for (const k of Object.keys(e)) assert.equal(vars(h[k]), vars(e[k]), k)
})

test('inline link tags survive translation', () => {
  const tags = (s) => [...String(s).matchAll(/<(\w+)>/g)].map((m) => m[1]).sort().join(',')
  assert.equal(tags(en.wizard.review.agree), 'privacy,terms')
  assert.equal(tags(hi.wizard.review.agree), 'privacy,terms')
})
