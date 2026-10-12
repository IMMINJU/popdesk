// 4번 묶음 screens.md 2절 C-4 댓글 구역: 댓글의 버튼(S4-AC-60), 고침·멘션·지운 댓글의 표시(S4-AC-61~63),
// 댓글 고치기(S4-AC-61), 댓글 지우기(S4-AC-62), 주소의 #comment-ID(S4-AC-66). "알릴 사람"은 C4IssueDetail.mentions.test.tsx.
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  ADMIN,
  ADMIN_PERSON,
  AGENT,
  AGENT_PERSON,
  CUSTOMER_PERSON,
  OTHER_AGENT_PERSON,
  alertWith,
  attachment,
  callsTo,
  comment,
  describedText,
  dialog,
  draftKept,
  errors,
  eventually,
  expectFieldError,
  expectNoAxeViolations,
  found,
  gate,
  heading1,
  isBlocked,
  link,
  openDialog,
  pageText,
  renderAt,
  screen,
  sent,
  state,
  text,
  within,
  type Comment,
} from '../__tests__/console';
import { closed, rejected, route, settle } from '../__tests__/console1a';
import { expectNotSaid, said } from '../__tests__/console2';
import { issueIn } from '../__tests__/console3';
import {
  COMMENTS_7,
  DELETE,
  DELETED_AT,
  EDIT,
  EDITED_AT,
  buttonIn,
  commentAction,
  commentActions,
  commentById,
  commentWith,
  deletedComment,
  editBox,
  editForm,
  editFormShown,
  focused,
  writeForm,
  type UpdateCommentRequest,
} from '../__tests__/console4';

const URL_7 = '/issues/ACME-7';
const ISSUE_7 = '/api/console/issues/ACME-7';
const ATTACHMENTS_7 = `${ISSUE_7}/attachments`;
const SERVER_DOWN = '서버에 연결하지 못했습니다. 잠시 뒤 다시 하세요.';
const FORBIDDEN = '이 작업을 할 권한이 없습니다.';
const SAVE = '고친 댓글 저장';
const CANCEL = '고치기 취소';
const KEEP = '그만두기';

type User = ReturnType<typeof renderAt>['user'];

const FROM_CUSTOMER = comment({ id: 4, author: CUSTOMER_PERSON, content: '확인 부탁드립니다.', createdAt: '2026-10-08T03:20:00Z' });
const MINE = comment({ id: 5, author: AGENT_PERSON, content: '확인하고 있습니다', createdAt: '2026-10-08T03:30:00Z' });
const FROM_OTHER = comment({ id: 6, author: OTHER_AGENT_PERSON, content: '제가 이어서 봅니다', createdAt: '2026-10-08T03:40:00Z' });
const MINE_TOO = comment({ id: 7, author: AGENT_PERSON, content: '재현했습니다', createdAt: '2026-10-08T03:50:00Z' });

const patches = () => callsTo('PATCH', /\/comments\/\d+$/);
const deletes = () => callsTo('DELETE', /\/comments\/\d+$/);
const commentReads = () => callsTo('GET', COMMENTS_7).length;
const timeAt = (root: Element, at: string) => Array.from(root.querySelectorAll('time')).some((t) => t.getAttribute('datetime') === at);

/** Signs in as 이상담 on an open issue with `comments`. */
function asAgent(comments: Comment[]) {
  state.me = AGENT;
  state.issue = issueIn('IN_PROGRESS');
  state.comments = comments;
}

/** Presses "댓글 고치기" of the comment holding `part` and returns its editor. */
async function openEdit(user: User, part: string) {
  await user.click(await commentAction(part, EDIT));
  return editBox();
}

async function retype(user: User, box: HTMLTextAreaElement, words: string) {
  await user.clear(box);
  await user.type(box, words);
}

/** Presses "댓글 지우기" of the comment holding `part` and returns the question. */
async function openDelete(user: User, part: string) {
  await user.click(await commentAction(part, DELETE));
  return found(() => screen.queryByRole('alertdialog', { name: DELETE }) ?? screen.queryByRole('dialog', { name: DELETE }), `대화상자 "${DELETE}"`);
}

const confirmDelete = (box: HTMLElement) => within(box).getByRole('button', { name: /^(댓글 지우기|지우는 중…)$/ });

describe('C-4 댓글의 버튼과 표시 (4번 묶음)', () => {
  test.each([
    { who: '상담원: 내 댓글에 둘 다, 남의 댓글에 없음', me: AGENT, mine: AGENT_PERSON, status: 'IN_PROGRESS' as const, onMine: [EDIT, DELETE], onOthers: [] },
    { who: '관리자: 내 댓글에 둘 다, 남의 댓글에 지우기만', me: ADMIN, mine: ADMIN_PERSON, status: 'IN_PROGRESS' as const, onMine: [EDIT, DELETE], onOthers: [DELETE] },
    { who: 'CLOSED 문의의 상담원: 내 댓글에 지우기만, 남의 댓글에 없음', me: AGENT, mine: AGENT_PERSON, status: 'CLOSED' as const, onMine: [DELETE], onOthers: [] },
    { who: 'CLOSED 문의의 관리자: 어느 댓글에나 지우기만', me: ADMIN, mine: ADMIN_PERSON, status: 'CLOSED' as const, onMine: [DELETE], onOthers: [DELETE] },
  ])('S4-AC-60 C-4 댓글의 버튼($who). 지운 댓글에는 없다. 버튼은 aria-describedby로 그 댓글에 이어진다', async ({ me, mine, status, onMine, onOthers }) => {
    state.me = me;
    state.issue = issueIn(status);
    state.comments = [
      FROM_CUSTOMER,
      comment({ ...MINE, author: mine }),
      FROM_OTHER,
      deletedComment({ id: 8, author: mine, deletedBy: mine, createdAt: '2026-10-08T03:55:00Z' }),
    ];
    renderAt(URL_7);

    const own = await commentWith(MINE.content);
    await eventually(() => {
      expect(commentActions(own), '내 댓글').toEqual(onMine);
    });
    expect(commentActions(await commentWith(FROM_OTHER.content)), '다른 상담원의 댓글').toEqual(onOthers);
    expect(commentActions(await commentWith(FROM_CUSTOMER.content)), '고객의 댓글').toEqual(onOthers);
    expect(commentActions(await commentWith('지워진 댓글')), '지운 댓글').toEqual([]);

    for (const name of onMine) {
      const button = within(own).getByRole('button', { name });
      expect(describedText(button), `"${name}"의 설명`).toContain(mine.name);
      const described = (button.getAttribute('aria-describedby') ?? '').split(/\s+/).map((id) => document.getElementById(id));
      expect(described.every((el) => el !== null && own.contains(el)), '설명이 그 댓글 안에 있다').toBe(true);
      expect(described.some((el) => el !== null && timeAt(el, MINE.createdAt)), '설명에 쓴 시각').toBe(true);
    }
    // 0절: the comment part holds comments only, and no editor is drawn until one is edited.
    expect(editForm()).toBeNull();
    expect(own.querySelectorAll('article')).toHaveLength(0);
  });

  test('S4-AC-61 S4-AC-62 S4-AC-63 C-4 댓글의 표시: 고친 댓글에 "고침"과 고친 시각, 멘션이 있으면 "알림: 이름들", 지운 댓글은 "지워진 댓글"과 지운 사람·시각. 지운 댓글에는 고침과 멘션이 없고 "댓글 N개"는 지운 것도 센다', async () => {
    const editedOnce = '2026-10-09T09:09:09Z';
    asAgent([
      FROM_CUSTOMER,
      comment({ ...MINE, editedAt: EDITED_AT, mentions: [OTHER_AGENT_PERSON, ADMIN_PERSON] }),
      deletedComment({ id: 6, author: OTHER_AGENT_PERSON, createdAt: '2026-10-08T03:40:00Z', editedAt: editedOnce, mentions: [ADMIN_PERSON] }),
    ]);
    renderAt(URL_7);

    const edited = await commentWith(MINE.content);
    await eventually(() => {
      expect(text(edited)).toContain('고침');
    });
    expect(timeAt(edited, EDITED_AT), '고친 시각 <time datetime>').toBe(true);
    expect(text(edited)).toContain('알림: 정상담, 최관리');
    expect(edited.querySelectorAll('img'), '멘션에 사진을 그리지 않는다').toHaveLength(0);

    const plain = await commentWith(FROM_CUSTOMER.content);
    expect(text(plain)).not.toContain('고침');
    expect(text(plain)).not.toContain('알림:');

    const gone = await commentWith('지워진 댓글');
    expect(text(gone)).toContain('최관리 님이 지움');
    expect(timeAt(gone, DELETED_AT), '지운 시각 <time datetime>').toBe(true);
    expect(text(gone)).toContain('정상담');
    expect(text(gone)).not.toContain('고침');
    expect(timeAt(gone, editedOnce)).toBe(false);
    expect(text(gone)).not.toContain('알림:');

    const part = await found(() => screen.queryByRole('region', { name: '댓글 3개' }), '구역 "댓글 3개"');
    expect(within(part).queryAllByRole('article')).toHaveLength(3);
  });
});

describe('C-4 댓글 고치기 (4번 묶음)', () => {
  test('S4-AC-61 C-4 댓글 고치기를 누름: 그 댓글 자리에 이름 "댓글 고치기"인 form이 생기고 편집기 "댓글 내용 고치기"에 지금 내용이 들어 포커스를 받는다. 고치는 동안 그 댓글의 동작 버튼이 없다', async () => {
    asAgent([FROM_CUSTOMER, MINE, MINE_TOO]);
    const { user } = renderAt(URL_7);
    const own = await commentWith(MINE.content);
    const other = await commentWith(MINE_TOO.content);

    const box = await openEdit(user, MINE.content);

    expect(box).toHaveValue(MINE.content);
    await focused(() => box, '편집기');
    const form = await editFormShown();
    expect(own.contains(form), '그 댓글 자리').toBe(true);
    expect(own.querySelectorAll('article')).toHaveLength(0);
    await buttonIn(editForm, SAVE);
    await buttonIn(editForm, CANCEL);
    expect(commentActions(own), '고치는 댓글의 동작 버튼').toEqual([]);
    expect(commentActions(other), '다른 댓글의 동작 버튼').toEqual([EDIT, DELETE]);
    // The editor's tabs and tools have names of their own, beside those of the new comment's editor (screens.md 4절).
    expect(within(form).queryByRole('tab', { name: '고칠 내용 쓰기' }), '탭 "고칠 내용 쓰기"').not.toBeNull();
    expect(within(form).queryByRole('tab', { name: '고친 내용 미리 보기' }), '탭 "고친 내용 미리 보기"').not.toBeNull();
    expect(within(form).queryByRole('toolbar', { name: '고치기 서식' }), '도구 묶음 "고치기 서식"').not.toBeNull();
    expect(screen.queryAllByRole('tab', { name: '쓰기' }), '새 댓글 편집기의 "쓰기"').toHaveLength(1);
    expect(await writeForm()).not.toBe(form);
    expect(screen.queryAllByRole('form', { name: EDIT })).toHaveLength(1);
    expect(patches()).toHaveLength(0);
  });

  test('S4-AC-61 C-4 댓글 고치기 저장: 내용만 바꾸면 PATCH에 content만 보낸다. 보내는 중 버튼이 막히고, 성공하면 닫히고 목록을 다시 받으며 "고침"이 보이고 토스트 "댓글을 고쳤습니다", 포커스는 그 댓글', async () => {
    asAgent([FROM_CUSTOMER, MINE]);
    const release = gate();
    route('patch', `${COMMENTS_7}/:id`, async ({ request }) => {
      const body = (await request.json()) as UpdateCommentRequest;
      await release.opened;
      const after = { ...MINE, content: body.content ?? MINE.content, editedAt: EDITED_AT };
      state.comments = state.comments.map((c) => (c.id === MINE.id ? after : c));
      return HttpResponse.json(after);
    });
    const { user } = renderAt(URL_7);
    await retype(user, await openEdit(user, MINE.content), '재설정 링크를 보냈습니다');
    const reads = commentReads();
    const save = await buttonIn(editForm, SAVE);
    await user.click(save);

    const [patch] = await sent('PATCH', `${COMMENTS_7}/5`);
    expect(await patch?.body).toEqual({ content: '재설정 링크를 보냈습니다' });
    await eventually(() => {
      const form = editForm();
      const pending = form && within(form).queryByRole('button', { name: /고친 댓글 저장|보내는 중…/ });
      expect(pending !== null && isBlocked(pending), '보내는 중 버튼 막힘').toBe(true);
    });
    await user.click(save);
    expect(patches()).toHaveLength(1);
    release.open();

    await said('댓글을 고쳤습니다');
    await eventually(() => {
      expect(editForm(), '편집기가 닫힘').toBeNull();
      expect(commentReads(), '댓글 목록을 다시 받음').toBeGreaterThan(reads);
    });
    const after = await commentWith('재설정 링크를 보냈습니다');
    expect(text(after)).toContain('고침');
    expect(timeAt(after, EDITED_AT)).toBe(true);
    await focused(() => commentById(MINE.id), '고친 댓글');
    expect(commentActions(after)).toEqual([EDIT, DELETE]);
  });

  test('S4-AC-61 C-4 댓글 고치기: 내용이 그대로인 채 "고친 댓글 저장"을 누르면 요청 없이 편집기를 닫는다', async () => {
    asAgent([MINE]);
    const { user } = renderAt(URL_7);
    await openEdit(user, MINE.content);

    await user.click(await buttonIn(editForm, SAVE));

    await eventually(() => {
      expect(editForm()).toBeNull();
    });
    await settle();
    expect(patches()).toHaveLength(0);
    expect(commentActions(await commentWith(MINE.content))).toEqual([EDIT, DELETE]);
    expectNotSaid('댓글을 고쳤습니다');
  });

  test('S4-AC-61 C-4 댓글 고치기 400 칸 오류: 편집기 아래에 문구가 보이고 입력은 그대로다', async () => {
    asAgent([MINE]);
    route('patch', `${COMMENTS_7}/:id`, () => errors.invalid([{ field: 'content', code: 'TOO_LONG' }]));
    const { user } = renderAt(URL_7);
    const box = await openEdit(user, MINE.content);
    await retype(user, box, '너무 긴 답변');

    await user.click(await buttonIn(editForm, SAVE));

    await expectFieldError(await editBox());
    expect(await editBox()).toHaveValue('너무 긴 답변');
    expect(editForm(), '편집기는 열려 있다').not.toBeNull();
  });

  test.each([
    {
      what: '400 ISSUE_CLOSED',
      answer: () => {
        state.issue = issueIn('CLOSED');
        return errors.issueClosed();
      },
      says: '문의가 종료되어 댓글을 고칠 수 없습니다.',
      reads: ISSUE_7,
    },
    {
      what: '400 COMMENT_DELETED',
      answer: () => {
        state.comments = [deletedComment({ ...MINE, content: '', deletedBy: ADMIN_PERSON })];
        return rejected('COMMENT_DELETED');
      },
      says: '이 댓글은 지워졌습니다.',
      reads: COMMENTS_7,
    },
    {
      what: '404',
      answer: () => {
        state.comments = [deletedComment({ ...MINE, content: '', deletedBy: ADMIN_PERSON })];
        return errors.notFound();
      },
      says: '이 댓글은 지워졌습니다.',
      reads: COMMENTS_7,
    },
  ])('S4-AC-61 C-4 댓글 고치기 $what: 오류 띠 "$says"와 함께 다시 받고, 입력은 "보내지 못한 글"로 남긴다', async ({ answer, says, reads }) => {
    asAgent([MINE]);
    route('patch', `${COMMENTS_7}/:id`, answer);
    const { user } = renderAt(URL_7);
    await retype(user, await openEdit(user, MINE.content), '닫히기 직전에 고친 글');
    const before = callsTo('GET', reads).length;

    await user.click(await buttonIn(editForm, SAVE));

    await alertWith(says);
    await eventually(() => {
      expect(callsTo('GET', reads).length, '다시 받음').toBeGreaterThan(before);
      expect(pageText()).toContain('보내지 못한 글');
    });
    expect(draftKept('닫히기 직전에 고친 글')).toBe(true);
    expectNotSaid('댓글을 고쳤습니다');
  });

  test.each([
    { what: '403', answer: () => errors.forbidden(), says: FORBIDDEN },
    { what: '429', answer: () => errors.rateLimited(30), says: /요청이 많습니다\. \d+초 뒤 다시 하세요\./ },
  ])('S4-AC-61 C-4 댓글 고치기 $what: 띠로 알리고 입력은 편집기에 그대로다', async ({ answer, says }) => {
    asAgent([MINE]);
    route('patch', `${COMMENTS_7}/:id`, answer);
    const { user } = renderAt(URL_7);
    await retype(user, await openEdit(user, MINE.content), '다시 보낼 글');

    await user.click(await buttonIn(editForm, SAVE));

    await alertWith(says);
    expect(await editBox()).toHaveValue('다시 보낼 글');
    expect(patches()).toHaveLength(1);
  });

  test('S4-AC-61 C-4 고치기 취소와 떠나기: 바뀐 것이 없으면 묻지 않고 닫고 포커스가 "댓글 고치기"로 간다. 바뀌었으면 취소와 떠나기 모두 먼저 묻고, 머무르면 글이 남는다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    state.comments = [comment({ ...MINE, author: ADMIN_PERSON })];
    const { user } = renderAt(URL_7);
    const own = await commentWith(MINE.content);
    await openEdit(user, MINE.content);

    await user.click(await buttonIn(editForm, CANCEL));
    await eventually(() => {
      expect(editForm()).toBeNull();
    });
    expect(openDialog(), '바뀐 것이 없으면 묻지 않는다').toBeNull();
    await focused(() => within(own).queryByRole('button', { name: EDIT }), `"${EDIT}" 버튼`);

    await user.type(await openEdit(user, MINE.content), ' (쓰는 중)');
    await user.click(await buttonIn(editForm, CANCEL));
    await dialog();
    await user.keyboard('{Escape}');
    await closed();
    expect(await editBox(), '취소를 물은 뒤').toHaveValue(`${MINE.content} (쓰는 중)`);

    await user.click(await link('고객사'));
    await dialog();
    expect(window.location.pathname).toBe(URL_7);
    await user.keyboard('{Escape}');
    await closed();
    expect(await editBox(), '떠나기를 물은 뒤').toHaveValue(`${MINE.content} (쓰는 중)`);
    expect(patches()).toHaveLength(0);
  });

  test('S4-AC-61 C-4 다른 댓글의 "댓글 고치기": 쓰던 것이 없으면 바로 그 댓글로 옮기고, 쓰던 것이 있으면 먼저 묻는다. 한 번에 한 댓글만 고친다', async () => {
    asAgent([MINE, MINE_TOO]);
    const { user } = renderAt(URL_7);
    const first = await commentWith(MINE.content);
    const second = await commentWith(MINE_TOO.content);
    await openEdit(user, MINE.content);

    await user.click(within(second).getByRole('button', { name: EDIT }));
    await eventually(() => {
      const form = editForm();
      expect(form !== null && second.contains(form), '둘째 댓글의 편집기').toBe(true);
    });
    expect(openDialog()).toBeNull();
    expect(await editBox()).toHaveValue(MINE_TOO.content);
    expect(screen.queryAllByRole('form', { name: EDIT })).toHaveLength(1);
    expect(commentActions(first), '첫 댓글의 버튼이 돌아온다').toEqual([EDIT, DELETE]);

    await user.type(await editBox(), ' 추가');
    await user.click(within(first).getByRole('button', { name: EDIT }));
    await dialog();
    await user.keyboard('{Escape}');
    await closed();
    expect(await editBox(), '물은 뒤').toHaveValue(`${MINE_TOO.content} 추가`);
    expect(second.contains(editForm()), '여전히 둘째 댓글').toBe(true);
    expect(patches()).toHaveLength(0);
  });

  test('S4-AC-69 C-4 떠난 뒤 온 답(댓글 고치기): 저장 중에 화면을 떠난 뒤 온 성공은 토스트를 띄우지 않고 주소를 바꾸지 않는다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    state.comments = [comment({ ...MINE, author: ADMIN_PERSON })];
    const release = gate();
    let late = false;
    route('patch', `${COMMENTS_7}/:id`, async () => {
      await release.opened;
      late = true;
      return HttpResponse.json({ ...MINE, author: ADMIN_PERSON, content: '늦게 저장된 글', editedAt: EDITED_AT });
    });
    const { user } = renderAt(URL_7);
    await retype(user, await openEdit(user, MINE.content), '늦게 저장된 글');
    await user.click(await buttonIn(editForm, SAVE));
    await sent('PATCH', `${COMMENTS_7}/5`);

    await user.click(await link('시스템 상태'));
    // A screen that still holds the text asks first; either way the user leaves.
    const asked = openDialog();
    if (asked) await user.click(within(asked).getByRole('button', { name: '떠나기' }));
    await found(() => screen.queryByRole('heading', { level: 1, name: '시스템 상태' }), '시스템 상태 화면');

    release.open();
    await eventually(() => {
      expect(late).toBe(true);
    });
    await settle();
    expectNotSaid('댓글을 고쳤습니다');
    expect(window.location.pathname).toBe('/system');
  });
});

describe('C-4 댓글 지우기 (4번 묶음)', () => {
  test.each([
    { what: '첨부가 없는 댓글', files: [], second: false },
    { what: '첨부가 있는 댓글', files: [attachment({ commentId: MINE.id, uploadedBy: AGENT_PERSON })], second: true },
  ])('S4-AC-62 C-4 댓글 지우기를 누름($what): 대화상자 "댓글 지우기"가 되돌릴 수 없다고 묻는다. "그만두기"는 요청 없이 닫는다', async ({ files, second }) => {
    asAgent([FROM_CUSTOMER, MINE]);
    state.attachments = files;
    const { user } = renderAt(URL_7);
    const own = await commentWith(MINE.content);

    const box = await openDelete(user, MINE.content);

    expect(text(box)).toContain('지운 댓글은 되돌릴 수 없습니다.');
    expect(text(box).includes('이 댓글에 붙은 파일도 함께 지웁니다.'), '둘째 문장').toBe(second);
    expect(within(box).queryAllByRole('button').map((b) => text(b)).sort()).toEqual([KEEP, DELETE].sort());
    expect(deletes()).toHaveLength(0);

    await user.click(within(box).getByRole('button', { name: KEEP }));
    await closed();
    expect(deletes()).toHaveLength(0);
    expect(text(own)).toContain(MINE.content);
    expect(commentActions(own)).toEqual([EDIT, DELETE]);
  });

  test('S4-AC-62 C-4 댓글 지우기 성공: DELETE를 보내고(보내는 중 "지우는 중…") 닫은 뒤 댓글과 첨부 목록을 다시 받는다. 그 자리에 "지워진 댓글"과 지운 사람, 토스트 "댓글을 지웠습니다", 포커스는 그 댓글', async () => {
    asAgent([FROM_CUSTOMER, MINE]);
    state.attachments = [attachment({ commentId: MINE.id, fileName: 'capture.png', uploadedBy: AGENT_PERSON })];
    const release = gate();
    route('delete', `${COMMENTS_7}/:id`, async () => {
      await release.opened;
      state.comments = state.comments.map((c) => (c.id === MINE.id ? deletedComment({ ...MINE, content: '', deletedBy: AGENT_PERSON }) : c));
      state.attachments = state.attachments.map((a) => ({ ...a, deleted: true, fileName: null, deletedAt: DELETED_AT, deletedBy: AGENT_PERSON }));
      return new HttpResponse(null, { status: 204 });
    });
    const { user } = renderAt(URL_7);
    await link(/capture\.png/);
    const box = await openDelete(user, MINE.content);
    const reads = commentReads();
    const fileReads = callsTo('GET', ATTACHMENTS_7).length;

    await user.click(confirmDelete(box));

    await sent('DELETE', `${COMMENTS_7}/5`);
    await found(() => within(box).queryByRole('button', { name: /지우는 중…/ }), '"지우는 중…"');
    expect(isBlocked(confirmDelete(box))).toBe(true);
    release.open();

    await closed();
    await said('댓글을 지웠습니다');
    await eventually(() => {
      expect(commentReads(), '댓글 목록을 다시 받음').toBeGreaterThan(reads);
      expect(callsTo('GET', ATTACHMENTS_7).length, '첨부 목록을 다시 받음').toBeGreaterThan(fileReads);
    });
    const gone = await commentWith('지워진 댓글');
    expect(gone).toBe(commentById(MINE.id));
    expect(text(gone)).toContain('이상담 님이 지움');
    expect(text(gone)).not.toContain(MINE.content);
    expect(commentActions(gone)).toEqual([]);
    await focused(() => commentById(MINE.id), '지운 댓글');
    await eventually(() => {
      expect(screen.queryByRole('link', { name: /capture\.png/ }), '댓글의 첨부').toBeNull();
    });
    expect(screen.queryByRole('heading', { name: '댓글 2개' }), '"댓글 N개"는 지운 것도 센다').not.toBeNull();
    expect(deletes()).toHaveLength(1);
  });

  test.each([
    { what: '403', answer: () => errors.forbidden(), says: FORBIDDEN, again: false },
    { what: '5xx', answer: () => errors.internal(), says: SERVER_DOWN, again: true },
  ])('S4-AC-62 C-4 댓글 지우기 $what: 대화상자 안 오류 띠로 알리고 대화상자는 열려 있다. 5xx는 다시 누를 수 있다', async ({ answer, says, again }) => {
    asAgent([MINE]);
    route('delete', `${COMMENTS_7}/:id`, answer);
    const { user } = renderAt(URL_7);
    const box = await openDelete(user, MINE.content);

    await user.click(confirmDelete(box));

    await found(() => within(box).queryAllByRole('alert').find((a) => text(a).includes(says)), `대화상자 안의 띠 "${says}"`);
    expect(openDialog(), '대화상자는 열려 있다').not.toBeNull();
    expect(text(await commentWith(MINE.content))).not.toContain('지워진 댓글');
    expectNotSaid('댓글을 지웠습니다');
    if (again) {
      await eventually(() => {
        expect(isBlocked(confirmDelete(box)), '다시 누를 수 있다').toBe(false);
      });
      await user.click(confirmDelete(box));
      await sent('DELETE', `${COMMENTS_7}/5`, 2);
    }
  });

  test('S4-AC-62 C-4 댓글 지우기 404: 대화상자를 닫고 댓글 목록을 다시 받는다', async () => {
    asAgent([FROM_CUSTOMER, MINE]);
    route('delete', `${COMMENTS_7}/:id`, () => {
      // An admin deleted it first, and the issue's comments say so now.
      state.comments = state.comments.map((c) => (c.id === MINE.id ? deletedComment({ ...MINE, content: '', deletedBy: ADMIN_PERSON }) : c));
      return errors.notFound();
    });
    const { user } = renderAt(URL_7);
    const box = await openDelete(user, MINE.content);
    const reads = commentReads();

    await user.click(confirmDelete(box));

    await closed();
    await eventually(() => {
      expect(commentReads()).toBeGreaterThan(reads);
    });
    expect(text(await commentWith('지워진 댓글'))).toContain('최관리 님이 지움');
    expectNotSaid('댓글을 지웠습니다');
  });

  test('S4-AC-69 C-4 닫힌 뒤 온 답(댓글 지우기): 지우는 중에 닫은 뒤 온 실패는 버린다. 다른 댓글로 다시 열면 오류 없이 처음부터 묻는다', async () => {
    asAgent([MINE, MINE_TOO]);
    const release = gate();
    let late = false;
    route('delete', `${COMMENTS_7}/:id`, async () => {
      await release.opened;
      late = true;
      return errors.internal();
    });
    const { user } = renderAt(URL_7);
    const first = await openDelete(user, MINE.content);
    await user.click(confirmDelete(first));
    await sent('DELETE', `${COMMENTS_7}/5`);
    await user.keyboard('{Escape}');
    await closed();

    release.open();
    await eventually(() => {
      expect(late).toBe(true);
    });
    await settle();
    expect(openDialog(), '늦은 답이 대화상자를 다시 열지 않는다').toBeNull();

    const second = await openDelete(user, MINE_TOO.content);
    expect(within(second).queryAllByRole('alert'), '앞 요청의 오류').toHaveLength(0);
    expect(isBlocked(confirmDelete(second))).toBe(false);
    expect(deletes()).toHaveLength(1);
  });
});

describe('C-4 주소의 #comment-ID (4번 묶음)', () => {
  test('S4-AC-66 C-4 #comment-ID: 받은 댓글에 없는 ID면 포커스가 화면 제목 그대로이고, 있는 ID면 댓글을 받은 뒤 그 댓글이 포커스를 받는다', async () => {
    asAgent([FROM_CUSTOMER, comment({ ...MINE, id: 12 }), FROM_OTHER]);
    const missing = renderAt(`${URL_7}#comment-999`);
    await commentWith(FROM_OTHER.content);
    await settle();
    expect(document.activeElement).toBe(await heading1());
    missing.unmount();

    renderAt(`${URL_7}#comment-12`);
    await commentWith(MINE.content);
    await focused(() => commentById(12), '댓글 12');
    expect(commentById(12)).toHaveAttribute('tabindex', '-1');
    expect(text(commentById(12))).toContain(MINE.content);
  });
});

describe('C-4 axe (4번 묶음)', () => {
  test('C-4 axe (댓글): 버튼, 고침, 멘션, 지운 댓글이 있는 화면과 고치는 화면, 지우기 대화상자에 접근성 위반이 없다', async () => {
    asAgent([
      FROM_CUSTOMER,
      comment({ ...MINE, editedAt: EDITED_AT, mentions: [OTHER_AGENT_PERSON] }),
      deletedComment({ id: 6, author: OTHER_AGENT_PERSON, createdAt: '2026-10-08T03:40:00Z' }),
      comment({ ...MINE_TOO, internal: true }),
    ]);
    const { user } = renderAt(URL_7);
    await commentAction(MINE.content, EDIT);
    await expectNoAxeViolations();

    await openEdit(user, MINE.content);
    await expectNoAxeViolations();

    await openDelete(user, MINE_TOO.content);
    await expectNoAxeViolations();
  });
});
