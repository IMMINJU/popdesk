// One flat config for every app and package. ESLint finds it by walking up
// from the package directory, so `eslint .` in a package uses this file.
import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  {
    ignores: ['**/dist/**', '**/node_modules/**', '**/coverage/**', 'packages/api/src/generated/**', '**/playwright-report/**', '**/test-results/**'],
  },
  js.configs.recommended,
  ...tseslint.configs.strictTypeChecked,
  {
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
  },
  {
    files: ['portal/**/*.{ts,tsx}', 'console/**/*.{ts,tsx}', 'packages/ui/**/*.{ts,tsx}', 'packages/app/**/*.{ts,tsx}'],
    extends: [reactHooks.configs.flat.recommended, reactRefresh.configs.vite],
    languageOptions: { globals: globals.browser },
  },
  // Each app imports only its own client (ADR D-43), and nothing from the other app.
  {
    files: ['portal/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': ['error', { patterns: ['@popdesk/api/console', '@popdesk/api/console/*', '**/console/src/**', '@popdesk/console', '@popdesk/console/*'] }],
    },
  },
  {
    files: ['console/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': ['error', { patterns: ['@popdesk/api/portal', '@popdesk/api/portal/*', '**/portal/src/**', '@popdesk/portal', '@popdesk/portal/*'] }],
    },
  },
  // Code both apps share takes what differs between them as arguments: no client, no app code.
  {
    files: ['packages/app/**/*.{ts,tsx}', 'packages/ui/**/*.{ts,tsx}', 'packages/i18n/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': ['error', { patterns: ['@popdesk/api', '@popdesk/api/*', '**/console/src/**', '**/portal/src/**'] }],
    },
  },
  // Production code does not write to the browser console. Tests and stories may.
  {
    files: ['portal/src/**/*.{ts,tsx}', 'console/src/**/*.{ts,tsx}', 'packages/*/src/**/*.{ts,tsx}'],
    ignores: ['**/*.test.{ts,tsx}', '**/__tests__/**', '**/test-setup.ts', '**/*.stories.tsx'],
    rules: {
      'no-console': 'error',
    },
  },
  {
    files: ['**/*.{js,mjs}'],
    extends: [tseslint.configs.disableTypeChecked],
    languageOptions: { globals: globals.node },
  },
);
