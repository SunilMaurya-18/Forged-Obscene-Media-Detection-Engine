/**
 * Phase 18: perceptual hash computed entirely in the browser. No network calls live in this file.
 *
 * Bit-for-bit port of `imagehash.phash` (hash_size 8) as the ML service runs it, so a hash made here
 * can be compared with the hashes the backend stores:
 *   RGB → Pillow "L" greyscale → Pillow LANCZOS resize to 32×32 (same fixed-point resampler)
 *   → 2-D DCT-II → top-left 8×8 → bit = coefficient > median → 16 hex chars, row-major.
 *
 * Browsers decode JPEGs with slightly different IDCTs than libjpeg, so a photo can land a few bits
 * away from the server's hash; the prescreen endpoint therefore matches by Hamming distance.
 *
 * StopNCII itself uses PDQ for images. This module exposes the algorithm name with every hash so a
 * PDQ implementation can be dropped in without changing the prescreen contract.
 */

export const ALGORITHM = 'phash'
const HASH_SIZE = 8
const IMG_SIZE = 32
const PRECISION_BITS = 32 - 8 - 2
/** Very large decodes are pre-shrunk by the browser before hashing (costs a bit or two of parity). */
const MAX_DECODE_SIDE = 4096

function sinc(x) {
  if (x === 0) return 1
  const px = x * Math.PI
  return Math.sin(px) / px
}

function lanczos(x) {
  return x >= -3 && x < 3 ? sinc(x) * sinc(x / 3) : 0
}

/** Pillow's precompute_coeffs + normalize_coeffs_8bpc for one axis. */
function coefficients(inSize, outSize) {
  const scale = inSize / outSize
  const filterscale = Math.max(scale, 1)
  const support = 3 * filterscale
  const ksize = Math.ceil(support) * 2 + 1
  const kk = new Int32Array(outSize * ksize)
  const bounds = new Int32Array(outSize * 2)
  const ss = 1 / filterscale
  const pre = new Float64Array(ksize)
  for (let xx = 0; xx < outSize; xx++) {
    const center = (xx + 0.5) * scale
    let xmin = Math.trunc(center - support + 0.5)
    if (xmin < 0) xmin = 0
    let xmax = Math.trunc(center + support + 0.5)
    if (xmax > inSize) xmax = inSize
    xmax -= xmin
    let ww = 0
    for (let x = 0; x < xmax; x++) {
      const w = lanczos((x + xmin - center + 0.5) * ss)
      pre[x] = w
      ww += w
    }
    for (let x = 0; x < ksize; x++) {
      const w = x < xmax && ww !== 0 ? pre[x] / ww : 0
      kk[xx * ksize + x] = Math.trunc(w * (1 << PRECISION_BITS) + (w < 0 ? -0.5 : 0.5))
    }
    bounds[xx * 2] = xmin
    bounds[xx * 2 + 1] = xmax
  }
  return { kk, bounds, ksize }
}

function clip8(v) {
  // Pillow keeps the accumulator in a 32-bit int; the arithmetic shift matches its clip8().
  const s = v >> PRECISION_BITS
  return s < 0 ? 0 : s > 255 ? 255 : s
}

/** Pillow ImagingResample (horizontal pass, then vertical) on a single 8-bit channel. */
export function resizeLanczos(gray, width, height, outW = IMG_SIZE, outH = IMG_SIZE) {
  const round = 1 << (PRECISION_BITS - 1)
  let src = gray
  let w = width
  if (outW !== width) {
    const { kk, bounds, ksize } = coefficients(width, outW)
    const tmp = new Uint8Array(outW * height)
    for (let y = 0; y < height; y++) {
      const row = y * width
      for (let xx = 0; xx < outW; xx++) {
        const xmin = bounds[xx * 2]
        const n = bounds[xx * 2 + 1]
        let acc = round
        for (let x = 0; x < n; x++) acc = (acc + src[row + xmin + x] * kk[xx * ksize + x]) | 0
        tmp[y * outW + xx] = clip8(acc)
      }
    }
    src = tmp
    w = outW
  }
  if (outH !== height) {
    const { kk, bounds, ksize } = coefficients(height, outH)
    const out = new Uint8Array(w * outH)
    for (let yy = 0; yy < outH; yy++) {
      const ymin = bounds[yy * 2]
      const n = bounds[yy * 2 + 1]
      for (let x = 0; x < w; x++) {
        let acc = round
        for (let y = 0; y < n; y++) acc = (acc + src[(ymin + y) * w + x] * kk[yy * ksize + y]) | 0
        out[yy * w + x] = clip8(acc)
      }
    }
    src = out
  }
  return src
}

/** Pillow's RGB → "L": (R·19595 + G·38470 + B·7471 + 0x8000) >> 16. `rgba` has 4 bytes per pixel. */
export function toLuma(pixels, width, height, channels = 4) {
  const out = new Uint8Array(width * height)
  for (let i = 0, p = 0; i < out.length; i++, p += channels) {
    out[i] = (pixels[p] * 19595 + pixels[p + 1] * 38470 + pixels[p + 2] * 7471 + 0x8000) >>> 16
  }
  return out
}

const COS = (() => {
  const table = new Float64Array(HASH_SIZE * IMG_SIZE)
  for (let k = 0; k < HASH_SIZE; k++) {
    for (let n = 0; n < IMG_SIZE; n++) table[k * IMG_SIZE + n] = Math.cos((Math.PI * k * (2 * n + 1)) / (2 * IMG_SIZE))
  }
  return table
})()

/** pHash of the 32×32 greyscale image imagehash would see (row-major, 1024 values). */
export function phashFromGray32(gray32) {
  // DCT-II along axis 0 (columns), keeping the first 8 rows of coefficients…
  const colDct = new Float64Array(HASH_SIZE * IMG_SIZE)
  for (let u = 0; u < HASH_SIZE; u++) {
    for (let x = 0; x < IMG_SIZE; x++) {
      let s = 0
      for (let y = 0; y < IMG_SIZE; y++) s += gray32[y * IMG_SIZE + x] * COS[u * IMG_SIZE + y]
      colDct[u * IMG_SIZE + x] = 2 * s
    }
  }
  // …then along axis 1, keeping the first 8 columns.
  const low = new Float64Array(HASH_SIZE * HASH_SIZE)
  for (let u = 0; u < HASH_SIZE; u++) {
    for (let v = 0; v < HASH_SIZE; v++) {
      let s = 0
      for (let x = 0; x < IMG_SIZE; x++) s += colDct[u * IMG_SIZE + x] * COS[v * IMG_SIZE + x]
      low[u * HASH_SIZE + v] = 2 * s
    }
  }
  const sorted = Float64Array.from(low).sort()
  const median = (sorted[31] + sorted[32]) / 2
  let hex = ''
  for (let nibble = 0; nibble < 16; nibble++) {
    let value = 0
    for (let b = 0; b < 4; b++) value = (value << 1) | (low[nibble * 4 + b] > median ? 1 : 0)
    hex += value.toString(16)
  }
  return hex
}

/** Full pipeline from raw pixels (RGBA by default). */
export function phashFromPixels(pixels, width, height, channels = 4) {
  return phashFromGray32(resizeLanczos(toLuma(pixels, width, height, channels), width, height))
}

export function hammingDistance(a, b) {
  let d = 0
  for (let i = 0; i < a.length; i += 4) {
    let x = parseInt(a.slice(i, i + 4), 16) ^ parseInt(b.slice(i, i + 4), 16)
    while (x) {
      d += x & 1
      x >>>= 1
    }
  }
  return d
}

async function decode(blob) {
  const bitmap = await createImageBitmap(blob, { colorSpaceConversion: 'none', premultiplyAlpha: 'none' })
  const scale = Math.min(1, MAX_DECODE_SIDE / Math.max(bitmap.width, bitmap.height))
  const width = Math.max(1, Math.round(bitmap.width * scale))
  const height = Math.max(1, Math.round(bitmap.height * scale))
  const canvas =
    typeof OffscreenCanvas !== 'undefined'
      ? new OffscreenCanvas(width, height)
      : Object.assign(document.createElement('canvas'), { width, height })
  const ctx = canvas.getContext('2d', { willReadFrequently: true })
  ctx.drawImage(bitmap, 0, 0, width, height)
  bitmap.close?.()
  return { data: ctx.getImageData(0, 0, width, height).data, width, height }
}

/**
 * Hash an image File/Blob on this device. Returns only derived values; the bytes stay local.
 * @returns {Promise<{hash: string, algorithm: string, width: number, height: number, ms: number}>}
 */
export async function hashImageOnDevice(blob) {
  const started = performance.now()
  const { data, width, height } = await decode(blob)
  return { hash: phashFromPixels(data, width, height), algorithm: ALGORITHM, width, height, ms: performance.now() - started }
}
