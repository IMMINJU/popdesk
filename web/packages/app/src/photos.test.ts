// 1a design.md 2절: 사진 주소는 화면이 만든다. 바꾼 시각이 `v`에 들어가 새 사진은 새 주소다. 사진이 없으면 주소도 없다(S1A-AC-60).
// 본문을 읽지 않고 시그니처, 주석, 앱 테스트(photos.behavior)가 고정한 동작으로 썼다.
import { describe, expect, it } from 'vitest';
import { photoAddress } from './photos';

const CONSOLE = '/api/console/photos';
const PORTAL = '/api/portal/photos';

describe('photoAddress', () => {
  it.each([
    [CONSOLE, 11, '2026-10-09T01:02:03+09:00', '/api/console/photos/11'],
    [CONSOLE, 1, '2026-10-09T01:02:03Z', '/api/console/photos/1'],
    [PORTAL, 11, '2026-10-09T01:02:03.123456Z', '/api/portal/photos/11'],
    [PORTAL, 987654321, '2026-01-01T00:00:00Z', '/api/portal/photos/987654321'],
  ])('S1A-AC-60 사진 있음: %s의 계정 %i, %s → 경로 %s, v는 바꾼 시각 그대로', (base, accountId, updatedAt, path) => {
    const address = photoAddress(base, accountId, updatedAt);
    const url = new URL(address ?? '', 'http://popdesk.test');

    expect(address?.startsWith(`${path}?`)).toBe(true);
    expect(url.pathname).toBe(path);
    expect(Array.from(url.searchParams.keys())).toEqual(['v']);
    expect(url.searchParams.get('v')).toBe(updatedAt);
  });

  it('S1A-AC-60 사진을 바꾸면 주소가 달라진다', () => {
    const before = photoAddress(CONSOLE, 11, '2026-10-09T01:02:03Z');
    const after = photoAddress(CONSOLE, 11, '2026-10-09T01:02:04Z');

    expect(after).not.toBe(before);
    expect(photoAddress(CONSOLE, 11, '2026-10-09T01:02:03Z')).toBe(before);
    expect(photoAddress(CONSOLE, 12, '2026-10-09T01:02:03Z')).not.toBe(before);
  });

  it.each<[string, number | null, string | null]>([
    ['바꾼 시각이 없음', 11, null],
    ['계정이 없음(포털의 지원팀)', null, '2026-10-09T01:02:03Z'],
    ['둘 다 없음', null, null],
  ])('S1A-AC-60 사진 없음: %s이면 주소가 없다', (_, accountId, updatedAt) => {
    expect(photoAddress(CONSOLE, accountId, updatedAt)).toBeNull();
    expect(photoAddress(PORTAL, accountId, updatedAt)).toBeNull();
  });
});
