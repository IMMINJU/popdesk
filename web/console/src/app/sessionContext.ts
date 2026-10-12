// Who is signed in. The console asks `GET /api/console/me` once when a signed-in
// screen opens; screens read the role from here (admin screens, C-3 empty list).
import { sessionContext, useSession as useSessionOf, type SessionValue as SessionValueOf } from '@popdesk/app/sessionContext';
import type { Schemas } from '../api';

export { safeNext, useToLogin, type LoginState } from '@popdesk/app/sessionContext';

export type Me = Schemas['Me'];

export type SessionValue = SessionValueOf<Me>;

export const SessionContext = sessionContext<Me>();

export function useSession(): SessionValue {
  return useSessionOf<Me>();
}
