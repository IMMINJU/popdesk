// screens.md 2절 "모든 화면에 걸리는 상태"와 접근성 공통, 포털 화면 위에서.
import { act } from '@testing-library/react';
import { HttpResponse } from 'msw/http';
import { describe, expect, test, vi } from 'vitest';
import {
  action,
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
  gate,
  heading1,
  hrefPath,
  isBlocked,
  issue,
  link,
  liveRegions,
  on,
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
} from '../__tests__/portal';

type User = ReturnType<typeof renderAt>['user'];

async function signIn(user: User) {
  await user.type(await field(/^이메일/, 'input'), 'kim@acme.test');
  await user.type(await field('비밀번호', 'input'), 'correct horse battery');
  await user.click(await button('로그인'));
}

async function fillIssue(user: User, title = '프린터가 안 됩니다') {
  await user.type(await field(/^제목/, 'input'), title);
  await user.type(await field(/^내용/, 'textarea'), '사무실 프린터가 응답하지 않습니다.');
}

describe('2절 401', () => {
  test('P-3 → P-4 2절 401 불러올 때: 로그인 화면으로 가고 "로그인이 끝났습니다" 안내, 로그인하면 원래 주소로 돌아온다', async () => {
    const { user } = renderAt('/issues');
    await link(/ACME-7/);

    state.signedIn = false;
    await user.click(await action('문의 올리기'));

    await atPath('/login');
    await statusWith('로그인이 끝났습니다. 다시 로그인하세요.');
    await signIn(user);

    await atPath('/issues/new');
    await field(/^제목/, 'input');
  });

  test('P-5 2절 401 불러올 때: 로그인하지 않고 문의 주소를 열면 /login?next=로 가고 로그인 뒤 그 문의로 돌아온다', async () => {
    state.signedIn = false;
    const { user } = renderAt('/issues/ACME-7');

    await atPath('/login');
    expect(new URLSearchParams(window.location.search).get('next')).toBe('/issues/ACME-7');
    await signIn(user);

    await atPath('/issues/ACME-7');
    await found(() => screen.queryByRole('heading', { name: /로그인이 안 됩니다/ }), '문의 제목');
  });

  test('P-4 2절 401 저장할 때: 입력을 지우지 않고 "다른 탭에서 다시 로그인" 띠, 링크는 새 탭으로 로그인 화면', async () => {
    on('post', '/api/portal/issues', () => errors.unauthenticated());
    const { user } = renderAt('/issues/new');
    await fillIssue(user);
    await user.click(await button('올리기'));

    const band = await alertWith('로그인이 끝났습니다. 다른 탭에서 다시 로그인한 뒤 다시 보내세요.');
    const loginLink = band.querySelector('a');
    expect(loginLink, '띠 안의 로그인 링크').not.toBeNull();
    expect(loginLink).toHaveAttribute('target', '_blank');
    expect(hrefPath(loginLink as Element)).toBe('/login');
    expect(window.location.pathname).toBe('/issues/new');
    expect(await field(/^제목/, 'input')).toHaveValue('프린터가 안 됩니다');
  });
});

describe('2절 401 돌아갈 주소', () => {
  test('P-1 2절 401 next: 같은 앱 경로면 로그인 뒤 그 경로로 간다', async () => {
    state.signedIn = false;
    const { user } = renderAt(`/login?next=${encodeURIComponent('/issues/ACME-7')}`);
    await signIn(user);

    await atPath('/issues/ACME-7');
  });

  for (const next of ['//evil.example/issues', '/\\evil.example/issues', 'https://evil.example/issues', 'javascript:alert(1)']) {
    test(`P-1 2절 401 next 부정: "${next}"는 버리고 목록으로 간다`, async () => {
      state.signedIn = false;
      const origin = window.location.origin;
      const { user } = renderAt(`/login?next=${encodeURIComponent(next)}`);
      await signIn(user);

      await atPath('/issues');
      expect(window.location.origin).toBe(origin);
    });
  }
});

describe('2절 처음 불러옴', () => {
  const skeletons = (main: Element) => main.querySelectorAll('[aria-hidden="true"]').length;

  test('P-5 2절 처음 불러옴: 300ms 안에 오면 뼈대를 보이지 않고, 불러오는 동안 main은 aria-busy', async () => {
    useFakeClock();
    const release = gate();
    on('get', '/api/portal/issues/:key', async () => {
      await release.opened;
      return HttpResponse.json(state.issue);
    });
    renderAt('/issues/ACME-7', { fakeTimers: true });

    const main = await found(() => screen.queryByRole('main'), '본문 main');
    await eventually(() => {
      expect(main).toHaveAttribute('aria-busy', 'true');
    });
    act(() => {
      vi.advanceTimersByTime(250);
    });
    expect(skeletons(main), '300ms 전의 뼈대').toBe(0);
    release.open();

    await found(() => screen.queryByRole('heading', { name: /로그인이 안 됩니다/ }), '문의 제목');
    await eventually(() => {
      expect(main).not.toHaveAttribute('aria-busy', 'true');
    });
  });

  test('P-5 2절 처음 불러옴: 300ms가 넘으면 aria-hidden 뼈대가 보이고, 다 오면 aria-busy를 푼다', async () => {
    useFakeClock();
    const release = gate();
    on('get', '/api/portal/issues/:key', async () => {
      await release.opened;
      return HttpResponse.json(state.issue);
    });
    renderAt('/issues/ACME-7', { fakeTimers: true });

    const main = await found(() => screen.queryByRole('main'), '본문 main');
    await eventually(() => {
      expect(main).toHaveAttribute('aria-busy', 'true');
    });
    act(() => {
      vi.advanceTimersByTime(350);
    });
    await eventually(() => {
      expect(skeletons(main), '300ms 뒤의 뼈대(aria-hidden)').toBeGreaterThan(0);
    });
    expect(main).toHaveAttribute('aria-busy', 'true');
    release.open();

    await found(() => screen.queryByRole('heading', { name: /로그인이 안 됩니다/ }), '문의 제목');
    await eventually(() => {
      expect(main).not.toHaveAttribute('aria-busy', 'true');
    });
  });
});

describe('2절 403·404', () => {
  test('P-5 2절 403: "이 작업을 할 권한이 없습니다." 띠, 쓰던 글은 그대로', async () => {
    on('post', '/api/portal/issues/:key/comments', () => errors.forbidden());
    const { user } = renderAt('/issues/ACME-7');
    await user.type(await field(/댓글/, 'textarea'), '추가로 확인했습니다');
    await user.click(await button('보내기'));

    await alertWith('이 작업을 할 권한이 없습니다.');
    expect(draftKept('추가로 확인했습니다')).toBe(true);
  });

  test('P-5 2절 404: 화면 전체가 "찾을 수 없습니다" 빈 상태와 목록 링크', async () => {
    renderAt('/issues/ACME-999');

    await eventually(() => {
      expect(pageText()).toContain('찾을 수 없습니다');
    });
    const toList = screen.queryAllByRole('link').filter((a) => hrefPath(a) === '/issues');
    expect(toList.length, '목록으로 가는 링크').toBeGreaterThan(0);
    expect(screen.queryByRole('form', { name: '댓글 쓰기' })).toBeNull();
  });
});

describe('2절 429', () => {
  test('P-4 2절 429: Retry-After 초를 세는 동안 보내기를 막고, 초 세기를 라이브 영역으로 매초 읽지 않는다', async () => {
    useFakeClock();
    on('post', '/api/portal/issues', () => errors.rateLimited(30));
    const { user } = renderAt('/issues/new', { fakeTimers: true });
    await fillIssue(user);
    const submit = await button('올리기');
    await user.click(submit);

    const band = await alertWith(/요청이 많습니다\. (30|29)초 뒤 다시 하세요\./);
    expect(text(band)).toMatch(/요청이 많습니다/);
    expect(isBlocked(submit), '세는 동안 보내기 버튼').toBe(true);

    const spokenAtStart = liveRegions().map(spokenText);
    for (let i = 0; i < 3; i += 1) {
      act(() => {
        vi.advanceTimersByTime(1000);
      });
      expect(liveRegions().map(spokenText), `${String(i + 1)}초 뒤 라이브 영역`).toEqual(spokenAtStart);
    }
    expect(pageText(), '화면에는 초가 줄어든다').toMatch(/2[67]초/);
    expect(isBlocked(submit)).toBe(true);

    act(() => {
      vi.advanceTimersByTime(30_000);
    });
    await eventually(() => {
      expect(isBlocked(submit), '다 센 뒤 보내기 버튼').toBe(false);
    });
    expect(pageText()).not.toMatch(/\d+초 뒤 다시 하세요/);
    expect(callsTo('POST', '/api/portal/issues')).toHaveLength(1);
  });
});

describe('2절 400', () => {
  test('P-4 2절 400 fields: 칸마다 오류 문구, 요약 띠 "2개 칸을 고쳐 주세요", 포커스는 첫 틀린 칸', async () => {
    on('post', '/api/portal/issues', () =>
      errors.invalid([
        { field: 'title', code: 'TOO_LONG' },
        { field: 'content', code: 'TOO_LONG' },
      ]),
    );
    const { user } = renderAt('/issues/new');
    await fillIssue(user);
    await user.click(await button('올리기'));

    await alertWith('2개 칸을 고쳐 주세요');
    const title = await field(/^제목/, 'input');
    const content = await field<HTMLTextAreaElement>(/^내용/, 'textarea');
    await expectFieldError(title);
    await expectFieldError(content);
    expect(document.activeElement).toBe(title);
    expect(title).toHaveValue('프린터가 안 됩니다');
  });

  test('P-4 2절 400 fields: 화면에 없는 칸이 있으면 요약 띠에 서버 message를 함께 보인다', async () => {
    on('post', '/api/portal/issues', () =>
      errors.invalid(
        [
          { field: 'title', code: 'BLANK' },
          { field: 'labels', code: 'UNKNOWN_VALUE' },
        ],
        '라벨 값이 틀렸습니다.',
      ),
    );
    const { user } = renderAt('/issues/new');
    await fillIssue(user, '   제목   ');
    await user.click(await button('올리기'));

    await alertWith('라벨 값이 틀렸습니다.');
    await expectFieldError(await field(/^제목/, 'input'));
  });

  test('P-4 2절 400 fields 없음: 모르는 code면 서버 message를 띠에 보인다(UX-5)', async () => {
    on('post', '/api/portal/issues', () => HttpResponse.json({ code: 'BRAND_NEW_RULE', message: '새 규칙에 걸렸습니다.' }, { status: 400 }));
    const { user } = renderAt('/issues/new');
    await fillIssue(user);
    await user.click(await button('올리기'));

    await alertWith('새 규칙에 걸렸습니다.');
    expect(await field(/^제목/, 'input')).toHaveValue('프린터가 안 됩니다');
  });
});

describe('2절 5xx·연결 끊김', () => {
  test('P-3 2절 5xx 불러올 때: "서버에 연결하지 못했습니다" 띠와 "다시 불러오기"', async () => {
    on('get', '/api/portal/issues', () => errors.internal());
    const { user } = renderAt('/issues');

    await alertWith('서버에 연결하지 못했습니다. 잠시 뒤 다시 하세요.');
    const retry = await button('다시 불러오기');
    on('get', '/api/portal/issues', () => HttpResponse.json({ items: state.issues, page: 1, size: 20, totalElements: 1, totalPages: 1 }));
    await user.click(retry);

    await link(/ACME-7/);
  });

  test('P-5 2절 연결 끊김 저장할 때: 띠를 보이고 쓰던 글을 남기며 버튼은 다시 눌린다(저장 실패)', async () => {
    on('post', '/api/portal/issues/:key/comments', () => HttpResponse.error());
    const { user } = renderAt('/issues/ACME-7');
    await user.type(await field(/댓글/, 'textarea'), '사진 첨부합니다');
    const send = await button('보내기');
    await user.click(send);

    await alertWith('서버에 연결하지 못했습니다. 잠시 뒤 다시 하세요.');
    expect(draftKept('사진 첨부합니다')).toBe(true);
    await eventually(() => {
      expect(isBlocked(send)).toBe(false);
    });
  });
});

describe('2절 저장 중', () => {
  test('P-4 2절 저장 중: 보내기 버튼은 aria-disabled와 "보내는 중…", 포커스를 잃지 않고 다시 보내지 않는다, 다른 칸은 쓸 수 있다', async () => {
    const release = gate();
    on('post', '/api/portal/issues', async () => {
      await release.opened;
      return HttpResponse.json(issue({ key: 'ACME-8' }), { status: 201 });
    });
    const { user } = renderAt('/issues/new');
    await fillIssue(user);
    const submit = await button('올리기');
    await user.click(submit);

    await eventually(() => {
      expect(submit).toHaveAttribute('aria-disabled', 'true');
      expect(text(submit)).toContain('보내는 중…');
    });
    expect(document.activeElement).toBe(submit);
    await user.click(submit);
    await user.keyboard('{Enter}');
    const title = await field(/^제목/, 'input');
    await user.type(title, '!');
    expect(title).toHaveValue('프린터가 안 됩니다!');
    expect(callsTo('POST', '/api/portal/issues')).toHaveLength(1);

    release.open();
    await atPath('/issues/ACME-8');
  });
});

describe('2절 떠나기', () => {
  test('P-4 2절 떠나기: 쓰던 글이 있으면 beforeunload로 묻는다', async () => {
    const { user } = renderAt('/issues/new');
    const title = await field(/^제목/, 'input');
    const before = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(before);
    expect(before.defaultPrevented, '쓴 글이 없을 때').toBe(false);

    await user.type(title, '쓰다 만 제목');
    const after = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(after);
    expect(after.defaultPrevented, '쓴 글이 있을 때').toBe(true);
  });

  test('P-4 2절 떠나기: 앱 안 이동은 확인 대화상자, Esc로 닫으면 그 자리에 쓰던 글이 남는다', async () => {
    const { user } = renderAt('/issues/new');
    await user.type(await field(/^제목/, 'input'), '쓰다 만 제목');
    await user.click(await button('취소'));

    await dialog();
    expect(window.location.pathname).toBe('/issues/new');
    await user.keyboard('{Escape}');

    await eventually(() => {
      expect(screen.queryByRole('dialog') ?? screen.queryByRole('alertdialog')).toBeNull();
    });
    expect(window.location.pathname).toBe('/issues/new');
    expect(await field(/^제목/, 'input')).toHaveValue('쓰다 만 제목');
  });
});

describe('접근성 공통과 화면 머리', () => {
  test('P-3 → P-4 접근성: 화면이 바뀌면 포커스가 h1(tabindex=-1)로 가고 화면 제목이 document.title에 들어간다', async () => {
    const { user } = renderAt('/issues');
    await link(/ACME-7/);
    await user.click(await action('문의 올리기'));

    await atPath('/issues/new');
    await field(/^제목/, 'input');
    const h1 = await heading1();
    await eventually(() => {
      expect(document.activeElement).toBe(h1);
    });
    expect(h1).toHaveAttribute('tabindex', '-1');
    expect(document.title).toContain(text(h1));
  });

  test('P-3 접근성: 맨 앞 Tab 자리는 "본문으로 건너뛰기" 링크이고 본문을 가리킨다', async () => {
    renderAt('/issues');
    await link(/ACME-7/);

    const first = tabbables()[0];
    expect(first ? text(first) : '').toBe('본문으로 건너뛰기');
    const target = (first?.getAttribute('href') ?? '').replace(/^#/, '');
    expect(target, '건너뛰기 대상 id').not.toBe('');
    expect(document.getElementById(target), '건너뛰기 대상').not.toBeNull();
  });

  test('P-3 화면 머리: 표시 이름 "popdesk", 사용자 이름, 로그아웃하면 로그인 화면으로', async () => {
    const { user } = renderAt('/issues');
    await link(/ACME-7/);

    const header = await found(() => screen.queryByRole('banner'), '화면 머리(header)');
    expect(text(header)).toContain('popdesk');
    expect(text(header)).toContain('김고객');
    await user.click(await button('로그아웃'));

    await sent('POST', '/api/public/sign-out');
    await atPath('/login');
  });

});
