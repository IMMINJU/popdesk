import { createConsoleClient, type ConsoleComponents } from '@popdesk/api/console';
import { csrfMiddleware } from '@popdesk/app/csrf';

export { csrfToken } from '@popdesk/app/csrf';

export const api = createConsoleClient();

export type Schemas = ConsoleComponents['schemas'];

api.use(csrfMiddleware);
