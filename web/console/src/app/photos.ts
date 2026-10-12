import { photoAddress } from '@popdesk/app/photos';

/** Address of an account's photo, or `null` without one: then nothing is requested and the initials show. */
export function photoUrl(accountId: number | null, photoUpdatedAt: string | null): string | null {
  return photoAddress('/api/console/photos', accountId, photoUpdatedAt);
}
