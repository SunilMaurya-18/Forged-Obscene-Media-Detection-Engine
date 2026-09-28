import { expect, test } from '@playwright/test'

// Phase 25 DoD: a first-time visitor reaches a populated, meaningful demo in one click.

test('one click from the landing page opens a populated, verifiable demo story', async ({ page, request }) => {
  const status = await (await request.get('/api/demo')).json()
  test.skip(!status.enabled, 'The one-click demo is off (DEMO_ENABLED=false)')

  let story
  await test.step('the landing page CTA opens the tour, seeding the story if there is none yet', async () => {
    await page.goto('/')
    await page.getByRole('link', { name: 'See it live in one click' }).first().click()
    await page.waitForURL('**/tour')
    await expect(page.getByRole('heading', { name: 'An edited image was taken down' })).toBeVisible({ timeout: 60_000 })
    story = (await (await request.get('/api/demo')).json()).story
    expect(story.flagged.decision).toBe('AUTO_FLAG')
    expect(story.appeal.caseNumber).toBeTruthy()
    // Seeded at risk; on a long-running stack its clock may since have run out
    expect(['AT_RISK', 'BREACHED']).toContain(story.atRisk.slaStatus)
    expect(story.breached.slaStatus).toBe('BREACHED')
  })

  await test.step('all five chapters show live data', async () => {
    for (const title of [
      'An edited image was taken down',
      'The uploader appealed',
      'A signed evidence report was issued',
      "A victim's report is close to its deadline",
      'A government order missed its deadline',
    ]) {
      await expect(page.getByRole('heading', { name: title })).toBeVisible()
    }
    await expect(page.getByText(story.report.reportId).first()).toBeVisible()
    await expect(page.getByText(story.atRisk.caseNumber).first()).toBeVisible()
  })

  await test.step('the signed report verifies from the tour', async () => {
    await page.getByRole('button', { name: 'Check the signature' }).click()
    await expect(page.getByText('Signature valid: the report matches the record.')).toBeVisible()
  })

  await test.step("the appeal link opens the uploader's page with the PIN filled in, not in the URL", async () => {
    await page.getByRole('link', { name: "Open the uploader's appeal page" }).click()
    await page.waitForURL('**/appeal**')
    expect(page.url()).not.toContain(story.appeal.pin)
    await expect(page.locator('#ap-pin')).toHaveValue(story.appeal.pin)
  })
})
