// Who is signed in. The portal asks `GET /api/portal/me` when a signed-in screen
// opens, and again when 우리 회사 사람 opens; screens read the name from here
// (화면 머리, "나" for own issues).
import { sessionContext, useSession as useSessionOf, type SessionValue as SessionValueOf } from '@popdesk/app/sessionContext';
import type { Schemas } from '../api';

export { safeNext, useToLogin, type LoginState } from '@popdesk/app/sessionContext';

export type Me = Schemas['Me'];

export type SessionValue = SessionValueOf<Me>;

export const SessionContext = sessionContext<Me>();

export function useSession(): SessionValue {
  return useSessionOf<Me>();
}
