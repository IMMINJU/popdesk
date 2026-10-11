// 1a design.md 2절: 사진 주소는 화면이 만든다. 사진이 없으면 주소도 없다(S1A-AC-60).
import { describe, expect, test } from 'vitest';
import { photoUrl } from './photos';

describe('photoUrl', () => {
  test('S1A-AC-60 photoUrl 사진 있음: 계정 번호가 경로에, 바꾼 시각이 v에 들어간다', () => {
    const url = new URL(photoUrl(11, '2026-10-09T01:02:03+09:00') ?? '', 'http://popdesk.test');

    expect(url.pathname).toBe('/api/portal/photos/11');
    expect(url.searchParams.get('v')).toBe('2026-10-09T01:02:03+09:00');
  });

  test('S1A-AC-60 photoUrl 사진 없음: 바꾼 시각이 null이면 주소가 없다', () => {
    expect(photoUrl(11, null)).toBeNull();
  });

  test('S1A-AC-60 photoUrl 계정 없음: 계정 번호가 null이면(포털의 지원팀) 주소가 없다', () => {
    expect(photoUrl(null, '2026-10-09T01:02:03Z')).toBeNull();
  });
});
