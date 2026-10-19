// More test support for slice 11 (design/slices/11-oidc/screens.md "C-1·P-1 로그인"). `portal.ts`
// stays as it is; this file holds the words of the P-1 OIDC button and bands and answers the sign-in
// options, in the shape of docs/integrate/openapi-portal.json. The console has its own copy
// (console/src/__tests__/console11.ts); the two apps do not import each other's test code.
import type { PortalComponents } from '@popdesk/api/portal';
import { http, HttpResponse } from 'msw/http';
import { server } from './portal';

type Schemas = PortalComponents['schemas'];

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

export const signInFailed = () => Response.json({ code: 'SIGN_IN_FAILED', message: 'Sign-in failed.' }, { status: 401 });
