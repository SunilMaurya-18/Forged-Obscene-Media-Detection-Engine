/**
 * Capture real Forged Media Detection screens for a SEOPulse-style product film.
 * Requires docker stack on :3000. Loads moderator creds from repo .env (never logged).
 *
 * Usage: node brag-output-seopulse-style/capture/capture.mjs
 */
import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const require = createRequire(import.meta.url)
const { chromium } = require('../../e2e/node_modules/@playwright/test/index.js')

const ROOT = fileURLToPath(new URL('../..', import.meta.url))
const APP = process.env.BRAG_APP_URL || 'http://localhost:3000'
const OUT = fileURLToPath(new URL('../composition/assets/ui/', import.meta.url))
mkdirSync(OUT, { recursive: true })

function loadEnv() {
  const p = join(ROOT, '.env')
  if (!existsSync(p)) return {}
  const out = {}
  for (const line of readFileSync(p, 'utf8').split(/\r?\n/)) {
    if (!line || line.startsWith('#')) continue
    const i = line.indexOf('=')
    if (i < 0) continue
    out[line.slice(0, i).trim()] = line.slice(i + 1).trim()
  }
  return out
}

const env = loadEnv()
const USER = process.env.E2E_MODERATOR_USERNAME || env.MODERATOR_USERNAME || ''
const PASS = process.env.E2E_MODERATOR_PASSWORD || env.MODERATOR_PASSWORD || ''

async function syntheticBuffer(page, seed = 42) {
  const base64 = await page.evaluate(async (s) => {
    const canvas = document.createElement('canvas')
    canvas.width = 224
    canvas.height = 224
    const ctx = canvas.getContext('2d')
    const tile = 8
    for (let y = 0; y < 224; y += tile) {
      for (let x = 0; x < 224; x += tile) {
        const n = (x * 13 + y * 7 + s) % 255
        ctx.fillStyle = `rgb(${n}, ${(n * 3) % 255}, ${255 - n})`
        ctx.fillRect(x, y, tile, tile)
      }
    }
    const blob = await new Promise((r) => canvas.toBlob(r, 'image/jpeg', 0.92))
    const bytes = new Uint8Array(await blob.arrayBuffer())
    let binary = ''
    bytes.forEach((b) => (binary += String.fromCharCode(b)))
    return btoa(binary)
  }, seed)
  return Buffer.from(base64, 'base64')
}

async function settle(page, ms = 800) {
  await page.waitForLoadState('networkidle').catch(() => {})
  await page.evaluate(() => document.fonts.ready).catch(() => {})
  await page.waitForTimeout(ms)
}

const shots = []
async function shot(page, name, opts = {}) {
  await page.screenshot({ path: join(OUT, `${name}.png`), animations: 'disabled', caret: 'hide', ...opts })
  shots.push(name)
  console.log('shot', name)
}

const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] })
const context = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  deviceScaleFactor: 2,
  colorScheme: 'dark',
})
const page = await context.newPage()
page.on('pageerror', (e) => console.log('pageerror:', e.message))

// 1) Landing hero
await page.goto(`${APP}/`, { waitUntil: 'domcontentloaded' })
await settle(page, 1200)
await shot(page, 'landing-hero')

// 2) Analyze empty
await page.goto(`${APP}/analyze`, { waitUntil: 'domcontentloaded' })
await settle(page, 900)
await shot(page, 'analyze-empty')

// 3) Analyze with result (upload synthetic)
const buf = await syntheticBuffer(page, 77)
const input = page.locator('input[type="file"]').first()
if (await input.count()) {
  await input.setInputFiles({ name: 'case-sample.jpg', mimeType: 'image/jpeg', buffer: buf })
  await page.waitForTimeout(8000)
  await settle(page, 600)
  await shot(page, 'analyze-result')
} else {
  console.log('warn: no file input on analyze')
  await shot(page, 'analyze-result')
}

// 4) Report wizard
await page.goto(`${APP}/report`, { waitUntil: 'domcontentloaded' })
await settle(page, 900)
await shot(page, 'report')

// 5) Verify
await page.goto(`${APP}/verify`, { waitUntil: 'domcontentloaded' })
await settle(page, 900)
await shot(page, 'verify')

// 6) Login + queue (if creds)
if (USER && PASS) {
  await page.goto(`${APP}/login`, { waitUntil: 'domcontentloaded' })
  await settle(page, 500)
  const userField = page.getByLabel('Username').or(page.locator('input[name="username"], input#username')).first()
  const passField = page.getByLabel('Password', { exact: true }).or(page.locator('input[type="password"]')).first()
  await userField.fill(USER)
  await passField.fill(PASS)
  await page.getByRole('button', { name: /sign in/i }).click()
  await page.waitForURL(/queue|compliance|admin/, { timeout: 15000 }).catch(() => {})
  await settle(page, 1200)
  await shot(page, 'queue')

  // Compliance if reachable
  await page.goto(`${APP}/compliance`, { waitUntil: 'domcontentloaded' }).catch(() => {})
  await settle(page, 900)
  await shot(page, 'compliance')
} else {
  console.log('warn: no moderator creds — skipping queue/compliance')
}

writeFileSync(join(OUT, 'shots.json'), JSON.stringify({ shots, capturedAt: new Date().toISOString() }, null, 2))
await browser.close()
console.log('done', shots.length, 'shots →', OUT)
