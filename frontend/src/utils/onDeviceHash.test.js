// Run with `npm test` (node --test). Vectors come from ml-service/tools/phash_vectors.py (real imagehash).
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { hammingDistance, phashFromGray32, phashFromPixels } from './onDeviceHash.js'

const vectors = JSON.parse(readFileSync(new URL('./phash-vectors.json', import.meta.url), 'utf8'))

test('DCT, median and hex encoding match imagehash.phash', () => {
  for (const v of vectors.dct) {
    assert.equal(phashFromGray32(v.gray32.flat()), v.hash, v.name)
  }
})

test('full pipeline (Pillow L + LANCZOS resize) matches imagehash.phash', () => {
  for (const v of vectors.pipeline) {
    assert.equal(phashFromPixels(Uint8Array.from(v.rgb), v.width, v.height, 3), v.hash, v.name)
  }
})

test('hamming distance', () => {
  assert.equal(hammingDistance('0000000000000000', '0000000000000000'), 0)
  assert.equal(hammingDistance('ffffffffffffffff', '0000000000000000'), 64)
  assert.equal(hammingDistance('c2423d3d3b783cc6', 'c2423d3d3b783cc7'), 1)
})
