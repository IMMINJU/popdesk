import type { StorybookConfig } from '@storybook/react-vite';

// Lives under src/ so the package tsconfig and ESLint cover it without changes.
// Run with `-c src/storybook` (see package.json).
const config: StorybookConfig = {
  framework: '@storybook/react-vite',
  stories: ['../components/*.stories.tsx'],
  core: { disableTelemetry: true },
};

export default config;
