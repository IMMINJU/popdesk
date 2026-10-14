import type { StorybookConfig } from '@storybook/react-vite';

// Lives under src/ so the package tsconfig and ESLint cover it without changes.
// Run with `-c src/storybook` (see package.json).
const config: StorybookConfig = {
  framework: '@storybook/react-vite',
  // The console keeps the states of a screen's parts next to the screen.
  stories: ['../components/*.stories.tsx', '../../../../console/src/screens/*.stories.tsx'],
  addons: ['@storybook/addon-vitest'],
  core: { disableTelemetry: true },
};

export default config;
