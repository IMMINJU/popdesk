// Profile photos (S1A-AC-60, design.md 2절). The screen makes the address; the
// time the photo last changed goes in `v`, so a new photo is a new address.

/** Address of an account's photo, or `null` without one: then nothing is requested and the initials show. */
export function photoUrl(accountId: number | null, photoUpdatedAt: string | null): string | null {
  if (accountId === null || photoUpdatedAt === null) return null;
  return `/api/portal/photos/${String(accountId)}?v=${encodeURIComponent(photoUpdatedAt)}`;
}
