// More test support for slice 11 (design/archive/11-oidc/screens.md). The earlier support files stay as
// they are; this file adds the slice 11 endpoints to the fake server, in the shape of
// docs/integrate/openapi-console.json, and the words and helpers the 11 screen tests share.
// The portal has its own copy of the sign-in part (portal/src/__tests__/portal11.ts).
import type { ConsoleComponents } from '@popdesk/api/console';
import { http, HttpResponse } from 'msw/http';
import { beforeEach } from 'vitest';
import { errors, server, state, type SystemStatus } from './console';

type Schemas = ConsoleComponents['schemas'];
export type OidcStatus = Schemas['OidcStatus'];
export type OidcFailureKind = NonNullable<OidcStatus['lastFailure']>['kind'];

// ---------------------------------------------------------------------------
// C-1 로그인 (screens.md "C-1·P-1 로그인")

export const OPTIONS = '/api/public/sign-in-options';
export const START = '/api/public/oidc/start';

export const OIDC_BUTTON = '회사 계정으로 로그인';
export const OR = '또는';
export const MOVING = '이동 중…';

/** The band each `oidc` query value shows above the form. */
export const OIDC_BANDS = {
  failed: '회사 계정으로 로그인하지 못했습니다. 초대받은 이메일인지 확인하거나 관리자에게 문의하세요.',
  unavailable: '회사 로그인 서비스에 연결하지 못했습니다. 잠시 뒤 다시 시도하거나 비밀번호로 로그인하세요.',
  busy: '시도가 너무 많습니다. 잠시 뒤 다시 시도하세요.',
} as const;

/** The sign-in options of this host for this test: OIDC on or off. */
export function signInOptions(oidc: boolean) {
  server.use(http.get(`*${OPTIONS}`, () => HttpResponse.json<Schemas['SignInOptions']>({ oidc })));
}

interface Fiber {
  type: unknown;
  memoizedProps: unknown;
  return: Fiber | null;
}

function componentName(type: unknown): string | undefined {
  if (typeof type === 'function') return (type as { displayName?: string }).displayName ?? type.name;
  if (type === null || typeof type !== 'object') return undefined;
  // memo() and forwardRef() keep the component in `type` and `render`.
  const wrapped = type as { displayName?: string; type?: unknown; render?: unknown };
  return wrapped.displayName ?? componentName(wrapped.type ?? wrapped.render);
}

/**
 * The props the screen gave the shared OIDC piece (`OidcSignIn` of @popdesk/app, acceptance.md 5절 16)
 * that drew `el`, or null when no such component holds it. jsdom drops the address `location.assign`
 * goes to, so the screen tests read the `next` the screen passed from React's record of the element
 * (C-7 웹 점검 D1). The piece test (packages/app/src/OidcSignIn.11.test.tsx) sees that the piece moves there.
 */
export function oidcPieceProps(el: Element): Record<string, unknown> | null {
  const key = Object.keys(el).find((k) => k.startsWith('__reactFiber$'));
  let fiber = key ? ((el as unknown as Record<string, Fiber | undefined>)[key] ?? null) : null;
  for (; fiber; fiber = fiber.return) {
    if (componentName(fiber.type) === 'OidcSignIn') return fiber.memoizedProps as Record<string, unknown>;
  }
  return null;
}

// ---------------------------------------------------------------------------
// C-10 구성원 목록 (screens.md "C-10 구성원 목록")

export const LINKED_BADGE = 'OIDC 연결';
export const UNLINK = 'OIDC 연결 풀기';
export const UNLINK_TITLE = 'OIDC 연결을 풀까요?';
export const unlinkBody = (name: string) =>
  `${name}님이 로그인한 곳에서 모두 로그아웃됩니다. 다음에 회사 계정으로 로그인하면 그때의 이메일로 다시 연결됩니다.`;
export const UNLINK_GO = '연결 풀기';
export const CANCEL = '취소';
export const UNLINKED = 'OIDC 연결을 풀었습니다.';
export const ALREADY_UNLINKED = '이미 연결이 풀렸습니다.';

/** The path of `unlinkAccountOidc`. */
export const unlinkPath = (id: number) => `/api/console/accounts/${String(id)}/oidc-link`;

// ---------------------------------------------------------------------------
// C-18 시스템 상태 (screens.md "C-18 시스템 상태")

export const OIDC_TITLE = 'OIDC 로그인';
export const OFF = '꺼짐';
export const APPS_WORDS = { CONSOLE: '켜짐: 콘솔', PORTAL: '켜짐: 포털', BOTH: '켜짐: 콘솔과 포털' } as const;
export const NO_SUCCESS = '앱이 뜬 뒤 없음';
export const FAILURE_WORDS: Readonly<Record<OidcFailureKind, string>> = {
  DISCOVERY: '제공자 정보를 받지 못함',
  TOKEN: '토큰을 받지 못함',
  ID_TOKEN: 'ID 토큰이 맞지 않음',
};
export const COUNT_NOTE = '수는 앱이 다시 시작하면 0부터 셉니다.';
export const failuresText = (count: number) => `제공자 실패: ${String(count)}건`;

/** OIDC on for `apps`, with no sign-in and no failure yet unless given. */
export function oidcOn(over: Partial<OidcStatus> = {}): OidcStatus {
  return { enabled: true, apps: ['CONSOLE'], lastSuccessAt: null, providerFailures: 0, lastFailure: null, ...over };
}

/** The system status with this `oidc` part and the rest as console.ts leaves it. */
export function withOidc(oidc: OidcStatus): SystemStatus {
  return { ...state.status, oidc };
}

// ---------------------------------------------------------------------------
// Fake server

function requireAdmin(): Response | undefined {
  if (!state.signedIn) return errors.unauthenticated();
  return state.me.role === 'ADMIN' ? undefined : errors.forbidden();
}

/** What the 11 server does when nothing goes wrong. Tests replace one with `route` of console1a.ts. */
const handlers11 = [
  // 묶음 풀기: 204 and the account no longer linked; 404 without a link (S11-AC-27).
  http.delete('*/api/console/accounts/:accountId/oidc-link', ({ params }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    const linked = state.accounts.find((a) => String(a.id) === params.accountId && a.oidcLinked);
    if (!linked) return errors.notFound();
    state.accounts = state.accounts.map((a) => (a === linked ? { ...a, oidcLinked: false } : a));
    return new HttpResponse(null, { status: 204 });
  }),
];

beforeEach(() => {
  server.use(...handlers11);
});
