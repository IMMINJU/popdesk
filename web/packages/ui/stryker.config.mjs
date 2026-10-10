// Mutation testing (design/process.md C-10). Run by hand, not in CI.
/** @type {import('@stryker-mutator/api/core').PartialStrykerOptions} */
export default {
  // pnpm keeps plugins out of core's reach, so name the runner.
  plugins: ['@stryker-mutator/vitest-runner'],
  testRunner: 'vitest',
  coverageAnalysis: 'perTest',
  vitest: { configFile: 'vitest.config.ts' },
  mutate: [
    'src/**/*.{ts,tsx}',
    '!src/**/*.test.{ts,tsx}',
    '!src/**/*.stories.{ts,tsx}',
    '!src/**/__tests__/**',
    '!src/test-setup.ts',
    '!src/storybook/**',
  ],
  // One runner: the dev PC has 16 GB and shares it with a WSL VM.
  concurrency: 1,
  reporters: ['clear-text', 'progress', 'html', 'json'],
  htmlReporter: { fileName: 'reports/mutation/mutation.html' },
  jsonReporter: { fileName: 'reports/mutation/mutation.json' },
  incrementalFile: 'reports/mutation/stryker-incremental.json',
  tempDirName: '.stryker-tmp',
};
