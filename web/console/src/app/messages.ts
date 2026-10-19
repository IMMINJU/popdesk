// Screen texts chosen by error `code` (UX-5, screens.md 2절). A `code` the
// screen does not know shows the server `message`. What reads the same in both
// apps is the shared file; this adds the console's own.
import { texts } from '@popdesk/app/locale';
import { TEXT_KEYS, withName } from '@popdesk/app/messages';

export { failureText, fieldSummaryTitle, fieldText, pageAnnouncement, rateLimitedText, slaLength, slaWords, type FieldLimits } from '@popdesk/app/messages';

export const TEXT = withName(
  texts({
    ...TEXT_KEYS,
    sidebar: 'console.sidebar',
    noPermissionHelp: 'console.noPermissionHelp',
  }),
);
