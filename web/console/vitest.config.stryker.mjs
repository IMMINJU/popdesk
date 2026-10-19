// The vitest settings Stryker uses (stryker.config.mjs). Stryker runs the tests from a copy two
// folders deeper (.stryker-tmp/sandbox-*/), where the test helpers' relative imports of another
// package (`../../../packages/api/src/testing/contract`) point at nothing and every test file
// that reaches one loads no tests. Send them to the real packages folder, found through the
// linked @popdesk/api.
import { realpathSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { mergeConfig } from 'vitest/config';
import base from './vitest.config.ts';

const packages = dirname(realpathSync(join(import.meta.dirname, 'node_modules/@popdesk/api'))).replaceAll('\\', '/');

export default mergeConfig(base, {
  resolve: { alias: [{ find: /^(?:\.\.\/){3}packages\//, replacement: `${packages}/` }] },
});
