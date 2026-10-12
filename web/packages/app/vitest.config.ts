import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    // Two waits of 3 s (test-setup.ts) must fit, so a failure says what was not found.
    testTimeout: 10_000,
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
