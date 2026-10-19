// Screen texts chosen by error `code` (UX-5, screens.md 2절). A `code` the
// screen does not know shows the server `message`. What reads the same in both
// apps is the shared file; this adds the portal's own.
import { texts } from '@popdesk/app/locale';
import { TEXT_KEYS, withName } from '@popdesk/app/messages';

export { failureText, fieldSummaryTitle, fieldText, pageAnnouncement, rateLimitedText, type FieldLimits } from '@popdesk/app/messages';

export const TEXT = withName(
  texts({
    ...TEXT_KEYS,
    noPermissionHelp: 'portal.noPermissionHelp',
    noMembership: 'portal.noMembership',
    supportTeam: 'portal.supportTeam',
    me: 'portal.me',
    private: 'portal.private',
  }),
);

/**
 * Who wrote or uploaded something, as the portal shows it (screens.md P-3·P-5):
 * "나" for me, "지원팀" for anyone on the support side (D-49), else the name.
 */
export function personLabel(person: { accountId: number | null; kind: 'CUSTOMER' | 'SUPPORT_TEAM'; name: string | null }, meId: number | undefined): string {
  if (person.kind === 'SUPPORT_TEAM') return TEXT.supportTeam;
  if (meId !== undefined && person.accountId === meId) return TEXT.me;
  return person.name ?? '';
}
