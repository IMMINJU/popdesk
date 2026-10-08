import createClient from 'openapi-fetch';
import type { components, paths } from './generated/portal';

// Only the portal contract. The other app's types and client live in a separate
// entry point, so they never reach this app's bundle (ADR D-43).
export type PortalPaths = paths;
export type PortalComponents = components;

// Sessions ride on a host-only HttpOnly cookie, so requests stay same-origin.
export function createPortalClient(baseUrl = '') {
  return createClient<paths>({ baseUrl, credentials: 'same-origin' });
}

export type PortalClient = ReturnType<typeof createPortalClient>;
