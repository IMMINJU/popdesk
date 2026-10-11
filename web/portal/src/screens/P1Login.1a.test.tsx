// 1a screens.md 2절 P-1 로그인: "비밀번호를 잊었나요?" 링크를 더한다. 재설정 뒤의 토스트는 P-10 테스트가 본다.
import { describe, expect, test } from 'vitest';
import { atPath, heading1, hrefPath, link, renderAt, state, text } from '../__tests__/portal';

describe('P-1 로그인 (1a)', () => {
  test('P-1 "비밀번호를 잊었나요?": 로그인 화면의 링크가 P-9 비밀번호 찾기로 간다', async () => {
    state.signedIn = false;
    const { user } = renderAt('/login');

    const forgot = await link('비밀번호를 잊었나요?');
    expect(hrefPath(forgot)).toBe('/forgot-password');
    await user.click(forgot);
    await atPath('/forgot-password');
    expect(text(await heading1())).toContain('비밀번호 찾기');
  });
});
