// screens.md 4절 C-4 문의 상세. 공개·내부 댓글을 시간순으로 섞어 보이고, 댓글 쓰기에 공개·내부 라디오 묶음이 있다.
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  ADMIN_PERSON,
  AGENT,
  AGENT_PERSON,
  CUSTOMER_PERSON,
  OTHER_AGENT_PERSON,
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
  expectNoAxeViolations,
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
} from '../__tests__/console';

const URL_7 = '/issues/ACME-7';
const CUSTOMER_FILE = '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e01';
const AGENT_FILE = '0b5e9d42-6f1a-4c3b-8d27-1e9f4a6c2b10';
const INTERNAL_FILE = 'a41d7c0e-5b2f-4e19-8c3a-6f0d2b9e1a74';

type User = ReturnType<typeof renderAt>['user'];

/** The comment editor: the one multi-line text box on the screen. */
const editor = () =>
  found(
    () => screen.queryAllByRole<HTMLTextAreaElement>('textbox').find((el) => el.tagName === 'TEXTAREA'),
    '댓글 편집칸(textarea)',
  );
const visibilityGroup = () => found(() => screen.queryByRole('radiogroup', { name: /공개|내부|댓글|답변/ }), '공개·내부 라디오 묶음');
const publicTab = () => found(() => screen.queryByRole('radio', { name: '공개 답변' }), '"공개 답변" 라디오');
const internalTab = () => found(() => screen.queryByRole('radio', { name: '내부 메모' }), '"내부 메모" 라디오');
const sendPublic = () => button('고객에게 답변 보내기');
const sendInternal = () => button('내부 메모 남기기');
const uploadInput = () => screen.queryAllByLabelText(/첨부/, { selector: 'input[type="file"]' })[0];
const anySend = () => screen.queryByRole('button', { name: /고객에게 답변 보내기|내부 메모 남기기/ });

async function titleShown() {
  return found(() => screen.queryByRole('heading', { name: /로그인이 안 됩니다/ }), '문의 제목');
}

/** The comment `article` holding `body`. */
async function commentWith(body: string): Promise<HTMLElement> {
  return found(
    () => screen.queryAllByRole('article').find((a) => text(a).includes(body) && a.querySelectorAll('article').length === 0),
    `댓글 "${body}"`,
  );
}

async function writeAndSend(user: User, body: string, send: () => Promise<HTMLElement>) {
  await user.type(await editor(), body);
  await user.click(await send());
}

describe('C-4 문의 상세', () => {
  test('C-4 데이터 있음: 머리에 키, 제목, 상태(상담용 말), 우선순위, 고객사, 올린 사람, 올린 시각, 본문은 마크다운 보기', async () => {
    state.issue = issue({ status: 'IN_PROGRESS', priority: 'HIGH' });
    renderAt(URL_7);

    await titleShown();
    const main = text(screen.getByRole('main'));
    for (const part of ['ACME-7', '처리 중', '높음', '에이크미', '김고객']) {
      expect(main, `머리에 "${part}"`).toContain(part);
    }
    const times = Array.from(document.querySelectorAll('time')).map((t) => new Date(t.getAttribute('datetime') ?? '').getTime());
    expect(times, '올린 시각 <time datetime>').toContain(new Date(T0).getTime());
    const bold = Array.from(document.querySelectorAll('strong')).find((el) => text(el) === '로그인');
    expect(bold, '본문 마크다운의 굵게').toBeDefined();
  });

  test('C-4 상태 말: 상담용 값 이름을 쓰고 고객용 말("접수됨")을 쓰지 않는다', async () => {
    renderAt(URL_7);

    await titleShown();
    await eventually(() => {
      expect(text(screen.getByRole('main'))).toContain('접수');
    });
    expect(text(screen.getByRole('main'))).not.toContain('접수됨');
  });

  test('C-4 비공개 문의는 비공개 표시', async () => {
    state.issue = issue({ private: true });
    renderAt(URL_7);

    await titleShown();
    await eventually(() => {
      expect(text(screen.getByRole('main'))).toContain('비공개');
    });
  });

  test('S0-AC-53 C-4 내부 댓글 표시: 공개·내부 댓글을 시간순으로 섞고, 내부 댓글 머리에 "내부 메모", 작성자 이름을 보인다', async () => {
    state.comments = [
      comment({ id: 1, author: CUSTOMER_PERSON, content: '처음 문의 보충', createdAt: '2026-10-08T03:20:00Z' }),
      comment({ id: 2, author: AGENT_PERSON, content: '재현 확인 필요', internal: true, createdAt: '2026-10-08T03:30:00Z' }),
      comment({ id: 3, author: AGENT_PERSON, content: '확인하고 있습니다', createdAt: '2026-10-08T03:40:00Z' }),
    ];
    renderAt(URL_7);

    const first = await commentWith('처음 문의 보충');
    const internal = await commentWith('재현 확인 필요');
    const reply = await commentWith('확인하고 있습니다');
    expect(first.compareDocumentPosition(internal) & Node.DOCUMENT_POSITION_FOLLOWING, '시간순: 1 → 2').toBeTruthy();
    expect(internal.compareDocumentPosition(reply) & Node.DOCUMENT_POSITION_FOLLOWING, '시간순: 2 → 3').toBeTruthy();
    expect(text(internal)).toContain('내부 메모');
    expect(text(internal)).toContain('이상담');
    expect(text(reply)).not.toContain('내부 메모');
    expect(text(reply)).toContain('이상담');
    expect(text(first)).toContain('김고객');
  });

  test('C-4 댓글 쓰기: "공개 답변"·"내부 메모" 라디오 묶음, 기본은 공개이고 보내기 버튼은 "고객에게 답변 보내기"', async () => {
    renderAt(URL_7);

    await visibilityGroup();
    expect(await publicTab()).toBeChecked();
    expect(await internalTab()).not.toBeChecked();
    await sendPublic();
    expect(screen.queryByRole('button', { name: '내부 메모 남기기' })).toBeNull();
  });

  test('C-4 댓글 쓰기: 내부를 고르면 보내기 버튼 글자가 "내부 메모 남기기"로 바뀐다(색만으로 가르지 않는다)', async () => {
    const { user } = renderAt(URL_7);
    await user.click(await internalTab());

    expect(await internalTab()).toBeChecked();
    await sendInternal();
    expect(screen.queryByRole('button', { name: '고객에게 답변 보내기' })).toBeNull();
  });

  test('C-4 키보드: 공개·내부는 화살표로 바꾼다', async () => {
    const { user } = renderAt(URL_7);
    const pub = await publicTab();
    pub.focus();

    await user.keyboard('{ArrowRight}');
    expect(await internalTab()).toBeChecked();
    expect(document.activeElement).toBe(await internalTab());
    await sendInternal();
    await user.keyboard('{ArrowLeft}');
    expect(await publicTab()).toBeChecked();
    await sendPublic();
  });

  test('C-4 공개 답변 성공: internal 없이(또는 false로) 보내고 편집칸을 비우며 포커스를 새 댓글로', async () => {
    const { user } = renderAt(URL_7);
    const box = await editor();
    await sent('GET', '/api/console/issues/ACME-7/comments');
    const loads = callsTo('GET', '/api/console/issues/ACME-7/comments').length;
    await writeAndSend(user, '재설정 링크를 보내드렸습니다', sendPublic);

    const [post] = await sent('POST', '/api/console/issues/ACME-7/comments');
    const body = (await post?.body) as { content: string; internal?: boolean };
    expect(body.content).toBe('재설정 링크를 보내드렸습니다');
    expect(body.internal ?? false, '공개 답변').toBe(false);
    await eventually(() => {
      expect(box).toHaveValue('');
      expect(callsTo('GET', '/api/console/issues/ACME-7/comments').length).toBeGreaterThan(loads);
    });
    await eventually(() => {
      const active = document.activeElement;
      expect(active).toHaveAttribute('tabindex', '-1');
      expect(text(active)).toContain('재설정 링크를 보내드렸습니다');
    });
  });

  test('S0-AC-53 C-4 내부 메모 성공: internal=true로 보내고, 새 댓글은 "내부 메모"로 보인다', async () => {
    const { user } = renderAt(URL_7);
    await user.click(await internalTab());
    await writeAndSend(user, '고객 계정 잠김 상태 확인함', sendInternal);

    const [post] = await sent('POST', '/api/console/issues/ACME-7/comments');
    expect(await post?.body).toEqual({ content: '고객 계정 잠김 상태 확인함', internal: true });
    const created = await commentWith('고객 계정 잠김 상태 확인함');
    expect(text(created)).toContain('내부 메모');
  });

  test('C-4 단축키: 편집칸에서 Ctrl+Enter로 보낸다', async () => {
    const { user } = renderAt(URL_7);
    const box = await editor();
    await user.type(box, '단축키로 보냅니다');
    await user.keyboard('{Control>}{Enter}{/Control}');

    const [post] = await sent('POST', '/api/console/issues/ACME-7/comments');
    expect(((await post?.body) as { content: string }).content).toBe('단축키로 보냅니다');
  });

  test('C-4 댓글 저장 중: 보내기 버튼은 aria-disabled, 편집칸은 쓸 수 있고 다시 보내지 않는다', async () => {
    const release = gate();
    on('post', '/api/console/issues/:key/comments', async () => {
      await release.opened;
      return HttpResponse.json(comment({ id: 500, author: AGENT_PERSON, content: '보낸 답변' }), { status: 201 });
    });
    const { user } = renderAt(URL_7);
    const box = await editor();
    await user.type(box, '보낸 답변');
    const send = await sendPublic();
    await user.click(send);

    await eventually(() => {
      expect(send).toHaveAttribute('aria-disabled', 'true');
    });
    expect(box).not.toBeDisabled();
    await user.click(send);
    expect(callsTo('POST', '/api/console/issues/ACME-7/comments')).toHaveLength(1);
    release.open();
  });

  test('C-4 댓글 400 fields: 4,000자 넘음을 편집칸 아래에, 포커스는 편집칸, 쓰던 글은 남는다', async () => {
    on('post', '/api/console/issues/:key/comments', () => errors.invalid([{ field: 'content', code: 'TOO_LONG' }]));
    const { user } = renderAt(URL_7);
    const box = await editor();
    await writeAndSend(user, '긴 답변', sendPublic);

    await expectFieldError(box);
    expect(document.activeElement).toBe(box);
    expect(box).toHaveValue('긴 답변');
  });

  test('C-4 댓글 400 ISSUE_CLOSED: "문의가 종료되어 댓글을 달 수 없습니다." 띠, 쓰던 글은 남기고 문의를 다시 불러온다', async () => {
    on('post', '/api/console/issues/:key/comments', () => {
      state.issue = issue({ status: 'CLOSED' });
      return errors.issueClosed();
    });
    const { user } = renderAt(URL_7);
    await editor();
    await sent('GET', '/api/console/issues/ACME-7');
    const loads = callsTo('GET', '/api/console/issues/ACME-7').length;
    await writeAndSend(user, '닫히기 직전에 쓴 답변', sendPublic);

    await alertWith('문의가 종료되어 댓글을 달 수 없습니다.');
    await eventually(() => {
      expect(callsTo('GET', '/api/console/issues/ACME-7').length).toBeGreaterThan(loads);
    });
    expect(draftKept('닫히기 직전에 쓴 답변')).toBe(true);
  });

  test('C-4 댓글 실패(5xx): 띠를 보이고 쓰던 글을 남기며 포커스는 편집칸, 버튼은 다시 눌린다', async () => {
    on('post', '/api/console/issues/:key/comments', () => errors.internal());
    const { user } = renderAt(URL_7);
    const box = await editor();
    await writeAndSend(user, '다시 보낼 답변', sendPublic);

    await alertWith('서버에 연결하지 못했습니다. 잠시 뒤 다시 하세요.');
    expect(box).toHaveValue('다시 보낼 답변');
    await eventually(() => {
      expect(document.activeElement).toBe(box);
      expect(anySend()).not.toHaveAttribute('aria-disabled', 'true');
    });
  });

  test('C-4 CLOSED: 댓글 쓰기와 첨부 올리기를 숨긴다(재오픈 버튼도 없다)', async () => {
    state.issue = issue({ status: 'CLOSED' });
    state.attachments = [attachment({ id: CUSTOMER_FILE })];
    renderAt(URL_7);

    await titleShown();
    await link(/log\.txt/);
    expect(text(screen.getByRole('main'))).toContain('종료');
    expect(screen.queryAllByRole('textbox').filter((el) => el.tagName === 'TEXTAREA')).toHaveLength(0);
    expect(anySend()).toBeNull();
    expect(uploadInput()).toBeUndefined();
    expect(screen.queryByRole('button', { name: /재오픈|다시 열기/ })).toBeNull();
  });

  test('C-4 동료·권한: 상담원은 배정된 고객사 문의 어디에나 댓글을 쓸 수 있다(RULE-08)', async () => {
    state.me = AGENT;
    state.issue = issue({ reporter: CUSTOMER_PERSON });
    renderAt(URL_7);

    await titleShown();
    await editor();
    await sendPublic();
    await found(uploadInput, '첨부 올리기 파일 칸');
  });

  test('C-4 첨부: 문의 첨부와 댓글 첨부, 내부 댓글의 첨부에는 "내부" 배지, 내려받기 링크는 문의 아래 경로', async () => {
    state.comments = [comment({ id: 41, author: AGENT_PERSON, content: '덤프 첨부', internal: true })];
    state.attachments = [
      attachment({ id: CUSTOMER_FILE, fileName: 'log.txt' }),
      attachment({ id: INTERNAL_FILE, fileName: 'heap.hprof', commentId: 41, internal: true, uploadedBy: AGENT_PERSON }),
    ];
    renderAt(URL_7);

    const publicFile = await link(/log\.txt/);
    expect(hrefPath(publicFile)).toBe(`/api/console/issues/ACME-7/attachments/${CUSTOMER_FILE}`);
    const internalFile = await link(/heap\.hprof/);
    expect(hrefPath(internalFile)).toBe(`/api/console/issues/ACME-7/attachments/${INTERNAL_FILE}`);
    const internalRow = internalFile.closest('li') ?? internalFile.parentElement;
    expect(text(internalRow)).toContain('내부');
    const publicRow = publicFile.closest('li') ?? publicFile.parentElement;
    expect(text(publicRow)).not.toContain('내부');
  });

  test('C-4 첨부 지우기 버튼(상담원): 자기가 올린 첨부에만', async () => {
    state.me = AGENT;
    state.attachments = [
      attachment({ id: CUSTOMER_FILE, fileName: 'log.txt', uploadedBy: CUSTOMER_PERSON }),
      attachment({ id: AGENT_FILE, fileName: 'guide.pdf', uploadedBy: AGENT_PERSON }),
      attachment({ id: INTERNAL_FILE, fileName: 'other.txt', uploadedBy: OTHER_AGENT_PERSON }),
    ];
    renderAt(URL_7);

    await link(/other\.txt/);
    const deletes = screen.queryAllByRole('button', { name: /지우기/ });
    expect(deletes, '자기가 올린 guide.pdf에만').toHaveLength(1);
    const guideRow = (await link(/guide\.pdf/)).closest('li');
    expect(guideRow && within(guideRow).queryByRole('button', { name: /지우기/ })).not.toBeNull();
  });

  test('C-4 첨부 지우기 버튼(관리자): 모든 첨부에', async () => {
    state.attachments = [
      attachment({ id: CUSTOMER_FILE, fileName: 'log.txt', uploadedBy: CUSTOMER_PERSON }),
      attachment({ id: AGENT_FILE, fileName: 'guide.pdf', uploadedBy: AGENT_PERSON }),
      attachment({ id: INTERNAL_FILE, fileName: 'mine.txt', uploadedBy: ADMIN_PERSON }),
    ];
    renderAt(URL_7);

    await link(/mine\.txt/);
    expect(screen.queryAllByRole('button', { name: /지우기/ })).toHaveLength(3);
  });

  test('C-4 첨부 지우기: 확인 대화상자를 거쳐 지우고, 다시 불러와 "지워진 첨부" 흔적으로 남는다', async () => {
    state.attachments = [attachment({ id: CUSTOMER_FILE, fileName: 'log.txt' })];
    const { user } = renderAt(URL_7);
    await link(/log\.txt/);
    await user.click(await button(/지우기/));

    const confirmBox = await dialog();
    expect(callsTo('DELETE', `/api/console/issues/ACME-7/attachments/${CUSTOMER_FILE}`)).toHaveLength(0);
    await user.click(within(confirmBox).getByRole('button', { name: /지우기/ }));

    await sent('DELETE', `/api/console/issues/ACME-7/attachments/${CUSTOMER_FILE}`);
    await eventually(() => {
      expect(pageText()).toContain('지워진 첨부');
      expect(screen.queryByRole('link', { name: /log\.txt/ })).toBeNull();
    });
  });

  test('C-4 첨부 올리는 중: 고르면 바로 올리고 파일 줄에 진행 막대, 끝나면 첨부 목록에 보인다', async () => {
    const release = gate();
    on('post', '/api/console/issues/:key/attachments', async ({ request }) => {
      await release.opened;
      return acceptUpload(request);
    });
    const { user } = renderAt(URL_7);
    await titleShown();
    await user.upload(await found(uploadInput, '첨부 올리기 파일 칸'), file('trace.log'));

    await sent('POST', '/api/console/issues/ACME-7/attachments');
    await found(() => screen.queryAllByRole('progressbar')[0], '파일 줄의 진행 막대');
    release.open();
    await link(/trace\.log/);
  });

  test('C-4 첨부 413: 그 파일 줄에 "파일이 서버 앞단의 크기 상한에 걸렸습니다", "다시 올리기"·"빼기"', async () => {
    on('post', '/api/console/issues/:key/attachments', () => errors.payloadTooLarge());
    const { user } = renderAt(URL_7);
    await titleShown();
    await user.upload(await found(uploadInput, '첨부 올리기 파일 칸'), file('dump.bin', 9 * 1024 * 1024));

    await eventually(() => {
      expect(pageText()).toContain('파일이 서버 앞단의 크기 상한에 걸렸습니다');
    });
    expect(pageText()).toContain('dump.bin');
    await button('다시 올리기');
    await button('빼기');
  });

  test('C-4 내려받기 404: HEAD가 404면 "파일을 찾을 수 없습니다" 띠', async () => {
    state.attachments = [attachment({ id: CUSTOMER_FILE, fileName: 'log.txt' })];
    on('head', '/api/console/issues/:key/attachments/:id', () => new HttpResponse(null, { status: 404 }));
    const { user } = renderAt(URL_7);
    await user.click(await link(/log\.txt/));

    await sent('HEAD', `/api/console/issues/ACME-7/attachments/${CUSTOMER_FILE}`);
    await alertWith('파일을 찾을 수 없습니다');
  });

  test('C-4 404: 배정에서 빠진 상담원이 열어도 "찾을 수 없습니다"이고 권한 문구를 쓰지 않는다(PROJ-4)', async () => {
    state.me = AGENT;
    on('get', '/api/console/issues/:key', () => errors.notFound());
    renderAt(URL_7);

    await eventually(() => {
      expect(pageText()).toContain('찾을 수 없습니다');
    });
    expect(pageText()).not.toContain('권한');
    const toList = screen.queryAllByRole('link').filter((a) => hrefPath(a) === '/issues' && !a.closest('nav'));
    expect(toList.length, '목록으로 가는 링크').toBeGreaterThan(0);
    expect(anySend()).toBeNull();
  });

  test('C-4 마크다운 보기 안의 링크는 새 탭, rel="noopener noreferrer"(COM-6)', async () => {
    state.issue = issue({ content: '[설치 안내](https://example.com/help) 를 따라 했습니다.' });
    renderAt(URL_7);

    const a = await link('설치 안내');
    expect(a).toHaveAttribute('target', '_blank');
    expect((a.getAttribute('rel') ?? '').split(/\s+/)).toEqual(expect.arrayContaining(['noopener', 'noreferrer']));
  });

  test('C-4 접근성: 처음 포커스는 h1(tabindex=-1), 화면 제목을 document.title에', async () => {
    renderAt(URL_7);
    await titleShown();

    const h1 = await heading1();
    expect(h1).toHaveAttribute('tabindex', '-1');
    await eventually(() => {
      expect(document.activeElement).toBe(h1);
    });
    expect(document.title).toContain(text(h1));
  });

  test('C-4 axe: 공개·내부 댓글과 첨부가 있는 화면에 접근성 위반이 없다', async () => {
    state.comments = [
      comment({ id: 1 }),
      comment({ id: 2, author: AGENT_PERSON, content: '내부 확인', internal: true }),
      comment({ id: 3, author: AGENT_PERSON, content: '답변드립니다' }),
    ];
    state.attachments = [
      attachment({ id: CUSTOMER_FILE }),
      attachment({ id: INTERNAL_FILE, fileName: 'heap.hprof', commentId: 2, internal: true, uploadedBy: AGENT_PERSON }),
    ];
    renderAt(URL_7);
    await titleShown();
    await link(/heap\.hprof/);
    await editor();

    await expectNoAxeViolations();
  });
});
