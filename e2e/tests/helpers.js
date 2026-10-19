export const FEED_URL = process.env.E2E_FEED_URL || 'http://localhost:3001'
export const MODERATOR = {
  username: process.env.E2E_MODERATOR_USERNAME || '',
  password: process.env.E2E_MODERATOR_PASSWORD || '',
}

/**
 * The synthetic high-frequency checker pattern the mock feed posts as its "manipulated sample"
 * (no faces, no real imagery). Drawn in the browser so no binary fixture is committed; `seed`
 * shifts the colours so every run gets a fresh perceptual hash.
 */
export async function syntheticSample(page, seed = Date.now() % 97) {
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
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.92))
    const bytes = new Uint8Array(await blob.arrayBuffer())
    let binary = ''
    bytes.forEach((b) => (binary += String.fromCharCode(b)))
    return btoa(binary)
  }, seed)
  return { name: `synthetic-sample-${seed}.jpg`, mimeType: 'image/jpeg', buffer: Buffer.from(base64, 'base64') }
}

export async function signIn(page) {
  await page.goto('/login')
  await page.getByLabel('Username').fill(MODERATOR.username)
  await page.getByLabel('Password', { exact: true }).fill(MODERATOR.password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.waitForURL('**/queue')
}

/** The card (queue item or compliance row) that holds the given button. */
export function cardOf(page, buttonName) {
  return page.getByRole('button', { name: buttonName }).locator('xpath=ancestor::*[contains(concat(" ", normalize-space(@class), " "), " card ")][1]')
}
