import { expect, test, type Page } from '@playwright/test'

const projectId = '00000000-0000-4000-8000-000000000001'

async function mockApi(page: Page) {
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    if (path === '/api/v1/auth/csrf') {
      await route.fulfill({ json: { headerName: 'X-XSRF-TOKEN', token: 'csrf-test' } })
      return
    }
    if (path === '/api/v1/auth/me') {
      await route.fulfill({ status: 401, json: { code: 'UNAUTHENTICATED' } })
      return
    }
    if (path === '/api/v1/auth/login' && method === 'POST') {
      const body = route.request().postDataJSON() as { username: string }
      const role = body.username === 'demo' ? 'DEMO_GUEST' : 'ADMIN'
      await route.fulfill({ json: { username: body.username, role } })
      return
    }
    if (path === '/api/v1/projects' && method === 'GET') {
      await route.fulfill({ json: [{ id: projectId, name: 'Demo', createdAt: '2026-11-03T00:00:00Z' }] })
      return
    }
    if (path.endsWith('/admission') && method === 'GET') {
      await route.fulfill({ json: {
        ownerUsername: 'admin', projectId, maxInflight: 64, maxQueued: 256,
        concurrencyPolicy: 'ALLOW', retentionDays: 14,
      } })
      return
    }
    if (path.endsWith('/admission') && method === 'PUT') {
      const body = route.request().postDataJSON() as Record<string, unknown>
      await route.fulfill({ json: { ownerUsername: 'admin', projectId, ...body } })
      return
    }
    if (path === '/api/v1/workers/drains') {
      await route.fulfill({ json: [] })
      return
    }
    if (path === '/api/v1/audit') {
      await route.fulfill({ json: [] })
      return
    }
    if (path.includes('/jobs')) {
      await route.fulfill({ json: [] })
      return
    }
    await route.fulfill({ status: 404, json: { code: 'NOT_MOCKED' } })
  })
}

async function signIn(page: Page, username: string) {
  await page.goto('/')
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill('local-test-password')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toBeVisible()
  await expect(page.getByText('Demo').first()).toBeVisible()
}

test('login form is shown before a session exists', async ({ page }) => {
  await mockApi(page)
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Sign in to OTRYX Flow' })).toBeVisible()
})

test('admin can open workers, limits, and audit', async ({ page }) => {
  await mockApi(page)
  await signIn(page, 'admin')
  await page.getByRole('button', { name: 'Workers' }).click()
  await expect(page.getByRole('heading', { name: 'Worker drain' })).toBeVisible()
  await page.getByRole('button', { name: 'Limits' }).click()
  await expect(page.getByRole('button', { name: 'Save limits' })).toBeEnabled()
  const save = page.waitForRequest(request => request.method() === 'PUT' && request.url().includes('/admission'))
  await page.getByRole('button', { name: 'Save limits' }).click()
  expect((await save).postDataJSON()).toMatchObject({ concurrencyPolicy: 'ALLOW', maxInflight: 64 })
  await page.getByRole('button', { name: 'Audit' }).click()
  await expect(page.getByRole('heading', { name: 'Change history' })).toBeVisible()
})

test('demo guest can read limits but cannot save them', async ({ page }) => {
  await mockApi(page)
  await signIn(page, 'demo')
  await page.getByRole('button', { name: 'Limits' }).click()
  await expect(page.getByText('Changes require an administrator.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Save limits' })).toBeDisabled()
})

test('job handler field only accepts a safe prefix', async ({ page }) => {
  await mockApi(page)
  await signIn(page, 'admin')
  await page.getByRole('button', { name: 'Jobs' }).click()
  await page.getByRole('button', { name: 'New job' }).click()
  await expect(page.locator('#job-handler')).toHaveAttribute('pattern', 'safe\\.[a-z][a-z0-9_.-]{0,120}')
})
