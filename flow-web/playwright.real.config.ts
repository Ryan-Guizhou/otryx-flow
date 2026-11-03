import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  testMatch: 'flow-1.0-real.spec.ts',
  use: {
    baseURL: process.env.FLOW_WEB_URL ?? 'http://127.0.0.1:8088',
    channel: 'chrome',
  },
})
