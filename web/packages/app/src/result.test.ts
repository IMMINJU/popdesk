// screens.md 2절: 모든 API 답을 한 꼴(Result)로 바꾼다. 401, 403, 404, 413, 429, 400(칸 있음, 없음), 409, 5xx, 끊긴 연결.
// 본문을 읽지 않고 시그니처, 주석, 앱 테스트(messages.behavior)가 고정한 동작으로 썼다.
import { describe, expect, it } from 'vitest';
import { call, failureOf, isAppError, retryAfter, type Failure } from './result';

const answer = (status: number, headers: Record<string, string> = {}) => new Response(null, { status, headers });
const BODY = { code: 'ISSUE_CLOSED', message: '닫힌 문의입니다' };
const FIELDS = [
  { field: 'title', code: 'REQUIRED' },
  { field: 'email', code: 'INVALID_FORMAT' },
];

describe('retryAfter', () => {
  it.each([
    ['머리글 없음', undefined, 60],
    ['1초', '1', 1],
    ['5초', '5', 5],
    ['큰 수', '900', 900],
    ['소수는 올림', '2.2', 3],
    ['1보다 작은 소수', '0.4', 1],
    ['0은 1초', '0', 1],
    ['음수는 1초', '-5', 1],
    ['읽을 수 없는 글', 'soon', 60],
    ['빈 글', '', 60],
  ])('COM-8 Retry-After %s → %s → %i초', (_, header, want) => {
    expect(retryAfter(answer(429, header === undefined ? {} : { 'Retry-After': header }))).toBe(want);
  });

  it('COM-8 Retry-After HTTP 날짜: 그때까지 남은 초, 지난 날짜는 1초', () => {
    const later = retryAfter(answer(429, { 'Retry-After': new Date(Date.now() + 90_000).toUTCString() }));
    expect(later).toBeGreaterThanOrEqual(85);
    expect(later).toBeLessThanOrEqual(90);

    const soon = retryAfter(answer(429, { 'Retry-After': new Date(Date.now() + 10_000).toUTCString() }));
    expect(soon).toBeGreaterThanOrEqual(5);
    expect(soon).toBeLessThanOrEqual(10);

    expect(retryAfter(answer(429, { 'Retry-After': new Date(Date.now() - 90_000).toUTCString() }))).toBe(1);
  });

  it('COM-8 Retry-After 503에서도 같은 머리글을 읽는다', () => {
    expect(retryAfter(answer(503, { 'Retry-After': '7' }))).toBe(7);
  });
});

describe('failureOf', () => {
  it.each<[string, number, Record<string, string>, unknown, Failure]>([
    ['401', 401, {}, BODY, { kind: 'unauthenticated' }],
    ['401 본문 없음', 401, {}, undefined, { kind: 'unauthenticated' }],
    ['403 code', 403, {}, { code: 'FORBIDDEN', message: 'm' }, { kind: 'forbidden', code: 'FORBIDDEN' }],
    ['403 CSRF', 403, {}, { code: 'CSRF_TOKEN_INVALID', message: 'm' }, { kind: 'forbidden', code: 'CSRF_TOKEN_INVALID' }],
    ['403 본문 없음', 403, {}, undefined, { kind: 'forbidden', code: '' }],
    ['403 프록시의 쪽', 403, {}, '<html>Forbidden</html>', { kind: 'forbidden', code: '' }],
    ['404', 404, {}, BODY, { kind: 'notFound' }],
    ['404 본문 없음', 404, {}, undefined, { kind: 'notFound' }],
    ['413', 413, {}, BODY, { kind: 'tooLarge' }],
    ['413 프록시의 쪽', 413, {}, '<html>Request Entity Too Large</html>', { kind: 'tooLarge' }],
    ['429 초', 429, { 'Retry-After': '5' }, BODY, { kind: 'rateLimited', seconds: 5 }],
    ['429 머리글 없음', 429, {}, BODY, { kind: 'rateLimited', seconds: 60 }],
    ['429 본문 없음', 429, { 'Retry-After': '12' }, undefined, { kind: 'rateLimited', seconds: 12 }],
    ['400 칸 있음', 400, {}, { code: 'INVALID_INPUT', message: '고쳐 주세요', fields: FIELDS }, { kind: 'invalid', fields: FIELDS, message: '고쳐 주세요' }],
    ['400 칸이 빔', 400, {}, { code: 'INVALID_INPUT', message: 'm', fields: [] }, { kind: 'rejected', status: 400, code: 'INVALID_INPUT', message: 'm' }],
    ['400 칸 없음', 400, {}, { code: 'INVALID_INPUT', message: 'm' }, { kind: 'rejected', status: 400, code: 'INVALID_INPUT', message: 'm' }],
    ['400 다른 code와 칸', 400, {}, { code: 'OTHER', message: 'm', fields: FIELDS }, { kind: 'rejected', status: 400, code: 'OTHER', message: 'm' }],
    ['409 INVALID_INPUT과 칸', 409, {}, { code: 'INVALID_INPUT', message: 'm', fields: FIELDS }, { kind: 'rejected', status: 409, code: 'INVALID_INPUT', message: 'm' }],
    ['409', 409, {}, BODY, { kind: 'rejected', status: 409, code: 'ISSUE_CLOSED', message: '닫힌 문의입니다' }],
    ['499', 499, {}, BODY, { kind: 'rejected', status: 499, code: 'ISSUE_CLOSED', message: '닫힌 문의입니다' }],
    ['409 message가 글자가 아님', 409, {}, { code: 'ISSUE_CLOSED', message: 3 }, { kind: 'rejected', status: 409, code: 'ISSUE_CLOSED', message: '' }],
    ['409 message 없음', 409, {}, { code: 'ISSUE_CLOSED' }, { kind: 'rejected', status: 409, code: 'ISSUE_CLOSED', message: '' }],
    ['409 글자 본문', 409, {}, 'not json', { kind: 'server' }],
    ['409 null', 409, {}, null, { kind: 'server' }],
    ['409 code 없음', 409, {}, { message: 'm' }, { kind: 'server' }],
    ['409 code가 수', 409, {}, { code: 7, message: 'm' }, { kind: 'server' }],
    ['400 본문 없음', 400, {}, undefined, { kind: 'server' }],
    ['500', 500, {}, BODY, { kind: 'server' }],
    ['500 본문 없음', 500, {}, undefined, { kind: 'server' }],
    ['502 프록시의 쪽', 502, {}, '<html>Bad Gateway</html>', { kind: 'server' }],
    ['503', 503, { 'Retry-After': '3' }, { code: 'SERVER_BUSY', message: 'm' }, { kind: 'server' }],
  ])('screens.md 2절 failureOf %s', (_, status, headers, error, want) => {
    expect(failureOf(answer(status, headers), error)).toEqual(want);
  });
});

describe('isAppError', () => {
  it.each<[string, unknown, boolean]>([
    ['앱의 오류 본문', BODY, true],
    ['code만', { code: 'PAYLOAD_TOO_LARGE' }, true],
    ['프록시의 쪽(글자)', '<html>413 Request Entity Too Large</html>', false],
    ['빈 글', '', false],
    ['없음', undefined, false],
    ['null', null, false],
    ['code 없는 객체', { message: 'm' }, false],
    ['빈 객체', {}, false],
  ])('S1A-AC-65 isAppError %s', (_, error, want) => {
    expect(isAppError(error)).toBe(want);
  });
});

describe('call', () => {
  it('screens.md 2절 call 성공: data와 response를 그대로 준다', async () => {
    const response = answer(200);
    const data = { id: 7 };

    const result = await call(Promise.resolve({ data, response }));

    expect(result).toEqual({ ok: true, data, response });
    expect(result.ok && result.response).toBe(response);
  });

  it('screens.md 2절 call 204: 본문이 없어도 성공이다', async () => {
    const response = answer(204);

    const result = await call<undefined>(Promise.resolve({ response }));

    expect(result.ok).toBe(true);
    expect(result).toMatchObject({ ok: true, response });
  });

  it.each<[string, number, unknown, Failure]>([
    ['409', 409, BODY, { kind: 'rejected', status: 409, code: 'ISSUE_CLOSED', message: '닫힌 문의입니다' }],
    ['401', 401, { code: 'UNAUTHENTICATED', message: 'm' }, { kind: 'unauthenticated' }],
    ['502 프록시의 쪽', 502, '<html>Bad Gateway</html>', { kind: 'server' }],
    ['빈 글 본문의 404', 404, '', { kind: 'notFound' }],
  ])('screens.md 2절 call 실패 %s: failureOf와 같은 실패', async (_, status, error, want) => {
    expect(await call(Promise.resolve({ error, response: answer(status) }))).toEqual({ ok: false, failure: want });
  });

  it('screens.md 2절 call 끊긴 연결: 던져진 오류는 network 실패다', async () => {
    expect(await call(Promise.reject(new TypeError('Failed to fetch')))).toEqual({ ok: false, failure: { kind: 'network' } });
  });
});
