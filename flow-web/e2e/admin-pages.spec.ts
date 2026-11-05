import { expect, test, type Page } from '@playwright/test'

const projectId = '00000000-0000-4000-8000-000000000001'

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('flow.locale', 'en'))
})

async function mockApi(page: Page) {
  const jobId = '00000000-0000-4000-8000-000000000010'
  let jobEnabled = true
  let jobDraftVersion = 1
  const scheduleId = '00000000-0000-4000-8000-000000000020'
  const schedule = {
    id: scheduleId, projectId, jobId, jobRevision: 1, scheduleRevision: 1,
    expression: '0 0 9 * * *', timeZone: 'Europe/Paris', misfirePolicy: 'SKIP',
    enabled: true, nextFireUtc: '2026-11-06T08:00:00Z', version: 3, archived: false,
    createdAt: '2026-11-03T00:00:00Z', updatedAt: '2026-11-03T00:00:00Z',
  }
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
    if (path === '/api/v1/overview') {
      await route.fulfill({ json: { projects: 1, jobs: 0, runs: 0, workersOnline: 0 } })
      return
    }
    if (path === '/api/v1/workers') {
      await route.fulfill({ json: [] })
      return
    }
    if (path === '/api/v1/handlers') {
      await route.fulfill({ json: [{ handlerKey: 'safe.echo', displayName: 'Echo', parameterSchema: '{}', supportsCancel: true, enabled: true }] })
      return
    }
    if (path.includes('/job-runs')) {
      await route.fulfill({ json: [] })
      return
    }
    if (path === '/api/v1/audit') {
      await route.fulfill({ json: [] })
      return
    }
    if (path.endsWith(`/jobs/${jobId}/enabled`) && method === 'POST') {
      const body = route.request().postDataJSON() as { enabled: boolean; expectedDraftVersion: number }
      expect(body.expectedDraftVersion).toBe(jobDraftVersion)
      jobEnabled = body.enabled
      jobDraftVersion += 1
      await route.fulfill({ json: {
        id: jobId, projectId, name: 'Echo job', handlerKey: 'safe.echo', parametersJson: '{}',
        timeoutSeconds: 30, maxAttempts: 2, draftVersion: jobDraftVersion, publishedRevision: 1,
        lastPublishedDraftVersion: 1, updatedAt: '2026-11-03T00:00:00Z', enabled: jobEnabled, archived: false,
      } })
      return
    }
    if (path === `/api/v1/projects/${projectId}/schedules` && method === 'GET') {
      await route.fulfill({ json: [schedule] })
      return
    }
    if (path === `/api/v1/projects/${projectId}/schedules/${scheduleId}/triggers` && method === 'GET') {
      await route.fulfill({ json: [] })
      return
    }
    if (path === `/api/v1/projects/${projectId}/schedules/${scheduleId}` && method === 'PUT') {
      const body = route.request().postDataJSON() as {
        jobId: string; jobRevision: number; expression: string; timeZone: string; misfirePolicy: string
      }
      Object.assign(schedule, body, { scheduleRevision: schedule.scheduleRevision + 1, version: schedule.version + 1 })
      await route.fulfill({ json: { code: 'OK', schedule, duplicate: false } })
      return
    }
    if (path.includes('/jobs') && method === 'GET') {
      await route.fulfill({ json: [{
        id: jobId, projectId, name: 'Echo job',
        handlerKey: 'safe.echo', parametersJson: '{}', timeoutSeconds: 30, maxAttempts: 2,
        draftVersion: jobDraftVersion, publishedRevision: 1, lastPublishedDraftVersion: 1,
        updatedAt: '2026-11-03T00:00:00Z', enabled: jobEnabled, archived: false,
      }] })
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
  await expect(page.getByRole('button', { name: 'Edit draft' })).toBeVisible()
  await page.getByRole('button', { name: 'Edit draft' }).click()
  await expect(page.getByRole('heading', { name: 'Edit job draft' })).toBeVisible()
  await page.getByRole('button', { name: 'Cancel' }).click()
  await page.getByRole('button', { name: 'New job' }).click()
  await expect(page.locator('#job-handler')).toHaveAttribute('pattern', 'safe\\.[a-z][a-z0-9_.-]{0,120}')
  await page.locator('.modal').getByRole('button', { name: 'Cancel' }).click()
  await page.getByTitle('Sign out').click()
  await expect(page.getByRole('heading', { name: 'Sign out?' })).toBeVisible()
})

test('language switch updates navigation and audit', async ({ page }) => {
  await mockApi(page)
  await signIn(page, 'admin')
  await page.getByRole('button', { name: '中文' }).click()
  await expect(page.getByRole('navigation', { name: '主导航' })).toBeVisible()
  await page.getByRole('button', { name: '审计' }).click()
  await expect(page.getByRole('heading', { name: '变更记录' })).toBeVisible()
  await page.getByRole('button', { name: '总览' }).click()
  await expect(page.getByRole('heading', { name: /欢迎回来/ })).toBeVisible()
  await page.getByRole('button', { name: 'English' }).click()
  await expect(page.getByRole('heading', { name: /Good to see you/ })).toBeVisible()
})

test('admin can disable and re-enable a published job with draft-version CAS', async ({ page }) => {
  await mockApi(page)
  await signIn(page, 'admin')
  await page.getByRole('button', { name: 'Jobs' }).click()
  await expect(page.getByRole('button', { name: 'Disable' })).toBeVisible()

  const disableRequest = page.waitForRequest(request =>
    request.method() === 'POST' && request.url().endsWith('/jobs/00000000-0000-4000-8000-000000000010/enabled'))
  await page.getByRole('button', { name: 'Disable' }).click()
  expect((await disableRequest).postDataJSON()).toEqual({ enabled: false, expectedDraftVersion: 1 })
  await expect(page.getByRole('button', { name: 'Enable' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Run now' })).toBeDisabled()

  const enableRequest = page.waitForRequest(request =>
    request.method() === 'POST' && request.url().endsWith('/jobs/00000000-0000-4000-8000-000000000010/enabled'))
  await page.getByRole('button', { name: 'Enable' }).click()
  expect((await enableRequest).postDataJSON()).toEqual({ enabled: true, expectedDraftVersion: 2 })
  await expect(page.getByRole('button', { name: 'Disable' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Run now' })).toBeEnabled()
})

test('cron schedule edit form restores persisted values and submits an update', async ({ page }) => {
  await mockApi(page)
  await signIn(page, 'admin')
  await page.getByRole('button', { name: 'Jobs' }).click()
  await expect(page.getByRole('button', { name: 'Edit schedule' })).toBeVisible()
  await page.getByRole('button', { name: 'Edit schedule' }).click()

  await expect(page.locator('#schedule-cron')).toHaveValue('0 0 9 * * *')
  await expect(page.locator('#schedule-zone')).toHaveValue('Europe/Paris')
  await expect(page.locator('#schedule-misfire')).toHaveValue('SKIP')

  await page.locator('#schedule-cron').fill('0 */10 * * * *')
  const update = page.waitForRequest(request =>
    request.method() === 'PUT' && request.url().endsWith('/schedules/00000000-0000-4000-8000-000000000020'))
  await page.getByRole('button', { name: 'Update schedule' }).click()
  expect((await update).postDataJSON()).toMatchObject({
    jobId: '00000000-0000-4000-8000-000000000010',
    jobRevision: 1,
    expression: '0 */10 * * * *',
    timeZone: 'Europe/Paris',
    misfirePolicy: 'SKIP',
  })
  await expect(page.getByRole('button', { name: 'Edit schedule' })).toBeVisible()
})
