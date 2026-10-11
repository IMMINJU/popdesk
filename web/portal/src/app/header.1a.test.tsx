// 1a screens.md 2절 모든 화면의 머리: 사용자 이름 앞에 사진이나 머리글자, 누르면 내 정보로 간다(S1A-AC-60).
import { describe, expect, test } from 'vitest';
import { ME, atPath, found, heading1, hrefPath, renderAt, screen, text, within } from '../__tests__/portal';
import { PHOTO_AT, photoOf, photosIn, signInWith } from '../__tests__/portal1a';

const banner = () => found(() => screen.queryByRole('banner'), '화면 머리');
const me = async (name: string) => {
  const head = await banner();
  return found(() => within(head).queryByRole<HTMLAnchorElement>('link', { name: new RegExp(name) }), `머리의 "${name}" 링크`);
};

describe('포털 화면 머리 (1a)', () => {
  test('S1A-AC-60 화면 머리 사진 있음: 이름 앞에 내 사진, 이름을 누르면 P-6 내 정보로 간다', async () => {
    signInWith({ photoUpdatedAt: PHOTO_AT });
    const { user } = renderAt('/issues');

    const mine = await me(ME.name);
    expect(hrefPath(mine)).toBe('/me');
    expect(photosIn(await banner())).toEqual([photoOf(ME.id, PHOTO_AT)]);
    await user.click(mine);
    await atPath('/me');
    expect(text(await heading1())).toBe('내 정보');
  });

  test('S1A-AC-60 화면 머리 사진 없음: 사진을 청하지 않고 머리글자를 그린다', async () => {
    renderAt('/issues');

    const mine = await me(ME.name);
    expect(hrefPath(mine)).toBe('/me');
    expect(photosIn(await banner())).toEqual([]);
  });
});
