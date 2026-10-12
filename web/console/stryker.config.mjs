// Mutation testing (design/process.md C-10). Run by hand, not in CI.
//
// STRYKER_SCOPE picks a part, because the whole app takes about 12 hours:
//   core     the src/app files that decide something (the safe `next`
//            address, which message to show, attachment checks, paging, late
//            answers) with every mutator
//   app      src/app/**, App.tsx, api.ts with every mutator
//   screens  src/screens/** without StringLiteral (the tests already pin the
//            screens.md wording)
//   (unset)  everything with every mutator
// sessionContext, result, paging, and navigate moved to @popdesk/app; the app files re-export them.
const CORE = ['messages.ts', 'attachments.ts'];
const SCOPES = {
  core: { mutate: CORE.map((f) => `src/app/${f}`), excluded: [] },
  app: { mutate: ['src/app/**/*.{ts,tsx}', 'src/App.tsx', 'src/api.ts'], excluded: [] },
  screens: { mutate: ['src/screens/**/*.{ts,tsx}'], excluded: ['StringLiteral'] },
};
const scope = SCOPES[process.env.STRYKER_SCOPE ?? ''];
const reportName = process.env.STRYKER_SCOPE ? `mutation-${process.env.STRYKER_SCOPE}` : 'mutation';

/** @type {import('@stryker-mutator/api/core').PartialStrykerOptions} */
export default {
  // pnpm keeps plugins out of core's reach, so name the runner.
  plugins: ['@stryker-mutator/vitest-runner'],
  testRunner: 'vitest',
  vitest: { configFile: 'vitest.config.ts' },
  coverageAnalysis: 'perTest',
  mutate: [
    ...(scope?.mutate ?? ['src/**/*.{ts,tsx}']),
    '!src/**/*.test.{ts,tsx}',
    '!src/**/__tests__/**',
    '!src/test-setup.ts',
    '!src/main.tsx',
  ],
  mutator: { excludedMutations: scope?.excluded ?? [] },
  // One runner: the dev PC has 16 GB and shares it with a WSL VM.
  concurrency: 1,
  reporters: ['clear-text', 'progress', 'html', 'json'],
  htmlReporter: { fileName: `reports/mutation/${reportName}.html` },
  jsonReporter: { fileName: `reports/mutation/${reportName}.json` },
  incrementalFile: `reports/mutation/${reportName}-incremental.json`,
  tempDirName: '.stryker-tmp',
};
