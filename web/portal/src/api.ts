import { createPortalClient, type PortalComponents } from '@popdesk/api/portal';
import { csrfMiddleware } from '@popdesk/app/csrf';

export { csrfToken } from '@popdesk/app/csrf';

export const api = createPortalClient();

export type Schemas = PortalComponents['schemas'];

api.use(csrfMiddleware);
