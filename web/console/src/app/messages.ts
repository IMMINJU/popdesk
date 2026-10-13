// Screen texts chosen by error `code` (UX-5, screens.md 2절). A `code` the
// screen does not know shows the server `message`. What reads the same in both
// apps is the shared file; this adds the console's own.
import { TEXT as SHARED } from '@popdesk/app/messages';

export { failureText, fieldSummaryTitle, fieldText, pageAnnouncement, rateLimitedText, type FieldLimits } from '@popdesk/app/messages';

export const TEXT = {
  ...SHARED,
  sidebar: '옆 메뉴 펼치기·접기',
  noPermissionHelp: '이 화면은 관리자만 씁니다.',
} as const;
