import { defineConfig, devices } from '@playwright/test';

// Runs against the real app on port 8080, which serves both apps and tells
// them apart by host (ADR D-43).
export default defineConfig({
  testDir: './tests',
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: process.env.CI ? [['list'], ['junit', { outputFile: 'test-results/junit.xml' }]] : 'list',
  use: {
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'portal',
      testDir: './tests/portal',
      use: { ...devices['Desktop Chrome'], baseURL: 'http://portal.localhost:8080' },
    },
    {
      name: 'console',
      testDir: './tests/console',
      use: { ...devices['Desktop Chrome'], baseURL: 'http://console.localhost:8080' },
    },
  ],
});
