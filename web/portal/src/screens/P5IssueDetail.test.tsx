// screens.md 3절 P-5 문의 상세. 상담 쪽 작성자는 Person.kind = SUPPORT_TEAM으로 오고 "지원팀"으로 그린다(D-49).
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  BETA,
  COLLEAGUE,
  SUPPORT_TEAM,
  T0,
  acceptUpload,
  alertWith,
  attachment,
  button,
  callsTo,
  comment,
  dialog,
  draftKept,
  errors,
  eventually,
  expectFieldError,
  fieldState,
  expectNoAxeViolations,
  field,
  file,
  found,
  gate,
  heading1,
  hrefPath,
  issue,
  link,
  on,
  pageText,
  renderAt,
  screen,
  sent,
  state,
  text,
  within,
} from '../__tests__/portal';

const URL_7 = '/issues/ACME-7';
const MINE_ID = '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e01';
const SUPPORT_FILE_ID = '0b5e9d42-6f1a-4c3b-8d27-1e9f4a6c2b10';
const DELETED_ID = 'c3a8f0d1-2e4b-4a7c-9f16-5d0b8e2a7c33';

const editor = () => field<HTMLTextAreaElement>(/댓글/, 'textarea');
const commentForm = () => screen.queryByRole('form', { name: '댓글 쓰기' });
const uploadInput = () => screen.queryAllByLabelText(/첨부/, { selector: 'input[type="file"]' })[0];

async function titleShown() {
  return found(() => screen.queryByRole('heading', { name: /로그인이 안 됩니다/ }), '문의 제목');
}

describe('P-5 문의 상세', () => {
  test('P-5 데이터 있음: 머리에 키, 제목, 상태(고객용 말), 우선순위, 고객사, 올린 사람("나"), 올린 시각, 본문은 마크다운 보기', async () => {
    renderAt(URL_7);

    await titleShown();
    const header = text(screen.getByRole('main'));
    for (const part of ['ACME-7', '접수됨', '보통', '에이크미', '나']) {
      expect(header, `머리에 "${part}"`).toContain(part);
    }
    const times = Array.from(document.querySelectorAll('time')).map((t) => new Date(t.getAttribute('datetime') ?? '').getTime());
    expect(times, '올린 시각 <time datetime>').toContain(new Date(T0).getTime());
    const bold = Array.from(document.querySelectorAll('strong')).find((el) => text(el) === '로그인');
    expect(bold, '본문 마크다운의 굵게').toBeDefined();
    expect(bold?.closest('article'), '본문은 article 안').not.toBeNull();
  });

  test('P-5 데이터 있음: 비공개 문의는 비공개 표시', async () => {
    state.issue = issue({ private: true });
    renderAt(URL_7);

    await titleShown();
    expect(text(screen.getByRole('main'))).toContain('비공개');
  });

  test('P-5 댓글 작성자: 내 댓글은 "나", 상담 쪽 댓글은 "지원팀". 댓글 section 제목은 "댓글 N개", 쓰기 form은 "댓글 쓰기"', async () => {
    state.comments = [
      comment({ id: 1, author: { accountId: 11, kind: 'CUSTOMER', name: '김고객' }, content: '처음 댓글', createdAt: T0 }),
      comment({ id: 2, author: SUPPORT_TEAM, content: '확인하고 있습니다', createdAt: '2026-10-08T04:00:00Z' }),
    ];
    renderAt(URL_7);

    const section = await found(() => screen.queryByRole('region', { name: /댓글 2개/ }), '댓글 section "댓글 2개"');
    const items = within(section).queryAllByRole('article');
    expect(items).toHaveLength(2);
    expect(text(items[0])).toContain('처음 댓글');
    expect(text(items[0])).toContain('나');
    expect(text(items[1])).toContain('확인하고 있습니다');
    expect(text(items[1])).toContain('지원팀');
    expect(commentForm(), '댓글 쓰기 form').not.toBeNull();
  });

  test('P-5 댓글 없음·첨부 없음: "아직 댓글이 없습니다."와 첨부 영역 제목 아래 "첨부 없음"', async () => {
    renderAt(URL_7);

    await titleShown();
    await eventually(() => {
      expect(pageText()).toContain('아직 댓글이 없습니다.');
      expect(pageText()).toContain('첨부 없음');
    });
    expect(screen.queryByRole('heading', { name: /첨부/ }), '첨부 영역 제목').not.toBeNull();
  });

  test('P-5 첨부 목록: 내려받기 링크, 상담 쪽이 올린 첨부는 "지원팀", 지운 첨부는 "지워진 첨부" 흔적, 지우기 버튼은 내가 올린 것에만', async () => {
    state.attachments = [
      attachment({ id: MINE_ID, fileName: 'log.txt' }),
      attachment({ id: SUPPORT_FILE_ID, fileName: 'guide.pdf', uploadedBy: SUPPORT_TEAM, contentType: 'application/pdf' }),
      attachment({ id: DELETED_ID, fileName: null, deleted: true, deletedAt: T0, deletedBy: { accountId: 11, kind: 'CUSTOMER', name: '김고객' } }),
    ];
    renderAt(URL_7);

    const mine = await link(/log\.txt/);
    expect(hrefPath(mine)).toBe(`/api/portal/issues/ACME-7/attachments/${MINE_ID}`);
    const guide = await link(/guide\.pdf/);
    expect(hrefPath(guide)).toBe(`/api/portal/issues/ACME-7/attachments/${SUPPORT_FILE_ID}`);
    const guideRow = guide.closest('li') ?? guide.parentElement;
    expect(text(guideRow)).toContain('지원팀');
    expect(pageText()).toContain('지워진 첨부');
    expect(screen.queryAllByRole('link').filter((a) => hrefPath(a).endsWith(DELETED_ID))).toHaveLength(0);
    expect(screen.queryAllByRole('button', { name: /지우기/ }), '내가 올린 첨부의 지우기 버튼').toHaveLength(1);
  });

  test('P-5 동료 문의: 댓글 쓰기와 첨부 올리기·지우기를 숨기고 읽기와 내려받기만', async () => {
    state.issue = issue({ reporter: COLLEAGUE });
    state.comments = [comment({ author: COLLEAGUE, content: '동료가 쓴 댓글' })];
    state.attachments = [attachment({ uploadedBy: COLLEAGUE })];
    renderAt(URL_7);

    await titleShown();
    await link(/log\.txt/);
    expect(pageText()).toContain('동료가 쓴 댓글');
    expect(pageText()).toContain('박동료');
    expect(commentForm()).toBeNull();
    expect(uploadInput()).toBeUndefined();
    expect(screen.queryAllByRole('button', { name: /지우기/ })).toHaveLength(0);
  });

  test('P-5 소속에서 빠진 내 문의: 보기만, 댓글·첨부를 숨기고 "이 고객사에서 빠져 댓글을 달 수 없습니다"', async () => {
    state.companies = [BETA];
    state.attachments = [attachment()];
    renderAt(URL_7);

    await titleShown();
    await eventually(() => {
      expect(pageText()).toContain('이 고객사에서 빠져 댓글을 달 수 없습니다');
    });
    await link(/log\.txt/);
    expect(commentForm()).toBeNull();
    expect(uploadInput()).toBeUndefined();
  });

  test('P-5 CLOSED: 댓글 쓰기와 첨부 올리기를 숨기고 "종료된 문의입니다", 상태는 "종료됨"', async () => {
    state.issue = issue({ status: 'CLOSED' });
    renderAt(URL_7);

    await titleShown();
    await eventually(() => {
      expect(pageText()).toContain('종료된 문의입니다');
    });
    expect(text(screen.getByRole('main'))).toContain('종료됨');
    expect(commentForm()).toBeNull();
    expect(uploadInput()).toBeUndefined();
  });

  test('P-5 댓글 성공: 내용을 보내고 편집칸을 비우며 댓글을 다시 불러와 포커스를 새 댓글로', async () => {
    const { user } = renderAt(URL_7);
    const box = await editor();
    await sent('GET', '/api/portal/issues/ACME-7/comments');
    const loads = callsTo('GET', '/api/portal/issues/ACME-7/comments').length;
    await user.type(box, '재현 순서를 적습니다');
    await user.click(await button('보내기'));

    const [post] = await sent('POST', '/api/portal/issues/ACME-7/comments');
    expect(await post?.body).toEqual({ content: '재현 순서를 적습니다' });
    await eventually(() => {
      expect(box).toHaveValue('');
      expect(callsTo('GET', '/api/portal/issues/ACME-7/comments').length).toBeGreaterThan(loads);
    });
    await eventually(() => {
      const active = document.activeElement;
      expect(active).toHaveAttribute('tabindex', '-1');
      expect(text(active)).toContain('재현 순서를 적습니다');
    });
  });

  test('P-5 댓글 저장 중: 보내기 버튼은 막히고 편집칸은 쓸 수 있다', async () => {
    const release = gate();
    on('post', '/api/portal/issues/:key/comments', async () => {
      await release.opened;
      return HttpResponse.json(comment({ id: 500, content: '보낸 댓글' }), { status: 201 });
    });
    const { user } = renderAt(URL_7);
    const box = await editor();
    await user.type(box, '보낸 댓글');
    const send = await button('보내기');
    await user.click(send);

    await eventually(() => {
      expect(send).toHaveAttribute('aria-disabled', 'true');
    });
    expect(box).not.toBeDisabled();
    await user.click(send);
    expect(callsTo('POST', '/api/portal/issues/ACME-7/comments')).toHaveLength(1);
    release.open();
  });

  test('P-5 댓글 400 fields: 4,000자 넘음을 편집칸 아래에, 포커스는 편집칸', async () => {
    on('post', '/api/portal/issues/:key/comments', () => errors.invalid([{ field: 'content', code: 'TOO_LONG' }]));
    const { user } = renderAt(URL_7);
    const box = await editor();
    await user.type(box, '긴 댓글');
    const before = fieldState(box);
    await user.click(await button('보내기'));

    await expectFieldError(box, before);
    expect(document.activeElement).toBe(box);
    expect(box).toHaveValue('긴 댓글');
  });

  test('P-5 댓글 400 ISSUE_CLOSED: "문의가 종료되어 댓글을 달 수 없습니다." 띠, 쓰던 글은 남기고 문의를 다시 불러온다', async () => {
    on('post', '/api/portal/issues/:key/comments', () => {
      state.issue = issue({ status: 'CLOSED' });
      return HttpResponse.json({ code: 'ISSUE_CLOSED', message: 'This issue is closed.' }, { status: 400 });
    });
    const { user } = renderAt(URL_7);
    const box = await editor();
    await sent('GET', '/api/portal/issues/ACME-7');
    const loads = callsTo('GET', '/api/portal/issues/ACME-7').length;
    await user.type(box, '닫히기 직전에 쓴 글');
    await user.click(await button('보내기'));

    await alertWith('문의가 종료되어 댓글을 달 수 없습니다.');
    await eventually(() => {
      expect(callsTo('GET', '/api/portal/issues/ACME-7').length).toBeGreaterThan(loads);
    });
    expect(draftKept('닫히기 직전에 쓴 글')).toBe(true);
  });

  test('P-5 첨부 올리는 중: 고르면 바로 올리고 파일 줄에 진행 막대, 끝나면 첨부 목록에 보인다', async () => {
    const release = gate();
    on('post', '/api/portal/issues/:key/attachments', async ({ request }) => {
      await release.opened;
      return acceptUpload(request);
    });
    const { user } = renderAt(URL_7);
    await titleShown();
    await user.upload(await found(uploadInput, '첨부 올리기 파일 칸'), file('trace.log'));

    await sent('POST', '/api/portal/issues/ACME-7/attachments');
    await found(() => screen.queryAllByRole('progressbar')[0], '파일 줄의 진행 막대');
    release.open();
    await link(/trace\.log/);
  });

  test('S0-AC-87 P-5 첨부 413(앱 본문): 그 파일 줄에 "파일이 서버 앞단의 크기 상한에 걸렸습니다", "다시 올리기"·"빼기"', async () => {
    on('post', '/api/portal/issues/:key/attachments', () => errors.payloadTooLarge());
    const { user } = renderAt(URL_7);
    await titleShown();
    await user.upload(await found(uploadInput, '첨부 올리기 파일 칸'), file('dump.bin', 9 * 1024 * 1024));

    await eventually(() => {
      expect(pageText()).toContain('파일이 서버 앞단의 크기 상한에 걸렸습니다');
    });
    expect(pageText()).toContain('dump.bin');
    expect(pageText()).not.toContain('서버에 연결하지 못했습니다');
    await button('다시 올리기');
    await button('빼기');
  });

  test('S0-AC-87 P-5 첨부 413(앞단 프록시 HTML 본문): 알 수 없는 오류가 아니라 크기 상한 안내, 다시 올리기는 다시 보낸다', async () => {
    on('post', '/api/portal/issues/:key/attachments', () => errors.proxyPayloadTooLarge());
    const { user } = renderAt(URL_7);
    await titleShown();
    await user.upload(await found(uploadInput, '첨부 올리기 파일 칸'), file('dump.bin', 9 * 1024 * 1024));

    await eventually(() => {
      expect(pageText()).toContain('파일이 서버 앞단의 크기 상한에 걸렸습니다');
    });
    expect(pageText()).not.toContain('서버에 연결하지 못했습니다');
    await user.click(await button('다시 올리기'));
    await sent('POST', '/api/portal/issues/ACME-7/attachments', 2);
  });

  test('S0-AC-87 P-5 첨부 실패 400 한도: 그 파일 줄에 "다시 올리기"·"빼기"와 413이 아닌 안내, 빼면 줄이 사라진다', async () => {
    on('post', '/api/portal/issues/:key/attachments', () =>
      HttpResponse.json({ code: 'ISSUE_ATTACHMENTS_TOO_LARGE', message: 'Over 50 MB.' }, { status: 400 }),
    );
    const { user } = renderAt(URL_7);
    await titleShown();
    await user.upload(await found(uploadInput, '첨부 올리기 파일 칸'), file('big.iso', 9 * 1024 * 1024));

    await button('다시 올리기');
    expect(pageText()).toContain('big.iso');
    // 413만 크기 상한 안내다. 모든 실패에 같은 안내를 내는 화면을 막는다(400 한도 문구는 정해지지 않아 고정하지 않는다).
    expect(pageText()).not.toContain('파일이 서버 앞단의 크기 상한에 걸렸습니다');
    await user.click(await button('빼기'));
    await eventually(() => {
      expect(pageText()).not.toContain('big.iso');
    });
  });

  test('P-5 첨부 지우기: 확인 대화상자를 거쳐 지우고, 다시 불러와 "지워진 첨부" 흔적으로 남는다', async () => {
    state.attachments = [attachment({ id: MINE_ID, fileName: 'log.txt' })];
    const { user } = renderAt(URL_7);
    await link(/log\.txt/);
    await user.click(await button(/지우기/));

    const confirmBox = await dialog();
    expect(callsTo('DELETE', `/api/portal/issues/ACME-7/attachments/${MINE_ID}`)).toHaveLength(0);
    await user.click(within(confirmBox).getByRole('button', { name: /지우기/ }));

    await sent('DELETE', `/api/portal/issues/ACME-7/attachments/${MINE_ID}`);
    await eventually(() => {
      expect(pageText()).toContain('지워진 첨부');
      expect(screen.queryByRole('link', { name: /log\.txt/ })).toBeNull();
    });
  });

  test('P-5 내려받기: 누르면 먼저 HEAD로 묻고, 200이면 띠 없이 내려받는다', async () => {
    state.attachments = [attachment({ id: MINE_ID, fileName: 'log.txt' })];
    const { user } = renderAt(URL_7);
    await user.click(await link(/log\.txt/));

    await sent('HEAD', `/api/portal/issues/ACME-7/attachments/${MINE_ID}`);
    expect(screen.queryAllByRole('alert').filter((el) => text(el).includes('파일을 찾을 수 없습니다'))).toHaveLength(0);
  });

  test('P-5 내려받기 404: HEAD가 404면 "파일을 찾을 수 없습니다" 띠', async () => {
    state.attachments = [attachment({ id: MINE_ID, fileName: 'log.txt' })];
    on('head', '/api/portal/issues/:key/attachments/:id', () => new HttpResponse(null, { status: 404 }));
    const { user } = renderAt(URL_7);
    await user.click(await link(/log\.txt/));

    await sent('HEAD', `/api/portal/issues/ACME-7/attachments/${MINE_ID}`);
    await alertWith('파일을 찾을 수 없습니다');
  });

  test('P-5 마크다운 보기 안의 링크는 새 탭, rel="noopener noreferrer"(COM-6)', async () => {
    state.issue = issue({ content: '[설치 안내](https://example.com/help) 를 따라 했습니다.' });
    renderAt(URL_7);

    const a = await link('설치 안내');
    expect(a).toHaveAttribute('target', '_blank');
    const rel = (a.getAttribute('rel') ?? '').split(/\s+/);
    expect(rel).toEqual(expect.arrayContaining(['noopener', 'noreferrer']));
  });

  test('P-5 404: 2절 "찾을 수 없습니다", 볼 수 없는 문의와 없는 문의를 나누지 않는다', async () => {
    renderAt('/issues/BETA-3');

    await eventually(() => {
      expect(pageText()).toContain('찾을 수 없습니다');
    });
    expect(pageText()).not.toContain('권한');
  });

  test('P-5 접근성: 처음 포커스는 h1(tabindex=-1), 화면 제목을 document.title에', async () => {
    renderAt(URL_7);
    await titleShown();

    const h1 = await heading1();
    expect(h1).toHaveAttribute('tabindex', '-1');
    await eventually(() => {
      expect(document.activeElement).toBe(h1);
    });
    expect(document.title).toContain(text(h1));
  });

  test('P-5 axe: 댓글·첨부가 있는 화면에 접근성 위반이 없다', async () => {
    state.comments = [comment({ id: 1 }), comment({ id: 2, author: SUPPORT_TEAM, content: '답변드립니다' })];
    state.attachments = [attachment({ id: MINE_ID }), attachment({ id: SUPPORT_FILE_ID, fileName: 'guide.pdf', uploadedBy: SUPPORT_TEAM })];
    renderAt(URL_7);
    await titleShown();
    await link(/guide\.pdf/);
    await editor();

    await expectNoAxeViolations();
  });
});
