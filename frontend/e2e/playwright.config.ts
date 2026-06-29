import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: '.',
  timeout: 30000,
  use: {
    baseURL: 'http://localhost:5177',
    headless: true
  },
  projects: [
    { name: 'chromium', use: { browserName: 'chromium' } }
  ]
})
