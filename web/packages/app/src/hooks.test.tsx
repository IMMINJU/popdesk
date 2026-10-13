// screens.md 2절의 상태들: 처음 불러옴(300ms 뒤 뼈대), 401로 로그인 보내기, 429 초 세기, 떠나기 전 묻기, 400 칸 오류와 포커스,
// 떠나거나 대상이 바뀐 뒤 온 답 버리기(S1A-AC-58), 제목 포커스와 문서 제목.
import { act, render, renderHook, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { useRef, type ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { framedHook } from './__tests__/frame';
import { ask, errorBody, gate, server, settle, useFakeClock } from './__tests__/server';
import {
  SKELETON_DELAY_MS,
  useCooldown,
  useDocumentTitle,
  useFieldErrors,
  useFocusWhenReady,
  useLeaveGuard,
  useLive,
  useLoad,
  type FieldErrors,
  type FieldSpec,
  type FieldWords,
} from './hooks';

interface Issue {
  key: string;
}

/** Answers `GET /api/portal/issues/:key`; a key in `holds` waits for its gate, a key in `fails` gets that answer. */
function issueServer() {
  const box: { asked: string[]; holds: Record<string, Promise<void>>; fails: Record<string, () => Response> } = { asked: [], holds: {}, fails: {} };
  server.use(
    http.get('*/api/portal/issues/:key', async ({ params }) => {
      const key = String(params.key);
      box.asked.push(key);
      const fail = box.fails[key];
      if (key in box.holds) await box.holds[key];
      return fail ? fail() : HttpResponse.json({ key });
    }),
  );
  return box;
}

const useIssue = (props: { issue: string }) => useLoad(() => ask<Issue>(`/api/portal/issues/${props.issue}`), props.issue);

describe('useLoad', () => {
  it('screens.md 2절 처음 불러옴: 답이 오기 전에는 loading이고 데이터가 없다. 답이 오면 ready와 데이터다', async () => {
    const box = issueServer();
    const { now } = framedHook(useIssue, { issue: 'ACME-1' }, '/issues/ACME-1');
    expect(now()).toMatchObject({ status: 'loading', data: undefined, failure: undefined, slow: false });

    await waitFor(() => {
      expect(now().status).toBe('ready');
    });
    expect(now()).toMatchObject({ data: { key: 'ACME-1' }, failure: undefined, slow: false });
    await settle();
    expect(box.asked).toEqual(['ACME-1']);
  });

  it('screens.md 2절 뼈대: 300ms가 지나도 답이 없을 때만 slow가 켜지고, 답이 오면 꺼진다', async () => {
    useFakeClock();
    expect(SKELETON_DELAY_MS).toBe(300);
    const box = issueServer();
    const slow = gate();
    box.holds['ACME-1'] = slow.opened;
    const { now } = framedHook(useIssue, { issue: 'ACME-1' });
    await waitFor(() => {
      expect(box.asked).toHaveLength(1);
    });
    expect(now().slow).toBe(false);

    act(() => {
      vi.advanceTimersByTime(SKELETON_DELAY_MS);
    });
    expect(now()).toMatchObject({ status: 'loading', slow: true });

    slow.open();
    await waitFor(() => {
      expect(now().status).toBe('ready');
    });
    expect(now().slow).toBe(false);
  });

  it('screens.md 2절 뼈대: 빨리 온 답 뒤에는 300ms가 지나도 slow가 켜지지 않는다', async () => {
    useFakeClock();
    issueServer();
    const { now } = framedHook(useIssue, { issue: 'ACME-1' });
    await waitFor(() => {
      expect(now().status).toBe('ready');
    });

    act(() => {
      vi.advanceTimersByTime(SKELETON_DELAY_MS);
    });
    act(() => {
      vi.advanceTimersByTime(SKELETON_DELAY_MS);
    });

    expect(now().slow).toBe(false);
  });

  it.each<[string, () => Response, object]>([
    ['404', () => errorBody(404, 'NOT_FOUND'), { kind: 'notFound' }],
    ['403', () => errorBody(403, 'FORBIDDEN'), { kind: 'forbidden', code: 'FORBIDDEN' }],
    ['500', () => errorBody(500, 'SERVER_ERROR'), { kind: 'server' }],
    ['끊긴 연결', () => HttpResponse.error(), { kind: 'network' }],
  ])('screens.md 2절 불러오기 실패 %s: failed와 그 실패를 주고 로그인으로 보내지 않는다', async (_, fail, failure) => {
    const box = issueServer();
    box.fails['ACME-1'] = fail;
    const { now, router, setMe } = framedHook(useIssue, { issue: 'ACME-1' }, '/issues/ACME-1');

    await waitFor(() => {
      expect(now().status).toBe('failed');
    });
    expect(now()).toMatchObject({ data: undefined, failure, slow: false });
    expect(router.state.location.pathname).toBe('/issues/ACME-1');
    expect(setMe).not.toHaveBeenCalled();
  });

  it('screens.md 2절 다시 불러오기: 실패 뒤 reload가 성공하면 ready가 되고, reload는 그 답을 돌려준다', async () => {
    const box = issueServer();
    box.fails['ACME-1'] = () => errorBody(500, 'SERVER_ERROR');
    const { now } = framedHook(useIssue, { issue: 'ACME-1' });
    await waitFor(() => {
      expect(now().status).toBe('failed');
    });

    box.fails = {};
    let result: Awaited<ReturnType<ReturnType<typeof now>['reload']>>;
    await act(async () => {
      result = await now().reload();
    });

    expect(result).toMatchObject({ ok: true, data: { key: 'ACME-1' } });
    expect(now()).toMatchObject({ status: 'ready', data: { key: 'ACME-1' }, failure: undefined });
    expect(box.asked).toEqual(['ACME-1', 'ACME-1']);
  });

  it('screens.md 2절 다시 불러오기 실패: failed가 되지만 앞서 받은 데이터는 남는다', async () => {
    const box = issueServer();
    const { now } = framedHook(useIssue, { issue: 'ACME-1' });
    await waitFor(() => {
      expect(now().status).toBe('ready');
    });

    box.fails['ACME-1'] = () => errorBody(500, 'SERVER_ERROR');
    let result: Awaited<ReturnType<ReturnType<typeof now>['reload']>>;
    await act(async () => {
      result = await now().reload();
    });

    expect(result).toEqual({ ok: false, failure: { kind: 'server' } });
    expect(now()).toMatchObject({ status: 'failed', data: { key: 'ACME-1' }, failure: { kind: 'server' } });
  });

  it('screens.md 2절 대상이 바뀜: 새 답이 올 때까지 앞 데이터를 ready로 두고(실패는 지운다), 새 답이 오면 바꾼다', async () => {
    useFakeClock();
    const box = issueServer();
    const { now, rerender } = framedHook(useIssue, { issue: 'ACME-1' });
    await waitFor(() => {
      expect(now().status).toBe('ready');
    });

    const next = gate();
    box.holds['ACME-2'] = next.opened;
    rerender({ issue: 'ACME-2' });
    await waitFor(() => {
      expect(box.asked).toEqual(['ACME-1', 'ACME-2']);
    });
    expect(now()).toMatchObject({ status: 'ready', data: { key: 'ACME-1' }, failure: undefined, slow: false });

    act(() => {
      vi.advanceTimersByTime(SKELETON_DELAY_MS);
    });
    expect(now(), '새 대상을 300ms 넘게 기다림').toMatchObject({ status: 'ready', slow: true });

    next.open();
    await waitFor(() => {
      expect(now().data).toEqual({ key: 'ACME-2' });
    });
    expect(now()).toMatchObject({ status: 'ready', slow: false });
  });

  it('screens.md 2절 대상이 바뀜: 앞 대상이 실패였으면 새 답이 올 때까지 loading이다', async () => {
    const box = issueServer();
    box.fails['ACME-1'] = () => errorBody(404, 'NOT_FOUND');
    const { now, rerender } = framedHook(useIssue, { issue: 'ACME-1' });
    await waitFor(() => {
      expect(now().status).toBe('failed');
    });

    const next = gate();
    box.holds['ACME-2'] = next.opened;
    rerender({ issue: 'ACME-2' });

    expect(now()).toMatchObject({ status: 'loading', data: undefined, failure: undefined });
    next.open();
    await waitFor(() => {
      expect(now()).toMatchObject({ status: 'ready', data: { key: 'ACME-2' } });
    });
  });

  it('S1A-AC-58 대상이 바뀐 뒤에 온 앞 대상의 답은 버린다. 그 reload는 undefined를 준다', async () => {
    const box = issueServer();
    const { now, rerender } = framedHook(useIssue, { issue: 'ACME-2' });
    await waitFor(() => {
      expect(now().status).toBe('ready');
    });

    const old = gate();
    box.holds['ACME-2'] = old.opened;
    let stale: Promise<unknown> = Promise.resolve('not asked');
    act(() => {
      stale = now().reload();
    });
    await waitFor(() => {
      expect(box.asked).toHaveLength(2);
    });
    rerender({ issue: 'ACME-3' });
    await waitFor(() => {
      expect(now().data).toEqual({ key: 'ACME-3' });
    });

    old.open();
    expect(await stale).toBeUndefined();
    await settle();
    expect(now()).toMatchObject({ status: 'ready', data: { key: 'ACME-3' } });
  });

  it('S1A-AC-58 뒤에 물은 답이 먼저 온 뒤의 늦은 401은 로그인으로 보내지 않는다', async () => {
    const box = issueServer();
    const first = gate();
    box.holds['ACME-1'] = first.opened;
    box.fails['ACME-1'] = () => errorBody(401, 'UNAUTHENTICATED');
    const { now, rerender, router, setMe } = framedHook(useIssue, { issue: 'ACME-1' }, '/issues/ACME-1');
    await waitFor(() => {
      expect(box.asked).toHaveLength(1);
    });
    rerender({ issue: 'ACME-2' });
    await waitFor(() => {
      expect(now().data).toEqual({ key: 'ACME-2' });
    });

    first.open();
    await settle();

    expect(router.state.location.pathname).toBe('/issues/ACME-1');
    expect(setMe).not.toHaveBeenCalled();
  });

  it('screens.md 2절 401: 로그인 화면으로 보내고(next는 지금 주소) 실패 상태로 바꾸지 않는다. reload는 그 답을 준다', async () => {
    const box = issueServer();
    const { now, router, setMe } = framedHook(useIssue, { issue: 'ACME-1' }, '/issues/ACME-1?tab=all');
    await waitFor(() => {
      expect(now().status).toBe('ready');
    });

    box.fails['ACME-1'] = () => errorBody(401, 'UNAUTHENTICATED');
    const before = now();
    let result: unknown;
    await act(async () => {
      result = await before.reload();
    });

    expect(result).toEqual({ ok: false, failure: { kind: 'unauthenticated' } });
    expect(setMe.mock.calls).toEqual([[null]]);
    expect(router.state.location.pathname).toBe('/login');
    expect(new URLSearchParams(router.state.location.search).get('next')).toBe('/issues/ACME-1?tab=all');
  });

  it('screens.md 2절 처음부터 401: loading인 채 로그인 화면으로 간다', async () => {
    const box = issueServer();
    box.fails['ACME-1'] = () => errorBody(401, 'UNAUTHENTICATED');
    const { router } = framedHook(useIssue, { issue: 'ACME-1' }, '/issues/ACME-1', false);

    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/login');
    });
    expect(router.state.location.state).toEqual({ expired: false });
  });

  it('screens.md 2절 reload는 그릴 때마다 같은 함수이고, 그때의 대상을 묻는다', async () => {
    const box = issueServer();
    const { now, rerender } = framedHook(useIssue, { issue: 'ACME-1' });
    const first = now().reload;
    await waitFor(() => {
      expect(now().status).toBe('ready');
    });
    rerender({ issue: 'ACME-2' });
    await waitFor(() => {
      expect(now().data).toEqual({ key: 'ACME-2' });
    });

    expect(now().reload).toBe(first);
    await act(async () => {
      await first();
    });
    expect(box.asked).toEqual(['ACME-1', 'ACME-2', 'ACME-2']);
  });
});

describe('useCooldown', () => {
  const second = () => {
    act(() => {
      vi.advanceTimersByTime(1000);
    });
  };

  it('screens.md 2절 429 처음: 세지 않고 남은 초도 끝남 표시도 없다. 시간이 가도 그대로다', () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useCooldown());
    second();

    expect(result.current).toMatchObject({ active: false, left: 0, initial: 0, ended: false });
  });

  it('screens.md 2절 429 초 세기: 1초마다 줄고, 다 세면 한 번 끝남을 알린다. 처음 초는 그대로다', () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useCooldown());

    act(() => {
      result.current.start(3);
    });
    expect(result.current).toMatchObject({ active: true, left: 3, initial: 3, ended: false });

    second();
    expect(result.current).toMatchObject({ active: true, left: 2, initial: 3, ended: false });
    second();
    expect(result.current).toMatchObject({ active: true, left: 1, initial: 3, ended: false });
    second();
    expect(result.current).toMatchObject({ active: false, left: 0, initial: 3, ended: true });

    second();
    expect(result.current).toMatchObject({ active: false, left: 0, ended: true });
    expect(vi.getTimerCount(), '다 센 뒤에는 시계를 지운다').toBe(0);
  });

  it('screens.md 2절 429 끝남 지우기: clearEnded 뒤에는 끝남 표시가 없다', () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useCooldown());
    act(() => {
      result.current.start(1);
    });
    second();
    expect(result.current.ended).toBe(true);

    act(() => {
      result.current.clearEnded();
    });

    expect(result.current).toMatchObject({ active: false, left: 0, ended: false });
  });

  it('screens.md 2절 429 다시 받음: 세는 중에 다시 시작하면 새 초로 세고, 끝난 뒤 다시 시작하면 끝남 표시가 꺼진다', () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useCooldown());
    act(() => {
      result.current.start(5);
    });
    second();
    act(() => {
      result.current.start(2);
    });
    expect(result.current).toMatchObject({ active: true, left: 2, initial: 2 });

    second();
    second();
    expect(result.current).toMatchObject({ active: false, ended: true });

    act(() => {
      result.current.start(60);
    });
    expect(result.current).toMatchObject({ active: true, left: 60, initial: 60, ended: false });
    second();
    expect(result.current.left).toBe(59);
  });

  it('screens.md 2절 429 초 사이: 0.5초가 지났을 때 남은 초는 올림이다', () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useCooldown());
    act(() => {
      result.current.start(2);
    });

    act(() => {
      vi.advanceTimersByTime(1500);
    });

    expect(result.current).toMatchObject({ active: true, left: 1, ended: false });
  });

  it('screens.md 2절 429 떠남: 세는 중에 화면이 내려가면 시계를 지운다', () => {
    vi.useFakeTimers();
    const { result, unmount } = renderHook(() => useCooldown());
    act(() => {
      result.current.start(30);
    });
    expect(vi.getTimerCount()).toBe(1);

    unmount();

    expect(vi.getTimerCount()).toBe(0);
  });
});

describe('useLeaveGuard', () => {
  /** Whether the browser would ask before closing the tab. */
  function asksOnClose(): boolean {
    const e = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(e);
    return e.defaultPrevented;
  }

  it('UX-4 탭 닫기: 보내지 않은 글이 있을 때만 묻는다. 글이 없어지거나 화면이 내려가면 묻지 않는다', () => {
    const { rerender, unmount } = framedHook((props: { dirty: boolean }) => useLeaveGuard(props.dirty), { dirty: false });
    expect(asksOnClose()).toBe(false);

    rerender({ dirty: true });
    expect(asksOnClose()).toBe(true);

    rerender({ dirty: false });
    expect(asksOnClose()).toBe(false);

    rerender({ dirty: true });
    unmount();
    expect(asksOnClose()).toBe(false);
  });

  it('UX-4 앱 안에서 옮기기: 보내지 않은 글이 있으면 막고, 계속하면 옮긴다', async () => {
    const { now, router } = framedHook((props: { dirty: boolean }) => useLeaveGuard(props.dirty), { dirty: true }, '/issues/new');
    expect(now().state).toBe('unblocked');

    await act(async () => {
      await router.navigate('/other');
    });
    expect(now().state).toBe('blocked');
    expect(router.state.location.pathname).toBe('/issues/new');

    await act(async () => {
      now().proceed?.();
      await Promise.resolve();
    });
    expect(router.state.location.pathname).toBe('/other');
  });

  it('UX-4 앱 안에서 옮기기: 글이 없으면 막지 않는다', async () => {
    const { router } = framedHook((props: { dirty: boolean }) => useLeaveGuard(props.dirty), { dirty: false }, '/issues/new');

    await act(async () => {
      await router.navigate('/other');
    });

    expect(router.state.location.pathname).toBe('/other');
  });

  it('UX-4 같은 화면에서 쿼리만 바뀌는 것은 막지 않는다', async () => {
    const { now, router } = framedHook((props: { dirty: boolean }) => useLeaveGuard(props.dirty), { dirty: true }, '/issues/new');

    await act(async () => {
      await router.navigate('/issues/new?company=ACME');
    });

    expect(now().state).toBe('unblocked');
    expect(router.state.location.search).toBe('?company=ACME');
  });
});

describe('useFieldErrors', () => {
  type Field = 'title' | 'email' | 'priority' | 'note';
  const SPECS: Record<Field, FieldSpec> = {
    title: { id: 'f-title', limits: { min: 2, max: 200 } },
    email: { id: 'f-email', limits: { format: '이메일' }, text: { INVALID_FORMAT: '이메일 꼴로 넣으세요' } },
    priority: { id: 'f-priority' },
    note: { id: 'f-note' },
  };
  const fieldText = vi.fn<FieldWords['fieldText']>((code, limits) => `${code}(${String(limits?.min ?? '')}~${String(limits?.max ?? '')})`);
  const WORDS: FieldWords = { fieldText, fieldSummaryTitle: (count) => `${String(count)}개 칸을 고쳐 주세요` };

  function drawn() {
    function Form(props: { children: ReactNode }) {
      return (
        <form>
          {props.children}
          <input id="f-title" aria-label="제목" />
          <input id="f-email" aria-label="이메일" />
          <fieldset id="f-priority">
            <legend>우선순위</legend>
            <span>고르세요</span>
            <input type="radio" name="priority" aria-label="높음" />
            <input type="radio" name="priority" aria-label="낮음" />
          </fieldset>
          <div id="f-note" tabIndex={-1} aria-label="메모 편집기" role="group">
            <textarea aria-label="메모" />
          </div>
        </form>
      );
    }
    const { result } = renderHook(() => useFieldErrors(SPECS, WORDS), { wrapper: Form });
    const form = (): FieldErrors<Field> => result.current;
    return { form };
  }

  it('screens.md 2절 400 처음: 오류도 요약도 없다', () => {
    const { form } = drawn();

    expect(form().errors).toEqual({});
    expect(form().summary).toBeNull();
    expect(document.activeElement).toBe(document.body);
  });

  it('screens.md 2절 400 fields: 폼 순서로 칸 오류와 요약을 만들고 첫 오류 칸에 포커스를 둔다', () => {
    const { form } = drawn();

    act(() => {
      form().applyFields(
        [
          { field: 'email', code: 'REQUIRED' },
          { field: 'title', code: 'TOO_LONG' },
        ],
        '고쳐 주세요',
      );
    });

    expect(form().errors).toEqual({ title: 'TOO_LONG(2~200)', email: 'REQUIRED(~)' });
    expect(form().summary).toEqual({
      title: '2개 칸을 고쳐 주세요',
      items: [
        { fieldId: 'f-title', message: 'TOO_LONG(2~200)' },
        { fieldId: 'f-email', message: 'REQUIRED(~)' },
      ],
    });
    expect(fieldText).toHaveBeenCalledWith('TOO_LONG', { min: 2, max: 200 });
    expect(fieldText).toHaveBeenCalledWith('REQUIRED', { format: '이메일' });
    expect(document.activeElement).toBe(screen.getByRole('textbox', { name: '제목' }));
  });

  it('screens.md 2절 400 fields: 칸이 제 문구를 가진 code는 그 문구를 쓴다', () => {
    const { form } = drawn();

    act(() => {
      form().applyFields([{ field: 'email', code: 'INVALID_FORMAT' }], '');
    });

    expect(form().errors).toEqual({ email: '이메일 꼴로 넣으세요' });
    expect(form().summary).toEqual({ title: '1개 칸을 고쳐 주세요', items: [{ fieldId: 'f-email', message: '이메일 꼴로 넣으세요' }] });
    expect(document.activeElement).toBe(screen.getByRole('textbox', { name: '이메일' }));
  });

  it.each<[string, Field, () => HTMLElement]>([
    ['묶음(fieldset)은 그 안의 첫 입력', 'priority', () => screen.getByRole('radio', { name: '높음' })],
    ['tabindex가 있는 칸은 그 칸', 'note', () => screen.getByRole('group', { name: '메모 편집기' })],
  ])('screens.md 2절 400 포커스: %s에 둔다', (_, field, target) => {
    const { form } = drawn();

    act(() => {
      form().applyFields([{ field, code: 'REQUIRED' }], '');
    });

    expect(document.activeElement).toBe(target());
  });

  it('screens.md 2절 400 화면에 없는 칸: 수에는 넣고 서버의 글을 요약에 싣는다. 포커스는 옮기지 않는다', () => {
    const { form } = drawn();

    act(() => {
      form().applyFields([{ field: 'companyKey', code: 'REQUIRED' }], '고객사를 고르세요');
    });

    expect(form().errors).toEqual({});
    expect(form().summary).toEqual({ title: '1개 칸을 고쳐 주세요', items: [], serverMessage: '고객사를 고르세요' });
    expect(document.activeElement).toBe(document.body);
  });

  it('screens.md 2절 400 화면에 없는 칸과 있는 칸: 있는 칸의 오류와 서버의 글을 함께 싣는다', () => {
    const { form } = drawn();

    act(() => {
      form().applyFields(
        [
          { field: 'companyKey', code: 'REQUIRED' },
          { field: 'title', code: 'REQUIRED' },
        ],
        '고객사를 고르세요',
      );
    });

    expect(form().summary).toEqual({
      title: '2개 칸을 고쳐 주세요',
      items: [{ fieldId: 'f-title', message: 'REQUIRED(2~200)' }],
      serverMessage: '고객사를 고르세요',
    });
  });

  it.each<[string, { field: string; code: string }[], string]>([
    ['화면에 없는 칸인데 서버의 글이 빔', [{ field: 'companyKey', code: 'REQUIRED' }], ''],
    ['모두 화면에 있는 칸', [{ field: 'title', code: 'REQUIRED' }], '고쳐 주세요'],
  ])('screens.md 2절 400 %s: 요약에 서버의 글 칸이 없다', (_, fields, message) => {
    const { form } = drawn();

    act(() => {
      form().applyFields(fields, message);
    });

    expect(form().summary).not.toBeNull();
    expect(form().summary).not.toHaveProperty('serverMessage');
  });

  it('screens.md 2절 400 다시 받음: 앞의 칸 오류는 새 답으로 바뀐다', () => {
    const { form } = drawn();
    act(() => {
      form().applyFields([{ field: 'title', code: 'REQUIRED' }], '');
    });

    act(() => {
      form().applyFields([{ field: 'email', code: 'REQUIRED' }], '');
    });

    expect(form().errors).toEqual({ email: 'REQUIRED(~)' });
    expect(document.activeElement).toBe(screen.getByRole('textbox', { name: '이메일' }));
  });

  it('screens.md 2절 409 칸 하나: setError는 그 칸의 오류만 남기고 요약을 지우며 그 칸에 포커스를 둔다', () => {
    const { form } = drawn();
    act(() => {
      form().applyFields([{ field: 'title', code: 'REQUIRED' }], '');
    });

    act(() => {
      form().setError('email', '이미 쓰는 이메일입니다');
    });

    expect(form().errors).toEqual({ email: '이미 쓰는 이메일입니다' });
    expect(form().summary).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole('textbox', { name: '이메일' }));
  });

  it('screens.md 2절 지우기: clear는 오류와 요약을 지우고 포커스는 건드리지 않는다', () => {
    const { form } = drawn();
    act(() => {
      form().applyFields([{ field: 'title', code: 'REQUIRED' }], '');
    });
    const first = form();

    act(() => {
      form().clear();
    });

    expect(form().errors).toEqual({});
    expect(form().summary).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole('textbox', { name: '제목' }));
    expect([form().applyFields, form().setError, form().clear], '함수는 그대로 남는다').toEqual([first.applyFields, first.setError, first.clear]);
  });

  it('screens.md 2절 포커스는 오류를 넣은 그때 한 번만 옮긴다', () => {
    const { form } = drawn();
    act(() => {
      form().applyFields([{ field: 'title', code: 'REQUIRED' }], '');
    });
    act(() => {
      screen.getByRole('textbox', { name: '이메일' }).focus();
    });

    act(() => {
      form().clear();
    });

    expect(document.activeElement).toBe(screen.getByRole('textbox', { name: '이메일' }));
  });
});

describe('useLive', () => {
  it('S1A-AC-58 화면이 있는 동안 true, 내려간 뒤 false. 묻는 함수는 그대로다', () => {
    const { result, rerender, unmount } = renderHook(() => useLive());
    const live = result.current;
    expect(live()).toBe(true);

    rerender();
    expect(result.current).toBe(live);
    expect(live()).toBe(true);

    unmount();
    expect(live()).toBe(false);
  });
});

describe('useFocusWhenReady', () => {
  function Screen(props: { ready: boolean }) {
    const heading = useRef<HTMLHeadingElement>(null);
    useFocusWhenReady(heading, props.ready);
    return (
      <>
        {props.ready && (
          <h1 ref={heading} tabIndex={-1}>
            문의 목록
          </h1>
        )}
        <button type="button">새 문의</button>
      </>
    );
  }

  it('screens.md 2절 접근성: 준비되기 전에는 포커스를 옮기지 않고, 준비되면 제목에 둔다', () => {
    const { rerender } = render(<Screen ready={false} />);
    expect(document.activeElement).toBe(document.body);

    rerender(<Screen ready={true} />);

    expect(document.activeElement).toBe(screen.getByRole('heading', { name: '문의 목록' }));
  });

  it('screens.md 2절 접근성: 한 번만 옮긴다. 사용자가 고른 곳에서 다시 가져오지 않는다', () => {
    const { rerender } = render(<Screen ready={true} />);
    expect(document.activeElement).toBe(screen.getByRole('heading', { name: '문의 목록' }));
    screen.getByRole('button', { name: '새 문의' }).focus();

    rerender(<Screen ready={false} />);
    rerender(<Screen ready={true} />);

    expect(document.activeElement).toBe(screen.getByRole('button', { name: '새 문의' }));
  });
});

describe('useDocumentTitle', () => {
  it.each([
    ['문의 목록', 'popdesk 지원', '문의 목록 - popdesk 지원'],
    ['ACME-7 로그인 오류', 'popdesk', 'ACME-7 로그인 오류 - popdesk'],
  ])('screens.md 2절 접근성: 화면 제목 "%s", 앱 이름 "%s" → 문서 제목 "%s"', (title, displayName, want) => {
    document.title = '앞 화면';

    renderHook(() => {
      useDocumentTitle(title, displayName);
    });

    expect(document.title).toBe(want);
  });

  it('screens.md 2절 접근성: 제목이 아직 없으면(null) 문서 제목을 그대로 두고, 제목이 생기거나 바뀌면 바꾼다', () => {
    document.title = '앞 화면';
    const { rerender } = renderHook((props: { title: string | null }) => {
      useDocumentTitle(props.title, 'popdesk');
    }, { initialProps: { title: null as string | null } });
    expect(document.title).toBe('앞 화면');

    rerender({ title: 'ACME-7' });
    expect(document.title).toBe('ACME-7 - popdesk');

    rerender({ title: 'ACME-8' });
    expect(document.title).toBe('ACME-8 - popdesk');

    rerender({ title: null });
    expect(document.title).toBe('ACME-8 - popdesk');
  });
});
