import { expect, test } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('flow.locale', 'en'))
})

const username = process.env.FLOW_ADMIN_USERNAME ?? ''
const password = process.env.FLOW_ADMIN_PASSWORD ?? ''

test.beforeAll(() => {
  if (!username || !password) {
    throw new Error('FLOW_ADMIN_USERNAME and FLOW_ADMIN_PASSWORD are required for the real stack test')
  }
})

test('signed-in user can edit a job and the session ends on logout', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toBeVisible()

  await page.getByRole('button', { name: 'Projects' }).click()
  await page.getByRole('button', { name: '+ Create project' }).click()
  const projectName = `flow-e2e-${Date.now()}`
  await page.getByLabel('Project name').fill(projectName)
  await page.locator('.modal').getByRole('button', { name: 'Create project' }).click()
  await expect(page.getByText(projectName)).toBeVisible()

  await page.getByRole('button', { name: 'Jobs' }).click()
  await page.getByLabel('Select project').selectOption({ label: projectName })
  await page.getByRole('button', { name: 'New job' }).click()
  await page.locator('#job-name').fill('Summarize once')
  await page.locator('#job-handler').fill('safe.summarize')
  await page.locator('#job-input').fill('{"values":[1,2]}')
  await page.getByRole('button', { name: 'Save draft' }).click()
  await expect(page.getByText('Summarize once')).toBeVisible()

  await page.getByRole('button', { name: 'Edit draft' }).click()
  await page.locator('#job-name').fill('Summarize edited')
  await page.getByRole('button', { name: 'Save changes' }).click()
  await expect(page.getByText('Summarize edited')).toBeVisible()
  await page.getByRole('button', { name: 'Publish revision' }).click()
  await expect(page.getByText('r1')).toBeVisible()

  await page.getByRole('button', { name: 'Run now' }).click()
  await expect(page.getByRole('heading', { name: 'Execution center' })).toBeVisible()
  const notice = page.locator('.notice')
  await expect(notice.or(page.getByText('No job runs match these filters'))).toBeVisible()

  await page.getByTitle('Sign out').click()
  await page.locator('.modal').getByRole('button', { name: 'Sign out' }).click()
  await expect(page.getByRole('heading', { name: 'Sign in to OTRYX Flow' })).toBeVisible()
})
