import { expect, test } from '@playwright/test'

const username = process.env.FLOW_ADMIN_USERNAME ?? ''
const password = process.env.FLOW_ADMIN_PASSWORD ?? ''

test.setTimeout(120_000)

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('flow.locale', 'en'))
})

test.beforeAll(() => {
  if (!username || !password) {
    throw new Error('FLOW_ADMIN_USERNAME and FLOW_ADMIN_PASSWORD are required for the real job-control test')
  }
})

test('admin can disable a Job, cancel a leased Run, and retry it from the execution center', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toBeVisible()

  await page.getByRole('button', { name: 'Projects' }).click()
  await page.getByRole('button', { name: '+ Create project' }).click()
  const projectName = `flow-control-${Date.now()}`
  await page.getByLabel('Project name').fill(projectName)
  await page.locator('.modal').getByRole('button', { name: 'Create project' }).click()
  await expect(page.getByText(projectName)).toBeVisible()

  await page.getByRole('button', { name: 'Jobs' }).click()
  await page.getByLabel('Select project').selectOption({ label: projectName })
  await page.getByRole('button', { name: 'New job' }).click()
  await page.locator('#job-name').fill('Cancellable delay')
  await page.locator('#job-handler').fill('safe.delay')
  await page.locator('#job-input').fill('{"marker":"browser-e2e"}')
  await page.locator('#job-timeout').fill('45')
  await page.locator('#job-attempts').fill('2')
  await page.getByRole('button', { name: 'Save draft' }).click()
  await expect(page.getByText('Cancellable delay')).toBeVisible()
  await page.getByRole('button', { name: 'Publish revision' }).click()
  await expect(page.getByText('r1')).toBeVisible()

  // Disabled Jobs must be unavailable to the manual execution entry point.
  await page.getByRole('button', { name: 'Disable' }).click()
  await expect(page.getByRole('button', { name: 'Enable' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Run now' })).toBeDisabled()
  await page.getByRole('button', { name: 'Enable' }).click()
  await expect(page.getByRole('button', { name: 'Disable' })).toBeVisible()

  await page.getByRole('button', { name: 'Run now' }).click()
  await expect(page.getByRole('heading', { name: 'Execution center' })).toBeVisible()
  await page.getByLabel('Project').first().selectOption({ label: projectName })

  const runsTable = page.locator('.page-body .projects-table').first()
  const manualRun = runsTable.locator('tbody tr').filter({ hasText: 'MANUAL' }).first()
  await expect(manualRun).toBeVisible({ timeout: 30_000 })
  await manualRun.getByRole('button', { name: 'Details' }).click()
  await expect(page.getByText(/Queue task .* is LEASED/)).toBeVisible({ timeout: 30_000 })

  await manualRun.getByRole('button', { name: 'Stop this run' }).click()
  await expect(manualRun).toContainText('CANCELLED', { timeout: 30_000 })
  await expect(manualRun.getByRole('button', { name: 'Run again' })).toBeEnabled()

  await manualRun.getByRole('button', { name: 'Run again' }).click()
  const retryRun = runsTable.locator('tbody tr').filter({ hasText: 'RETRY' }).first()
  await expect(retryRun).toBeVisible({ timeout: 30_000 })
  await expect(retryRun).toContainText('SUCCEEDED', { timeout: 60_000 })

  await page.getByTitle('Sign out').click()
  await page.locator('.modal').getByRole('button', { name: 'Sign out' }).click()
  await expect(page.getByRole('heading', { name: 'Sign in to OTRYX Flow' })).toBeVisible()
})
