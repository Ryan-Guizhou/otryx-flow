import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  testMatch: 'flow-*-real.spec.ts',
  workers: 1,
  fullyParallel: false,
  use: {
    baseURL: process.env.FLOW_WEB_URL ?? 'http://127.0.0.1:8088',
  },
})
