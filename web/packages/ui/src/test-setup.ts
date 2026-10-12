import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterEach } from 'vitest';

// A first screen can take longer than the default second when runs overlap.
configure({ asyncUtilTimeout: 3000 });

afterEach(() => {
  cleanup();
});
