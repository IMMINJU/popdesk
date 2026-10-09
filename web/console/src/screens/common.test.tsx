// screens.md 2절 "모든 화면에 걸리는 상태"와 접근성 공통, 콘솔 화면 위에서.
import { act } from '@testing-library/react';
import { HttpResponse } from 'msw/http';
import { describe, expect, test, vi } from 'vitest';
import {
  alertWith,
  atPath,
  button,
  callsTo,
  dialog,
  draftKept,
  errors,
  eventually,
  expectFieldError,
  field,
  found,
  hrefPath,
  isBlocked,
  link,
  liveRegions,
  on,
  openDialog,
  pageText,
  renderAt,
  screen,
  sent,
  spokenText,
  state,
  statusWith,
  tabbables,
  text,
  useFakeClock,
  within,
} from '../__tests__/console';

type User = ReturnType<typeof renderAt>['user'];

async function signIn(user: User) {
  await user.type(await field(/^이메일/, 'input'), 'admin@popdesk.test');
  await user.type(await field('비밀번호', 'input'), 'correct horse battery');
  await user.click(await button('로그인'));
}

const editor = () =>
  found(
    () => screen.queryAllByRole<HTMLTextAreaElement>('textbox').find((el) => el.tagName === 'TEXTAREA'),
    '댓글 편집칸(textarea)',
  );
const sendPublic = () => button('고객에게 답변 보내기');

async function writeReply(user: User, body: string) {
  await user.type(await editor(), body);
  await user.click(await sendPublic());
}

async function openCreateCompany(user: User) {
  await user.click(await button('고객사 만들기'));
  const box = await dialog();
  const input = (label: RegExp) =>
    found(() => within(box).queryAllByLabelText<HTMLInputElement>(label, { selector: 'input' })[0], `칸 ${String(label)}`);
  await user.type(await input(/^이름/), '감마');
  await user.type(await input(/^코드/), 'GAMMA');
  await user.type(await input(/^회사명/), '감마 주식회사');
  await user.click(await found(() => within(box).queryByRole('button', { name: /^만들기/ }), '"만들기" 버튼'));
  return { box, input };
}

describe('2절 401', () => {
  test('C-4 2절 401 불러올 때: 로그인하지 않고 문의 주소를 열면 /login?next=로 가고 로그인 뒤 그 문의로 돌아온다', async () => {
    state.signedIn = false;
    const { user } = renderAt('/issues/ACME-7');

    await atPath('/login');
    expect(new URLSearchParams(window.location.search).get('next')).toBe('/issues/ACME-7');
    await signIn(user);

    await atPath('/issues/ACME-7');
    await found(() => screen.queryByRole('heading', { name: /로그인이 안 됩니다/ }), '문의 제목');
  });

  test('C-3 → C-4 2절 401 불러올 때: 세션이 끝났으면 로그인 화면에 "로그인이 끝났습니다. 다시 로그인하세요." 안내', async () => {
    const { user } = renderAt('/issues');
    await link(/로그인이 안 됩니다/);

    state.signedIn = false;
    await user.click(await link(/로그인이 안 됩니다/));

    await atPath('/login');
    await statusWith('로그인이 끝났습니다. 다시 로그인하세요.');
    await signIn(user);
    await atPath('/issues/ACME-7');
  });

  test('C-4 2절 401 저장할 때: 입력을 지우지 않고 "다른 탭에서 다시 로그인" 띠, 링크는 새 탭으로 로그인 화면', async () => {
    on('post', '/api/console/issues/:key/comments', () => errors.unauthenticated());
    const { user } = renderAt('/issues/ACME-7');
    await writeReply(user, '세션이 끝난 뒤 쓴 답변');

    const band = await alertWith('로그인이 끝났습니다. 다른 탭에서 다시 로그인한 뒤 다시 보내세요.');
    const loginLink = band.querySelector('a');
    expect(loginLink, '띠 안의 로그인 링크').not.toBeNull();
    expect(loginLink).toHaveAttribute('target', '_blank');
    expect(hrefPath(loginLink as Element)).toBe('/login');
    expect(window.location.pathname).toBe('/issues/ACME-7');
    expect(draftKept('세션이 끝난 뒤 쓴 답변')).toBe(true);
  });
});

describe('2절 401 돌아갈 주소', () => {
  test('C-1 2절 401 next: 같은 앱 경로면 로그인 뒤 그 경로로 간다', async () => {
    state.signedIn = false;
    const { user } = renderAt(`/login?next=${encodeURIComponent('/companies/ACME')}`);
    await signIn(user);

    await atPath('/companies/ACME');
  });

  for (const next of ['//evil.example/issues', '/\\evil.example/issues', 'https://evil.example/issues', 'javascript:alert(1)']) {
    test(`C-1 2절 401 next 부정: "${next}"는 버리고 문의 목록으로 간다`, async () => {
      state.signedIn = false;
      const origin = window.location.origin;
      const { user } = renderAt(`/login?next=${encodeURIComponent(next)}`);
      await signIn(user);

      await atPath('/issues');
      expect(window.location.origin).toBe(origin);
    });
  }
});

describe('2절 403·404', () => {
  test('C-4 2절 403 저장할 때: "이 작업을 할 권한이 없습니다." 띠, 쓰던 글은 그대로', async () => {
    on('post', '/api/console/issues/:key/comments', () => errors.forbidden());
    const { user } = renderAt('/issues/ACME-7');
    await writeReply(user, '권한이 빠진 뒤 쓴 답변');

    await alertWith('이 작업을 할 권한이 없습니다.');
    expect(draftKept('권한이 빠진 뒤 쓴 답변')).toBe(true);
  });

  test('C-4 2절 404: 화면 전체가 "찾을 수 없습니다" 빈 상태와 목록 링크, 댓글 쓰기가 없다', async () => {
    renderAt('/issues/ACME-999');

    await eventually(() => {
      expect(pageText()).toContain('찾을 수 없습니다');
    });
    const toList = screen.queryAllByRole('link').filter((a) => hrefPath(a) === '/issues' && !a.closest('nav'));
    expect(toList.length, '목록으로 가는 링크').toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: /고객에게 답변 보내기|내부 메모 남기기/ })).toBeNull();
  });
});

describe('2절 429', () => {
  test('C-4 2절 429: Retry-After 초를 세는 동안 보내기를 막고, 초 세기를 라이브 영역으로 매초 읽지 않으며, 다 세면 한 번 알린다', async () => {
    useFakeClock();
    on('post', '/api/console/issues/:key/comments', () => errors.rateLimited(30));
    const { user } = renderAt('/issues/ACME-7', { fakeTimers: true });
    await user.type(await editor(), '많이 보낸 답변');
    const send = await sendPublic();
    await user.click(send);

    await alertWith(/요청이 많습니다\. (30|29)초 뒤 다시 하세요\./);
    expect(isBlocked(send), '세는 동안 보내기 버튼').toBe(true);

    const spokenAtStart = liveRegions().map(spokenText);
    for (let i = 0; i < 3; i += 1) {
      act(() => {
        vi.advanceTimersByTime(1000);
      });
      expect(liveRegions().map(spokenText), `${String(i + 1)}초 뒤 라이브 영역`).toEqual(spokenAtStart);
    }
    expect(pageText(), '화면에는 초가 줄어든다').toMatch(/2[67]초/);

    act(() => {
      vi.advanceTimersByTime(30_000);
    });
    await eventually(() => {
      expect(isBlocked(send), '다 센 뒤 보내기 버튼').toBe(false);
    });
    expect(liveRegions().map(spokenText), '다시 할 수 있게 되면 한 번 알린다').not.toEqual(spokenAtStart);
    expect(callsTo('POST', '/api/console/issues/ACME-7/comments')).toHaveLength(1);
    expect(draftKept('많이 보낸 답변')).toBe(true);
  });
});

describe('2절 400', () => {
  test('C-8 2절 400 fields: 요약 띠 "2개 칸을 고쳐 주세요", 칸마다 오류, 포커스는 첫 틀린 칸', async () => {
    on('post', '/api/console/companies', () =>
      errors.invalid([
        { field: 'name', code: 'TOO_LONG' },
        { field: 'legalName', code: 'TOO_LONG' },
      ]),
    );
    const { user } = renderAt('/companies');
    const { input } = await openCreateCompany(user);

    await alertWith('2개 칸을 고쳐 주세요');
    const name = await input(/^이름/);
    await expectFieldError(name);
    await expectFieldError(await input(/^회사명/));
    expect(document.activeElement).toBe(name);
  });

  test('C-8 2절 400 fields: 화면에 없는 칸이 있으면 요약 띠에 서버 message를 함께 보인다', async () => {
    on('post', '/api/console/companies', () =>
      errors.invalid(
        [
          { field: 'name', code: 'BLANK' },
          { field: 'labels', code: 'UNKNOWN_VALUE' },
        ],
        '라벨 값이 틀렸습니다.',
      ),
    );
    const { user } = renderAt('/companies');
    const { input } = await openCreateCompany(user);

    await alertWith('라벨 값이 틀렸습니다.');
    await expectFieldError(await input(/^이름/));
  });

  test('C-8 2절 400 fields 없음: 모르는 code면 서버 message를 띠에 보이고 입력은 그대로(UX-5)', async () => {
    on('post', '/api/console/companies', () =>
      HttpResponse.json({ code: 'BRAND_NEW_RULE', message: '새 규칙에 걸렸습니다.' }, { status: 400 }),
    );
    const { user } = renderAt('/companies');
    const { input } = await openCreateCompany(user);

    await alertWith('새 규칙에 걸렸습니다.');
    expect(await input(/^이름/)).toHaveValue('감마');
  });
});

describe('2절 5xx·연결 끊김', () => {
  test('C-3 2절 5xx 불러올 때: "서버에 연결하지 못했습니다" 띠와 "다시 불러오기"', async () => {
    on('get', '/api/console/issues', () => errors.internal());
    const { user } = renderAt('/issues');

    await alertWith('서버에 연결하지 못했습니다. 잠시 뒤 다시 하세요.');
    const retry = await button('다시 불러오기');
    on('get', '/api/console/issues', () =>
      HttpResponse.json({ items: state.issues, page: 1, size: 20, totalElements: 1, totalPages: 1 }),
    );
    await user.click(retry);

    await link(/로그인이 안 됩니다/);
  });

  test('C-4 2절 연결 끊김 저장할 때: 띠를 보이고 쓰던 글을 남기며 버튼은 다시 눌린다', async () => {
    on('post', '/api/console/issues/:key/comments', () => HttpResponse.error());
    const { user } = renderAt('/issues/ACME-7');
    await writeReply(user, '연결이 끊긴 답변');

    await alertWith('서버에 연결하지 못했습니다. 잠시 뒤 다시 하세요.');
    expect(draftKept('연결이 끊긴 답변')).toBe(true);
    await eventually(() => {
      expect(isBlocked(screen.getByRole('button', { name: '고객에게 답변 보내기' }))).toBe(false);
    });
  });
});

describe('2절 저장 성공', () => {
  test('C-4 2절 저장 성공: 같은 화면이면 그 자리를 다시 불러오고 토스트(role="status")', async () => {
    const { user } = renderAt('/issues/ACME-7');
    await writeReply(user, '답변을 남겼습니다');

    await sent('POST', '/api/console/issues/ACME-7/comments');
    await found(
      () => screen.queryAllByRole('status').find((el) => text(el) !== '' && el.getAttribute('aria-live') !== 'off'),
      '토스트(role="status")',
    );
  });
});

describe('2절 떠나기', () => {
  test('C-4 2절 떠나기: 쓰던 답변이 있으면 beforeunload로 묻는다', async () => {
    const { user } = renderAt('/issues/ACME-7');
    const box = await editor();
    const before = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(before);
    expect(before.defaultPrevented, '쓴 글이 없을 때').toBe(false);

    await user.type(box, '쓰다 만 답변');
    const after = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(after);
    expect(after.defaultPrevented, '쓴 글이 있을 때').toBe(true);
  });

  test('C-4 2절 떠나기: 쓰던 답변이 있을 때 메뉴로 옮기면 확인 대화상자, Esc로 닫으면 그 자리에 글이 남는다', async () => {
    const { user } = renderAt('/issues/ACME-7');
    await user.type(await editor(), '쓰다 만 답변');
    await user.click(await found(() => screen.queryByRole('link', { name: '고객사' }), '메뉴 "고객사"'));

    await dialog();
    expect(window.location.pathname).toBe('/issues/ACME-7');
    await user.keyboard('{Escape}');

    await eventually(() => {
      expect(openDialog()).toBeNull();
    });
    expect(window.location.pathname).toBe('/issues/ACME-7');
    expect(await editor()).toHaveValue('쓰다 만 답변');
  });
});

describe('접근성 공통과 화면 머리', () => {
  test('C-3 → C-4 접근성: 화면이 바뀌면 포커스가 h1(tabindex=-1)로 가고 화면 제목이 document.title에 들어간다', async () => {
    const { user } = renderAt('/issues');
    await user.click(await link(/로그인이 안 됩니다/));

    await atPath('/issues/ACME-7');
    const h1 = await found(() => screen.queryByRole('heading', { level: 1, name: /로그인이 안 됩니다/ }), '문의 제목 h1');
    await eventually(() => {
      expect(document.activeElement).toBe(h1);
    });
    expect(h1).toHaveAttribute('tabindex', '-1');
    expect(document.title).toContain(text(h1));
  });

  test('C-3 접근성: 맨 앞 Tab 자리는 "본문으로 건너뛰기" 링크이고 본문(main)을 가리킨다', async () => {
    renderAt('/issues');
    await link(/로그인이 안 됩니다/);

    const first = tabbables()[0];
    expect(first ? text(first) : '').toBe('본문으로 건너뛰기');
    const target = (first?.getAttribute('href') ?? '').replace(/^#/, '');
    expect(target, '건너뛰기 대상 id').not.toBe('');
    const main = document.getElementById(target);
    expect(main, '건너뛰기 대상').not.toBeNull();
    expect(main?.closest('main') ?? main?.querySelector('main'), '대상은 main').not.toBeNull();
  });

  test('C-3 화면 머리: 표시 이름 "popdesk", 사용자 이름, 로그아웃하면 세션을 끝내고 로그인 화면으로', async () => {
    const { user } = renderAt('/issues');
    await link(/로그인이 안 됩니다/);

    await eventually(() => {
      expect(pageText()).toContain('popdesk');
      expect(pageText()).toContain('최관리');
    });
    await user.click(await button('로그아웃'));

    await sent('POST', '/api/public/sign-out');
    await atPath('/login');
  });

  test('C-3 화면 머리: 지금 화면의 메뉴는 aria-current="page"', async () => {
    renderAt('/issues');
    await link(/로그인이 안 됩니다/);

    const current = await found(() => screen.queryByRole('link', { name: '문의' }), '메뉴 "문의"');
    expect(current).toHaveAttribute('aria-current', 'page');
    expect(await found(() => screen.queryByRole('link', { name: '고객사' }), '메뉴 "고객사"')).not.toHaveAttribute(
      'aria-current',
      'page',
    );
  });
});
