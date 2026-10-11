// 1a screens.md 2절 모든 화면: 403 `CSRF_TOKEN_INVALID`를 받으면 토큰을 새로 받아 그 요청을 한 번만 다시 보낸다.
// 또 실패하면 "권한이 없습니다"가 아니라 새로고침 안내를 보이고 입력은 지우지 않는다(S1A-AC-57).
// 이미 있는 화면(C-4 댓글 쓰기)으로 본다. 서버가 쿠키를 새로 주는 것은 가짜 서버가 document.cookie로 흉내 낸다.
import { describe, expect, test } from 'vitest';
import { alertWith, button, callsTo, comment, draftKept, errors, eventually, found, on, pageText, renderAt, screen, sent, state, text } from '../__tests__/console';
import { answered, csrfInvalid, setCsrfCookie } from '../__tests__/console1a';

const COMMENTS = '/api/console/issues/ACME-7/comments';
const HEADER = 'X-XSRF-TOKEN';
const STALE_TEXT = '화면이 오래됐습니다. 새로고침한 뒤 다시 하세요.';

const editor = () =>
  found(() => screen.queryAllByRole<HTMLTextAreaElement>('textbox').find((el) => el.tagName === 'TEXTAREA'), '댓글 편집칸(textarea)');

/** The server gives a new token whenever the screen asks for one. */
function freshTokenOnAsk() {
  on('get', '/api/public/csrf', () => {
    setCsrfCookie('fresh-token');
    return new Response(null, { status: 204 });
  });
}

describe('403 CSRF_TOKEN_INVALID (1a)', () => {
  test('S1A-AC-57 C-4 댓글: 토큰을 새로 받아 같은 요청을 한 번 다시 보내고, 성공하면 오류를 보이지 않는다', async () => {
    setCsrfCookie('stale-token');
    freshTokenOnAsk();
    on('post', COMMENTS, async ({ request }) => {
      if (request.headers.get(HEADER) !== 'fresh-token') return csrfInvalid();
      const body = (await request.json()) as { content: string };
      const created = comment({ id: 900, content: body.content });
      state.comments = [created];
      return Response.json(created, { status: 201 });
    });
    const { user } = renderAt('/issues/ACME-7');
    await user.type(await editor(), '다시 보낼 답변');
    await user.click(await button('고객에게 답변 보내기'));

    const posts = await sent('POST', COMMENTS, 2);
    expect(posts.map((c) => c.headers.get(HEADER))).toEqual(['stale-token', 'fresh-token']);
    expect(await posts[1]?.body).toEqual(await posts[0]?.body);
    expect(callsTo('GET', '/api/public/csrf').length, '토큰을 새로 받는 요청').toBeGreaterThanOrEqual(1);
    await eventually(() => {
      expect(screen.queryAllByRole('article').some((a) => text(a).includes('다시 보낼 답변')), '새 댓글').toBe(true);
    });
    expect(screen.queryAllByRole('alert').filter((el) => text(el) !== ''), '오류 띠').toHaveLength(0);
  });

  test('S1A-AC-57 C-4 댓글: 다시 보낸 것도 403이면 더 보내지 않고 새로고침 안내를 보인다. 쓰던 글은 남는다', async () => {
    setCsrfCookie('stale-token');
    freshTokenOnAsk();
    on('post', COMMENTS, () => csrfInvalid());
    const { user } = renderAt('/issues/ACME-7');
    await user.type(await editor(), '남아야 하는 답변');
    const second = answered('POST', COMMENTS).then(() => answered('POST', COMMENTS));
    await user.click(await button('고객에게 답변 보내기'));

    await alertWith(STALE_TEXT);
    await second;
    expect(pageText()).not.toContain('권한이 없습니다');
    expect(draftKept('남아야 하는 답변')).toBe(true);
    expect(callsTo('POST', COMMENTS), '한 번만 다시 보낸다').toHaveLength(2);
  });

  test('S1A-AC-57 C-4 댓글: CSRF가 아닌 403은 다시 보내지 않고 "이 작업을 할 권한이 없습니다."를 보인다', async () => {
    setCsrfCookie('good-token');
    freshTokenOnAsk();
    on('post', COMMENTS, () => errors.forbidden());
    const { user } = renderAt('/issues/ACME-7');
    await user.type(await editor(), '보내지 못한 답변');
    const first = answered('POST', COMMENTS);
    await user.click(await button('고객에게 답변 보내기'));

    await alertWith('이 작업을 할 권한이 없습니다.');
    await first;
    expect(pageText()).not.toContain(STALE_TEXT);
    expect(callsTo('POST', COMMENTS), '다시 보내지 않는다').toHaveLength(1);
    expect(callsTo('POST', COMMENTS)[0]?.headers.get(HEADER)).toBe('good-token');
  });
});
