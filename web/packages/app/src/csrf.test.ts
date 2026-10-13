// conventions.md "Sessions and CSRF": 데이터를 바꾸는 요청은 쿠키의 토큰을 머리글에 싣는다. 쿠키가 없으면 먼저 받는다.
// 403 `CSRF_TOKEN_INVALID`면 토큰을 새로 받아 같은 요청을 한 번만 다시 보낸다(S1A-AC-57).
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { at, errorBody, server } from './__tests__/server';
import { csrfMiddleware, csrfToken } from './csrf';

const HEADER = 'X-XSRF-TOKEN';
const COMMENTS = '/api/portal/issues/ACME-7/comments';

function setCookie(name: string, value: string | null) {
  document.cookie = value === null ? `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/` : `${name}=${value}; path=/`;
}

/** Counts the asks for a token; each one sets the cookie to `give`, or leaves it alone when `give` is null. */
function tokenServer(give: string | null) {
  const asks: Request[] = [];
  server.use(
    http.get('*/api/public/csrf', ({ request }) => {
      asks.push(request);
      if (give !== null) setCookie('XSRF-TOKEN', give);
      return new HttpResponse(null, { status: 204 });
    }),
  );
  return asks;
}

/** What the server got for `method COMMENTS`: the token header and the body, in order. */
function commentServer(method: 'post' | 'patch' | 'delete' | 'put', answer: (token: string | null) => Response) {
  const got: { token: string | null; body: string }[] = [];
  server.use(
    http[method](`*${COMMENTS}`, async ({ request }) => {
      const token = request.headers.get(HEADER);
      got.push({ token, body: await request.text() });
      return answer(token);
    }),
  );
  return got;
}

let nextId = 0;

/** Sends one request the way openapi-fetch does: onRequest, fetch, onResponse. */
async function send(method: string, path: string, body?: string): Promise<Response> {
  nextId += 1;
  const id = `request-${String(nextId)}`;
  const first = new Request(at(path), { method, ...(body === undefined ? {} : { body, headers: { 'Content-Type': 'application/json' } }) });
  const request = (await csrfMiddleware.onRequest({ request: first, id })) ?? first;
  const response = await fetch(request);
  return (await csrfMiddleware.onResponse({ response, id })) ?? response;
}

beforeEach(() => {
  setCookie('XSRF-TOKEN', null);
  setCookie('theme', null);
  setCookie('XSRF-TOKEN-OLD', null);
});

describe('csrfToken', () => {
  it.each([
    ['글자 그대로', 'tok-Hk3sV8pQ', 'tok-Hk3sV8pQ'],
    ['인코딩된 값은 풀어서', 'a%2Fb%3Dc', 'a/b=c'],
    ['값 안의 =', 'abc==', 'abc=='],
  ])('S1A-AC-57 csrfToken 쿠키에 있음(%s): 묻지 않고 그 값을 준다', async (_, cookie, want) => {
    const asks = tokenServer('other');
    setCookie('XSRF-TOKEN', cookie);

    expect(await csrfToken()).toBe(want);
    expect(asks).toHaveLength(0);
  });

  it('S1A-AC-57 csrfToken 다른 쿠키 사이에서 이름이 꼭 맞는 것만 읽는다', async () => {
    tokenServer(null);
    setCookie('theme', 'dark');
    setCookie('XSRF-TOKEN-OLD', 'old');
    setCookie('XSRF-TOKEN', 'mine');

    expect(await csrfToken()).toBe('mine');
  });

  it('S1A-AC-57 csrfToken 쿠키에 없음: GET /api/public/csrf로 받아 온 값을 준다', async () => {
    const asks = tokenServer('fresh');
    setCookie('XSRF-TOKEN-OLD', 'old');

    expect(await csrfToken()).toBe('fresh');
    expect(asks).toHaveLength(1);
    expect(asks[0]?.method).toBe('GET');
    expect(new URL(asks[0]?.url ?? '').pathname).toBe('/api/public/csrf');
  });

  it('S1A-AC-57 csrfToken 서버가 쿠키를 주지 않으면 null', async () => {
    const asks = tokenServer(null);

    expect(await csrfToken()).toBeNull();
    expect(asks).toHaveLength(1);
  });

  it('S1A-AC-57 csrfToken 연결이 끊겨도 던지지 않고 null', async () => {
    server.use(http.get('*/api/public/csrf', () => HttpResponse.error()));

    expect(await csrfToken()).toBeNull();
  });
});

describe('csrfMiddleware 보낼 때', () => {
  it.each(['GET', 'HEAD', 'OPTIONS'])('S1A-AC-57 %s는 토큰을 싣지 않고 토큰을 묻지도 않는다', async (method) => {
    const asks = tokenServer('fresh');
    const request = new Request(at(COMMENTS), { method });

    expect(await csrfMiddleware.onRequest({ request, id: `safe-${method}` })).toBeUndefined();
    expect(request.headers.has(HEADER)).toBe(false);
    expect(asks).toHaveLength(0);
  });

  it.each(['post', 'patch', 'put', 'delete'] as const)('S1A-AC-57 %s는 쿠키의 토큰을 머리글에 실어 보낸다', async (method) => {
    setCookie('XSRF-TOKEN', 'good');
    const got = commentServer(method, () => new HttpResponse(null, { status: 204 }));

    const response = await send(method.toUpperCase(), COMMENTS, method === 'delete' ? undefined : '{"content":"답변"}');

    expect(response.status).toBe(204);
    expect(got).toEqual([{ token: 'good', body: method === 'delete' ? '' : '{"content":"답변"}' }]);
  });

  it('S1A-AC-57 쿠키가 없으면 먼저 토큰을 받아 싣는다', async () => {
    const asks = tokenServer('fresh');
    const got = commentServer('post', () => new HttpResponse(null, { status: 204 }));

    await send('POST', COMMENTS, '{}');

    expect(asks).toHaveLength(1);
    expect(got.map((g) => g.token)).toEqual(['fresh']);
  });

  it('S1A-AC-57 토큰을 받지 못해도 요청은 머리글 없이 그대로 간다', async () => {
    tokenServer(null);
    const got = commentServer('post', () => errorBody(403, 'FORBIDDEN'));
    const request = new Request(at(COMMENTS), { method: 'POST', body: '{}' });

    expect(await csrfMiddleware.onRequest({ request, id: 'no-token' })).toBe(request);
    expect(request.headers.has(HEADER)).toBe(false);
    expect((await fetch(request)).status).toBe(403);
    expect(got).toEqual([{ token: null, body: '{}' }]);
    csrfMiddleware.onError({ id: 'no-token' });
  });
});

describe('csrfMiddleware 403 CSRF_TOKEN_INVALID', () => {
  it('S1A-AC-57 토큰을 새로 받아 같은 요청을 한 번 다시 보내고, 둘째 답을 준다', async () => {
    setCookie('XSRF-TOKEN', 'stale');
    const asks = tokenServer('fresh');
    const got = commentServer('post', (token) => (token === 'fresh' ? HttpResponse.json({ id: 900 }, { status: 201 }) : errorBody(403, 'CSRF_TOKEN_INVALID')));

    const response = await send('POST', COMMENTS, '{"content":"다시 보낼 답변"}');

    expect(response.status).toBe(201);
    expect(await response.json()).toEqual({ id: 900 });
    expect(asks, '쿠키가 있어도 새로 받는다').toHaveLength(1);
    expect(got).toEqual([
      { token: 'stale', body: '{"content":"다시 보낼 답변"}' },
      { token: 'fresh', body: '{"content":"다시 보낼 답변"}' },
    ]);
  });

  it('S1A-AC-57 다시 보낸 것도 403이면 더 보내지 않고 그 답을 준다', async () => {
    setCookie('XSRF-TOKEN', 'stale');
    const asks = tokenServer('fresh');
    const got = commentServer('patch', () => errorBody(403, 'CSRF_TOKEN_INVALID'));

    const response = await send('PATCH', COMMENTS, '{"content":"남아야 하는 답변"}');

    expect(response.status).toBe(403);
    expect(await response.json()).toMatchObject({ code: 'CSRF_TOKEN_INVALID' });
    expect(got.map((g) => g.token)).toEqual(['stale', 'fresh']);
    expect(asks).toHaveLength(1);
  });

  it('S1A-AC-57 새 토큰을 받지 못하면 앞의 토큰으로 한 번 다시 보낸다', async () => {
    setCookie('XSRF-TOKEN', 'stale');
    tokenServer(null);
    const got = commentServer('post', () => errorBody(403, 'CSRF_TOKEN_INVALID'));

    const response = await send('POST', COMMENTS, '{}');

    expect(response.status).toBe(403);
    expect(got.map((g) => g.token)).toEqual(['stale', 'stale']);
  });

  it('S1A-AC-57 토큰이 처음부터 없고 새로도 받지 못하면 머리글 없이 한 번 다시 보낸다', async () => {
    tokenServer(null);
    const got = commentServer('post', () => errorBody(403, 'CSRF_TOKEN_INVALID'));

    const response = await send('POST', COMMENTS, '{}');

    expect(response.status).toBe(403);
    expect(got.map((g) => g.token)).toEqual([null, null]);
  });

  it('S1A-AC-57 답을 읽을 화면에는 본문이 남아 있다(답을 읽어 버리지 않는다)', async () => {
    setCookie('XSRF-TOKEN', 'good');
    commentServer('post', () => errorBody(403, 'FORBIDDEN'));

    const response = await send('POST', COMMENTS, '{}');

    expect(response.bodyUsed).toBe(false);
    expect(await response.json()).toMatchObject({ code: 'FORBIDDEN' });
  });

  it.each<[string, () => Response]>([
    ['CSRF가 아닌 403', () => errorBody(403, 'FORBIDDEN')],
    ['JSON이 아닌 403', () => new HttpResponse('<html>Forbidden</html>', { status: 403 })],
    ['본문 없는 403', () => new HttpResponse(null, { status: 403 })],
    ['code가 같은 409', () => errorBody(409, 'CSRF_TOKEN_INVALID')],
    ['code가 같은 400', () => errorBody(400, 'CSRF_TOKEN_INVALID')],
    ['성공', () => HttpResponse.json({ id: 1 }, { status: 201 })],
  ])('S1A-AC-57 %s: 다시 보내지 않고 토큰도 새로 받지 않는다', async (_, answer) => {
    setCookie('XSRF-TOKEN', 'good');
    const asks = tokenServer('fresh');
    const got = commentServer('post', answer);
    const request = new Request(at(COMMENTS), { method: 'POST', body: '{}' });
    await csrfMiddleware.onRequest({ request, id: 'once' });
    const response = await fetch(request);

    expect(await csrfMiddleware.onResponse({ response, id: 'once' })).toBeUndefined();
    expect(got.map((g) => g.token)).toEqual(['good']);
    expect(asks).toHaveLength(0);
  });

  it('S1A-AC-57 GET의 403 CSRF_TOKEN_INVALID는 다시 보내지 않는다', async () => {
    setCookie('XSRF-TOKEN', 'stale');
    const asks = tokenServer('fresh');
    let gets = 0;
    server.use(
      http.get(`*${COMMENTS}`, () => {
        gets += 1;
        return errorBody(403, 'CSRF_TOKEN_INVALID');
      }),
    );

    const response = await send('GET', COMMENTS);

    expect(response.status).toBe(403);
    expect(gets).toBe(1);
    expect(asks).toHaveLength(0);
  });

  it.each<[string, (id: string) => Promise<unknown>]>([
    ['답을 이미 받은', (id) => csrfMiddleware.onResponse({ response: new Response(null, { status: 204 }), id })],
    ['연결 오류로 끝난', (id) => {
      csrfMiddleware.onError({ id });
      return Promise.resolve();
    }],
  ])('S1A-AC-57 %s 요청의 복사본은 남지 않는다: 같은 id의 늦은 403에 다시 보내지 않는다', async (_, finish) => {
    setCookie('XSRF-TOKEN', 'stale');
    const asks = tokenServer('fresh');
    const got = commentServer('post', () => new HttpResponse(null, { status: 204 }));
    const request = new Request(at(COMMENTS), { method: 'POST', body: '{}' });
    await csrfMiddleware.onRequest({ request, id: 'finished' });
    await finish('finished');

    const late = await csrfMiddleware.onResponse({ response: errorBody(403, 'CSRF_TOKEN_INVALID'), id: 'finished' });

    expect(late).toBeUndefined();
    expect(got).toHaveLength(0);
    expect(asks).toHaveLength(0);
  });

  it('S1A-AC-57 같은 때 나간 두 요청은 저마다 제 본문으로 다시 간다', async () => {
    setCookie('XSRF-TOKEN', 'stale');
    tokenServer('fresh');
    const got = commentServer('post', (token) => (token === 'fresh' ? new HttpResponse(null, { status: 204 }) : errorBody(403, 'CSRF_TOKEN_INVALID')));

    const answers = await Promise.all([send('POST', COMMENTS, '{"content":"하나"}'), send('POST', COMMENTS, '{"content":"둘"}')]);

    expect(answers.map((r) => r.status)).toEqual([204, 204]);
    expect(got.filter((g) => g.token === 'fresh').map((g) => g.body).sort()).toEqual(['{"content":"둘"}', '{"content":"하나"}']);
    expect(got.filter((g) => g.token === 'stale')).toHaveLength(2);
  });
});
