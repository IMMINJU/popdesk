// 10a 묶음 design.md 0절 11: C-10과 C-12가 같이 쓰는 내보내기 확인 도움 startExport(url, { navigate, busyText }).
// HEAD로 묻고, 200이면 그 주소로 한 번 옮겨 간다(내려받기). 503이면 옮겨 가지 않고 Retry-After 초(없으면 null)로
// 화면이 넘긴 글을 만든다. 404·500·401이면 옮겨 가지 않는다. 401의 로그인 이동은 화면이 한다(C-12와 같은 길,
// C10MemberList.10a.test.tsx와 C12AuditLog.actions.test.tsx가 본다) (S10A-AC-29, C-7 웹 점검 D1).
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import { callsTo, errors, on } from '../__tests__/console';

type ExportCheck = { ok: true } | { ok: false; status: number; text: string };
type StartExport = (url: string, options: { navigate: (url: string) => void; busyText: (seconds: number | null) => string }) => Promise<ExportCheck>;

const ACCOUNT_EXPORT = '/api/console/accounts/2/export';
const AUDIT_EXPORT = '/api/console/audit-log/export';
const AUDIT_URL = `${AUDIT_EXPORT}?format=CSV&action=ACCOUNT_SUSPENDED`;

/**
 * Calls `startExport` of ./exportDownload.ts. The module comes with the implementation; until then
 * the test fails on the assertion here instead of failing to load.
 */
async function startExport(...args: Parameters<StartExport>): Promise<ExportCheck> {
  const where = './exportDownload';
  let made: unknown;
  try {
    made = await import(/* @vite-ignore */ where);
  } catch {
    made = undefined;
  }
  const helper = (made as { startExport?: unknown } | undefined)?.startExport;
  expect(typeof helper, 'app/exportDownload.ts의 startExport').toBe('function');
  return (helper as StartExport)(...args);
}

/** Runs the helper on `url` and keeps where it went and what seconds it asked the text for. */
async function check(url: string) {
  const went: string[] = [];
  const asked: (number | null)[] = [];
  const result = await startExport(url, {
    navigate: (to) => {
      went.push(to);
    },
    busyText: (seconds) => {
      asked.push(seconds);
      return `몰림 ${String(seconds)}`;
    },
  });
  return { result, went, asked };
}

describe('내보내기 확인 도움 startExport (10a 묶음)', () => {
  test.each([
    ['C-10 계정 내보내기', ACCOUNT_EXPORT, ACCOUNT_EXPORT],
    ['C-12 감사 로그(조건이 붙은 주소)', AUDIT_EXPORT, AUDIT_URL],
  ])('S10A-AC-29 startExport 200(%s): 같은 주소로 HEAD를 한 번 묻고, 그 주소로 한 번 옮겨 간다', async (_, path, url) => {
    on('head', path, () => new HttpResponse(null, { status: 200 }));

    const { result, went, asked } = await check(url);

    expect(result).toEqual({ ok: true });
    expect(went, '옮겨 간 주소').toEqual([url]);
    expect(asked, '503 글').toEqual([]);
    const heads = callsTo('HEAD', path);
    expect(heads).toHaveLength(1);
    const sentAt = new URL(heads[0]?.url ?? 'http://x/');
    expect(`${sentAt.pathname}${sentAt.search}`, 'HEAD의 주소').toBe(url);
  });

  test.each([
    ['Retry-After 7', { 'Retry-After': '7' }, 7],
    ['Retry-After 30', { 'Retry-After': '30' }, 30],
    ['Retry-After 없음', {}, null],
  ] as const)('S10A-AC-29 startExport 503(%s): 옮겨 가지 않고 화면이 넘긴 글에 그 초를 넘긴다', async (_, headers, seconds) => {
    on('head', ACCOUNT_EXPORT, () => new HttpResponse(null, { status: 503, headers }));

    const { result, went, asked } = await check(ACCOUNT_EXPORT);

    expect(went, '옮겨 간 주소').toEqual([]);
    expect(asked).toEqual([seconds]);
    expect(result).toEqual({ ok: false, status: 503, text: `몰림 ${String(seconds)}` });
  });

  test.each([
    // A `HEAD` answer has no body (the contract of headAccountExport).
    ['404', () => new HttpResponse(null, { status: 404 }), 404],
    ['500', () => errors.internal(), 500],
  ])('S10A-AC-29 startExport %s: 옮겨 가지 않고 그 상태와 글을 돌려준다', async (_, answer, status) => {
    on('head', ACCOUNT_EXPORT, () => answer());

    const { result, went, asked } = await check(ACCOUNT_EXPORT);

    expect(went, '옮겨 간 주소').toEqual([]);
    expect(asked, '503 글').toEqual([]);
    // The text is the screen's band: any text that is not blank.
    expect(result).toEqual({ ok: false, status, text: expect.stringMatching(/\S/) as unknown });
  });

  test('S10A-AC-29 startExport 401: 내려받으러 옮겨 가지 않고 401을 돌려준다(로그인 이동은 화면이 한다)', async () => {
    on('head', ACCOUNT_EXPORT, () => new HttpResponse(null, { status: 401 }));

    const { result, went } = await check(ACCOUNT_EXPORT);

    expect(went, '내려받기 주소').not.toContain(ACCOUNT_EXPORT);
    expect(result).toMatchObject({ ok: false, status: 401 });
  });
});
