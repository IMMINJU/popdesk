// The fake server of the shared package's tests, and the way a test asks it the way an app's
// client would: the body comes back as `data` or `error` and goes through `call`.
import { act } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, vi } from 'vitest';
import { call, type Result } from '../result';

export const server = setupServer();
server.listen({ onUnhandledFrame: 'error' });

afterEach(() => {
  server.resetHandlers();
  vi.useRealTimers();
});
afterAll(() => {
  server.close();
});

/** A browser resolves a path against the page; Node's fetch does not. */
export const at = (path: string) => new URL(path, window.location.href).href;

/** Asks `path` and gives the answer as a Result. */
export function ask<T>(path: string): Promise<Result<T>> {
  return call(
    fetch(at(path)).then(async (response) => {
      const body: unknown = response.status === 204 ? undefined : await response.json();
      return response.ok ? { data: body as T, response } : { error: body, response };
    }),
  );
}

/** An answer the test lets go when it wants. */
export function gate() {
  let open!: () => void;
  const opened = new Promise<void>((resolve) => {
    open = resolve;
  });
  return { open, opened };
}

/** The error body of conventions.md. */
export const errorBody = (status: number, code: string) => HttpResponse.json({ code, message: code }, { status });

/** Lets an answer already released reach the code under test: two more full round trips. */
export async function settle() {
  server.use(http.get('*/api/test/ping', () => new HttpResponse(null, { status: 204 })));
  await act(async () => {
    for (let i = 0; i < 2; i += 1) {
      await fetch(at('/api/test/ping'));
    }
  });
}

/** Fake timers that still move with real time, so Testing Library's waits keep working. */
export function useFakeClock() {
  vi.useFakeTimers({ shouldAdvanceTime: true, now: new Date('2026-10-13T03:20:00Z') });
}

/** Moves the fake clock half a minute on. */
export function halfMinute() {
  act(() => {
    vi.advanceTimersByTime(30_000);
  });
}
