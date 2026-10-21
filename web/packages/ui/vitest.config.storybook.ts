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
      // A Korean browser, as on the dev machine: a screen drawn before sign in takes the browser's
      // language, and the CI runner's is English.
      provider: playwright({ contextOptions: { locale: 'ko-KR' } }),
      instances: [{ browser: 'chromium' }],
    },
  },
});
