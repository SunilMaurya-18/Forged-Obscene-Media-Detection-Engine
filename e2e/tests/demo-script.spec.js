import { expect, test } from '@playwright/test'
import { FEED_URL, cardOf, signIn, syntheticSample } from './helpers.js'

// The README demo script, end to end through the UI:
// analyse -> REVIEW -> moderator approves -> SLA countdown -> provenance verify -> signed report VALID.
// Needs the demo .env (DECISION_FORGERY_REVIEW=0.05) so the synthetic sample routes to REVIEW.

test.beforeAll(async ({ request }) => {
  const { thresholds } = await (await request.get('/api/stats')).json()
  expect(
    thresholds.forgeryReview,
    'Run the stack with the demo .env (cp .env.example .env): DECISION_FORGERY_REVIEW=0.05 routes the synthetic sample to REVIEW',
  ).toBeLessThanOrEqual(0.05)
})

test('demo script: analyse, review, SLA, provenance, signed report', async ({ page }) => {
  let analysis
  let complaint

  await test.step('analyse an upload: scores, REVIEW routing and a provenance label', async () => {
    await page.goto('/analyze')
    const sample = await syntheticSample(page)
    await page.locator('input[type="file"]').first().setInputFiles(sample)
    const response = page.waitForResponse((r) => r.url().endsWith('/api/analyze') && r.request().method() === 'POST')
    await page.getByRole('button', { name: 'Run analysis' }).click()
    const res = await response
    expect(res.status()).toBe(200)
    analysis = await res.json()
    expect(analysis.decision).toBe('REVIEW')
    expect(analysis.provenanceId).toBeTruthy()
    await expect(page.getByText('Provenance label', { exact: true })).toBeVisible()
    await expect(page.getByText(analysis.provenanceId).first()).toBeVisible()
  })

  await test.step('moderator approves it from the queue (after the 10 s undo window)', async () => {
    await signIn(page)
    const approve = page.getByRole('button', { name: `Approve item ${analysis.id}` })
    await expect(approve).toBeEnabled()
    await approve.click()
    const decision = page.waitForResponse((r) => r.url().endsWith(`/api/queue/${analysis.id}/decision`))
    // The decision is held client-side for the undo window; "Save now" commits it immediately
    await page.getByRole('button', { name: /^Save now/ }).first().click()
    expect((await decision).status()).toBe(200)
    await expect(approve).toHaveCount(0)
  })

  await test.step('a filed complaint starts a live SLA countdown', async () => {
    await page.goto('/compliance')
    await page.getByRole('button', { name: 'File complaint' }).first().click()
    const dialog = page.getByRole('dialog', { name: 'File complaint' })
    await dialog.locator('input[type="file"]').setInputFiles(await syntheticSample(page))
    await dialog.getByPlaceholder(/NCII-2026/).fill(`E2E-${Date.now()}`)
    const filed = page.waitForResponse((r) => r.url().endsWith('/api/compliance/complaints') && r.request().method() === 'POST')
    await dialog.getByRole('button', { name: 'File complaint' }).click()
    const res = await filed
    expect(res.status()).toBe(200)
    complaint = await res.json()
    expect(complaint.jurisdiction).toBe('IN')

    const row = cardOf(page, `Take down item ${complaint.id}`)
    await expect(row).toContainText('until deadline')
    await expect(row).toContainText('India')
    const clock = row.locator('p.font-mono.text-3xl')
    const before = await clock.textContent()
    await expect.poll(() => clock.textContent(), { timeout: 5_000 }).not.toBe(before)
  })

  await test.step('the signed evidentiary report verifies as VALID', async () => {
    const row = cardOf(page, `Take down item ${complaint.id}`)
    const report = page.waitForResponse((r) => r.url().endsWith(`/api/queue/${complaint.id}/report`))
    await row.getByRole('button', { name: 'Download report' }).click()
    expect((await report).status()).toBe(200)
    const verify = row.getByRole('button', { name: 'Verify report integrity' })
    await expect(verify).toBeEnabled()
    await verify.click()
    const verdict = row.getByRole('status')
    await expect(verdict).toContainText('Valid')
    await expect(verdict).toContainText('Ed25519')
  })

  await test.step('the public audit trail shows every step', async () => {
    await page.goto(`/verify/${analysis.provenanceId}`)
    await expect(page.getByText('Audit trail', { exact: true })).toBeVisible()
    await expect(page.getByText('Analysed and labelled').first()).toBeVisible()
    await expect(page.getByText('Moderator decision').first()).toBeVisible()

    await page.goto(`/verify/${complaint.provenanceId}`)
    await expect(page.getByText('Takedown complaint').first()).toBeVisible()
    await expect(page.getByText('Evidentiary report issued').first()).toBeVisible()
  })
})

test('mock platform feed routes the manipulated sample to REVIEW', async ({ page }) => {
  await page.goto(FEED_URL)
  const response = page.waitForResponse((r) => r.url().endsWith('/api/analyze'))
  await page.locator('#post-manipulated').click()
  const analysis = await (await response).json()
  expect(analysis.decision).toBe('REVIEW')
  await expect(page.getByText(`analysis #${analysis.id}`).first()).toBeVisible()
})

test('request IDs are echoed through nginx and the backend', async ({ request }) => {
  const res = await request.get('/api/verify-report/keys', { headers: { 'X-Request-ID': 'e2e-trace-123' } })
  expect(res.status()).toBe(200)
  expect(res.headers()['x-request-id']).toBe('e2e-trace-123')
  const keys = await res.json()
  expect(keys.activeKeyId).toMatch(/^[0-9a-f]{16}$/)

  const minted = await request.get('/api/verify-report/public-key')
  expect(minted.headers()['x-request-id']).toMatch(/^[0-9a-f]{32}$/)
})
