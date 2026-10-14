import { fileURLToPath } from 'node:url';
import { storybookTest } from '@storybook/addon-vitest/vitest-plugin';
import { playwright } from '@vitest/browser-playwright';
import { defineConfig } from 'vitest/config';

// Renders every story in headless Chromium and runs its `play` (S6A-AC-90).
// A story fails if it throws while rendering or its `play` fails.
// The browser is the one `web/e2e` already downloads with Playwright.
export default defineConfig({
  plugins: [
    storybookTest({ configDir: fileURLToPath(new URL('./src/storybook', import.meta.url)) }),
  ],
  test: {
    name: 'storybook',
    browser: {
      enabled: true,
      headless: true,
      provider: playwright(),
      instances: [{ browser: 'chromium' }],
    },
  },
});
