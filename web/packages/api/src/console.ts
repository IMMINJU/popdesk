import createClient from 'openapi-fetch';
import type { components, paths } from './generated/console';

// Only the console contract. The other app's types and client live in a separate
// entry point, so they never reach this app's bundle (ADR D-43).
export type ConsolePaths = paths;
export type ConsoleComponents = components;

// Sessions ride on a host-only HttpOnly cookie, so requests stay same-origin.
export function createConsoleClient(baseUrl = '') {
  return createClient<paths>({ baseUrl, credentials: 'same-origin' });
}

export type ConsoleClient = ReturnType<typeof createConsoleClient>;
