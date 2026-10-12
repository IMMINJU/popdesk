// The shared unread count, asked from this app's inbox. Apart from `useNavCountRefresh` (소속 요청).
import { useUnreadCount as useUnreadCountWith, type Unread } from '@popdesk/app/unread';
import { api } from '../api';
import { call } from './result';

export { UNREAD_PERIOD_MS, UnreadContext, useUnread, type Unread } from '@popdesk/app/unread';

const askCount = () => call(api.GET('/api/console/notifications/unread-count'));

export function useUnreadCount(on: boolean): Unread {
  return useUnreadCountWith(on, askCount);
}
