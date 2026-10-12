// 4번 묶음 screens.md 2절 C-4 "알릴 사람": 댓글 쓰기 폼과 댓글 고치기 폼의 멘션 고르기(S4-AC-63, 69).
// 후보는 처음 펼칠 때 받고, 공개 답변과 내부 메모를 바꾸면 다시 받는다. 아무도 고르지 않으면 요청 본문에 mentions가 없다.
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  ADMIN_PERSON,
  AGENT,
  AGENT_PERSON,
  CUSTOMER_PERSON,
  OTHER_AGENT_PERSON,
  callsTo,
  comment,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  isBlocked,
  on,
  renderAt,
  screen,
  sent,
  state,
  within,
  type Person,
} from '../__tests__/console';
import { settle } from '../__tests__/console1a';
import { said } from '../__tests__/console2';
import { issueIn } from '../__tests__/console3';
import {
  CANDIDATES_7,
  COMMENTS_7,
  EDIT,
  buttonIn,
  candidates,
  commentAction,
  commentWith,
  editBox,
  editForm,
  editFormShown,
  mentionsOf,
  people,
  s4,
  writeBox,
} from '../__tests__/console4';

const URL_7 = '/issues/ACME-7';
const NONE = '알릴 사람 없음';
const LIMIT = '10명까지 고를 수 있습니다';
const UNKNOWN = '지금 알릴 수 없는 사람이 있습니다. 다시 골라 주세요.';
const DROPPED = '내부 메모에서는 고객에게 알릴 수 없어 김고객 님을 뺐습니다.';

type User = ReturnType<typeof renderAt>['user'];

const newForm = () => screen.queryByRole('form', { name: '댓글 쓰기' });
const picker = people(newForm);
const asked = () => callsTo('GET', CANDIDATES_7);
const askedFor = () => asked().map((c) => new URL(c.url).searchParams.get('internal') ?? 'false');
const posts = () => callsTo('POST', COMMENTS_7);
const sendPublic = () => buttonIn(newForm, '고객에게 답변 보내기');
const sendInternal = () => buttonIn(newForm, '내부 메모 남기기');
const internalTab = () => found(() => screen.queryByRole('radio', { name: '내부 메모' }), '"내부 메모" 라디오');
const summary = () => picker.summary();
/** The lines of the candidates, sorted as `lines` gives them. */
const PUBLIC_LINES = ['김고객 (올린 고객)', '정상담 (상담원)', '최관리 (관리자)'];
const INTERNAL_LINES = ['정상담 (상담원)', '최관리 (관리자)'];

/** Signs in as 이상담 on an open issue 김고객 filed. */
function asAgent() {
  state.me = AGENT;
  state.issue = issueIn('IN_PROGRESS');
}

/** Opens the picker of the new comment and waits for its boxes. */
async function openPicker(user: User) {
  await user.click(await picker.toggle());
  return picker.opened();
}

const agentNo = (n: number): Person => ({ accountId: 100 + n, name: `상담${String(n).padStart(2, '0')}`, role: 'AGENT', photoUpdatedAt: null });

describe('C-4 알릴 사람 (4번 묶음)', () => {
  test('S4-AC-63 C-4 알릴 사람(접힘과 펼침): 댓글 쓰기 폼에 "알릴 사람 고르기" 버튼과 "알릴 사람 없음"이 있다. 처음 펼칠 때 internal=false로 후보를 받아 "이름 (역할)" 줄로 보이고 올린 고객은 "이름 (올린 고객)"이다', async () => {
    asAgent();
    const { user } = renderAt(URL_7);

    const toggle = await picker.toggle();
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(summary()).toBe(NONE);
    expect(picker.group(), '접힌 동안 상자').toBeNull();
    await settle();
    expect(asked(), '펼치기 전에는 후보를 받지 않는다').toHaveLength(0);
    // Before the send button, inside the form.
    expect(toggle.compareDocumentPosition(await sendPublic()) & Node.DOCUMENT_POSITION_FOLLOWING, '보내기 버튼 위').toBeTruthy();

    await openPicker(user);

    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await eventually(() => {
      expect(picker.lines()).toEqual(PUBLIC_LINES);
    });
    expect(askedFor()).toEqual(['false']);
    expect(screen.queryByRole('radiogroup', { name: /알릴 사람/ }), 'radiogroup이 아니다').toBeNull();
    expect((picker.group() as HTMLElement).querySelectorAll('img'), '사진은 그리지 않는다').toHaveLength(0);

    // Folding and opening again does not ask again.
    await user.click(toggle);
    await user.click(toggle);
    await picker.opened();
    await settle();
    expect(asked()).toHaveLength(1);
  });

  test('S4-AC-63 C-4 알릴 사람을 고르고 보냄: 본문에 mentions(계정 ID 숫자의 목록)가 실린다. 보내면 고른 것이 비고 접히며, 아무도 고르지 않은 다음 댓글의 본문에는 mentions 키가 없다', async () => {
    asAgent();
    const { user } = renderAt(URL_7);
    await openPicker(user);
    await user.click(await picker.box('정상담'));
    await user.click(await picker.box('최관리'));
    await eventually(() => {
      expect(summary()).toMatch(/^알릴 사람: /);
    });
    expect(summary()).toContain('정상담');
    expect(summary()).toContain('최관리');
    expect(summary()).not.toContain('김고객');

    await user.type(await writeBox(), '두 분께 알립니다');
    await user.click(await sendPublic());

    const [first] = await sent('POST', COMMENTS_7);
    const body = (await first?.body) as { content: string; internal?: boolean };
    expect(body.content).toBe('두 분께 알립니다');
    expect(body.internal ?? false).toBe(false);
    expect(mentionsOf(body)).toEqual([1, 3]);
    await said('답변을 보냈습니다');
    await eventually(() => {
      expect(summary()).toBe(NONE);
    });
    expect(await picker.toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(picker.group()).toBeNull();
    // The comment shows who was told.
    expect((await commentWith('두 분께 알립니다')).textContent).toMatch(/알림: (최관리, 정상담|정상담, 최관리)/);

    await user.type(await writeBox(), '아무도 고르지 않은 답변');
    await user.click(await sendPublic());
    const [, second] = await sent('POST', COMMENTS_7, 2);
    const next = (await second?.body) as { content: string };
    expect(next.content).toBe('아무도 고르지 않은 답변');
    expect('mentions' in next, '본문의 mentions 키').toBe(false);
  });

  test('S4-AC-63 C-4 알릴 사람(내부 메모로 바꿈): internal=true로 후보를 다시 받고 올린 고객이 빠지며 한 줄로 알린다. 남은 사람만 mentions에 실린다', async () => {
    asAgent();
    const { user } = renderAt(URL_7);
    await openPicker(user);
    await user.click(await picker.box('김고객'));
    await user.click(await picker.box('정상담'));

    await user.click(await internalTab());

    await eventually(() => {
      expect(askedFor()).toEqual(['false', 'true']);
      expect(picker.lines()).toEqual(INTERNAL_LINES);
    });
    await eventually(() => {
      expect(picker.says()).toContain(DROPPED);
    });
    expect(await picker.box('정상담')).toBeChecked();
    expect(summary()).toContain('정상담');
    expect(summary()).not.toContain('김고객');

    await user.type(await writeBox(), '내부에서 먼저 봅니다');
    await user.click(await sendInternal());
    const [post] = await sent('POST', COMMENTS_7);
    expect(await post?.body).toEqual({ content: '내부에서 먼저 봅니다', internal: true, mentions: [3] });
    await said('내부 메모를 남겼습니다');
  });

  test('S4-AC-63 C-4 알릴 사람(접힌 채로 댓글 종류를 바꿈): 받아 둔 후보를 버리고, 다시 펼치면 internal=true로 후보를 다시 받아 올린 고객이 없다', async () => {
    asAgent();
    const { user } = renderAt(URL_7);
    await openPicker(user);
    await eventually(() => {
      expect(picker.lines()).toEqual(PUBLIC_LINES);
    });
    const toggle = await picker.toggle();
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');

    await user.click(await internalTab());
    await user.click(toggle);

    await picker.opened();
    await eventually(() => {
      expect(askedFor().at(-1), '펼칠 때의 후보 요청').toBe('true');
      expect(picker.lines()).toEqual(INTERNAL_LINES);
    });
    await settle();
    expect(picker.lines(), '공개 답변의 후보(올린 고객)가 남지 않는다').toEqual(INTERNAL_LINES);
    expect(askedFor().filter((kind) => kind === 'true'), '내부 메모의 후보는 한 번 받는다').toHaveLength(1);
    expect(picker.says()).not.toContain('김고객');
  });

  test('S4-AC-63 C-4 알릴 사람 10명: 10명을 고르면 나머지 상자가 막히고 "10명까지 고를 수 있습니다"가 보인다. 하나를 빼면 풀린다', async () => {
    asAgent();
    s4.candidates.public = Array.from({ length: 12 }, (_, i) => agentNo(i + 1));
    const { user } = renderAt(URL_7);
    const group = await openPicker(user);
    const boxes = () => within(group).queryAllByRole<HTMLInputElement>('checkbox');
    await eventually(() => {
      expect(boxes()).toHaveLength(12);
    });
    expect(picker.says()).not.toContain(LIMIT);

    for (let n = 1; n <= 10; n += 1) await user.click(await picker.box(`상담${String(n).padStart(2, '0')}`));

    await eventually(() => {
      expect(picker.says()).toContain(LIMIT);
    });
    expect(boxes().filter((b) => b.checked)).toHaveLength(10);
    expect(boxes().filter((b) => !b.checked).map(isBlocked), '고르지 않은 두 상자').toEqual([true, true]);
    expect(boxes().filter((b) => b.checked).some(isBlocked), '고른 상자는 뺄 수 있다').toBe(false);

    await user.click(await picker.box('상담01'));
    await eventually(() => {
      expect(boxes().filter(isBlocked)).toHaveLength(0);
    });
    expect(picker.says()).not.toContain(LIMIT);
  });

  test('S4-AC-63 C-4 알릴 사람(후보를 받는 중, 후보 없음): 받는 동안 "불러오는 중…", 후보가 없으면 "알릴 수 있는 사람이 없습니다"', async () => {
    asAgent();
    const release = gate();
    on('get', CANDIDATES_7, async () => {
      await release.opened;
      return HttpResponse.json({ items: [] });
    });
    const { user } = renderAt(URL_7);
    await user.click(await picker.toggle());

    await sent('GET', CANDIDATES_7);
    await eventually(() => {
      expect(picker.says()).toContain('불러오는 중…');
    });
    expect(picker.says()).not.toContain('알릴 수 있는 사람이 없습니다');
    release.open();

    await eventually(() => {
      expect(picker.says()).toContain('알릴 수 있는 사람이 없습니다');
    });
    expect(picker.says()).not.toContain('불러오는 중…');
    expect(within(newForm() as HTMLElement).queryAllByRole('checkbox')).toHaveLength(0);
  });

  test('S4-AC-63 C-4 알릴 사람(후보 받기 실패): 구역 안에 "후보를 불러오지 못했습니다"와 "다시 불러오기"가 보이고, 누르면 후보가 나온다. 후보를 받지 못한 채로도 댓글은 멘션 없이 보낼 수 있다', async () => {
    asAgent();
    let broken = true;
    on('get', CANDIDATES_7, ({ request }) =>
      broken ? errors.internal() : HttpResponse.json({ items: candidates(new URL(request.url).searchParams.get('internal') === 'true') }),
    );
    const { user } = renderAt(URL_7);
    await user.click(await picker.toggle());

    await eventually(() => {
      expect(picker.says()).toContain('후보를 불러오지 못했습니다');
    });
    expect(within(newForm() as HTMLElement).queryAllByRole('checkbox')).toHaveLength(0);
    broken = false;
    await user.click(await buttonIn(newForm, '다시 불러오기'));

    await sent('GET', CANDIDATES_7, 2);
    await eventually(() => {
      expect(picker.lines()).toEqual(PUBLIC_LINES);
    });
    expect(picker.says()).not.toContain('후보를 불러오지 못했습니다');

    // It fails again, for the other kind of comment: the comment goes out all the same, with nobody mentioned.
    broken = true;
    await user.click(await internalTab());
    await eventually(() => {
      expect(picker.says()).toContain('후보를 불러오지 못했습니다');
    });
    await user.type(await writeBox(), '후보 없이 남기는 메모');
    await user.click(await sendInternal());

    const [post] = await sent('POST', COMMENTS_7);
    const body = (await post?.body) as { content: string };
    expect(body.content).toBe('후보 없이 남기는 메모');
    expect('mentions' in body, '본문의 mentions 키').toBe(false);
    await said('내부 메모를 남겼습니다');
    await commentWith('후보 없이 남기는 메모');
  });

  test('S4-AC-63 C-4 알릴 사람 400 UNKNOWN_VALUE: "지금 알릴 수 없는 사람이 있습니다. 다시 골라 주세요."와 함께 후보를 다시 받고, 없는 사람을 고른 것에서 뺀다. 내용은 그대로다', async () => {
    asAgent();
    const { user } = renderAt(URL_7);
    await openPicker(user);
    await user.click(await picker.box('정상담'));
    await user.click(await picker.box('최관리'));
    await user.type(await writeBox(), '그 사이 배정이 바뀐 답변');
    // 정상담 lost the company while the form was open: the server refuses and no longer offers them.
    s4.candidates.public = [ADMIN_PERSON, CUSTOMER_PERSON];

    await user.click(await sendPublic());

    await sent('POST', COMMENTS_7);
    await eventually(() => {
      expect(picker.says()).toContain(UNKNOWN);
    });
    await sent('GET', CANDIDATES_7, 2);
    await eventually(() => {
      expect(picker.lines()).toEqual(['김고객 (올린 고객)', '최관리 (관리자)']);
    });
    expect(await picker.box('최관리')).toBeChecked();
    expect(summary()).toContain('최관리');
    expect(summary()).not.toContain('정상담');
    expect(await writeBox()).toHaveValue('그 사이 배정이 바뀐 답변');

    await user.click(await sendPublic());
    const [, second] = await sent('POST', COMMENTS_7, 2);
    expect(mentionsOf(await second?.body)).toEqual([1]);
    await said('답변을 보냈습니다');
  });

  test('S4-AC-63 C-4 알릴 사람 400 TOO_MANY: "10명까지 고를 수 있습니다"를 보이고 내용과 고른 사람은 그대로다', async () => {
    asAgent();
    on('post', COMMENTS_7, () => errors.invalid([{ field: 'mentions', code: 'TOO_MANY' }]));
    const { user } = renderAt(URL_7);
    await openPicker(user);
    await user.click(await picker.box('정상담'));
    await user.type(await writeBox(), '많이 알리는 답변');
    expect(picker.says()).not.toContain(LIMIT);

    await user.click(await sendPublic());

    await sent('POST', COMMENTS_7);
    await eventually(() => {
      expect(picker.says()).toContain(LIMIT);
    });
    expect(await writeBox()).toHaveValue('많이 알리는 답변');
    expect(summary()).toContain('정상담');
    expect(posts()).toHaveLength(1);
  });

  test('S4-AC-69 C-4 알릴 사람(늦게 온 후보): 댓글 종류를 바꾼 뒤에 온 앞 종류의 후보는 버린다', async () => {
    asAgent();
    const release = gate();
    let late = false;
    on('get', CANDIDATES_7, async ({ request }) => {
      if (new URL(request.url).searchParams.get('internal') === 'true') return HttpResponse.json({ items: candidates(true) });
      await release.opened;
      late = true;
      return HttpResponse.json({ items: candidates(false) });
    });
    const { user } = renderAt(URL_7);
    await user.click(await picker.toggle());
    await sent('GET', CANDIDATES_7);

    await user.click(await internalTab());
    await eventually(() => {
      expect(picker.lines()).toEqual(INTERNAL_LINES);
    });

    release.open();
    await eventually(() => {
      expect(late).toBe(true);
    });
    await settle();
    expect(picker.lines(), '공개 답변의 후보(올린 고객)가 끼어들지 않는다').toEqual(INTERNAL_LINES);
    expect(picker.says()).not.toContain('김고객');
  });

  test('S4-AC-63 C-4 알릴 사람(고치기 폼): 고치기 폼에도 있고 지금 멘션된 사람이 골라져 있다. 후보는 그 댓글의 종류로 받고, 멘션만 바꾸면 PATCH에 mentions만 보낸다', async () => {
    asAgent();
    state.comments = [comment({ id: 5, author: AGENT_PERSON, content: '내부 확인 중', internal: true, mentions: [OTHER_AGENT_PERSON] })];
    const { user } = renderAt(URL_7);
    await user.click(await commentAction('내부 확인 중', EDIT));
    await editFormShown();
    const editing = people(editForm);

    expect(await editing.toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(editing.summary()).toBe('알릴 사람: 정상담');
    // The editor comes first, then the picker, then the buttons.
    const save = await buttonIn(editForm, '고친 댓글 저장');
    expect((await editBox()).compareDocumentPosition(await editing.toggle()) & Node.DOCUMENT_POSITION_FOLLOWING, '편집기 아래').toBeTruthy();
    expect((await editing.toggle()).compareDocumentPosition(save) & Node.DOCUMENT_POSITION_FOLLOWING, '저장 버튼 위').toBeTruthy();

    await user.click(await editing.toggle());
    await editing.opened();
    await eventually(() => {
      expect(editing.lines()).toEqual(INTERNAL_LINES);
    });
    expect(askedFor(), '내부 메모의 후보').toEqual(['true']);
    expect(await editing.box('정상담')).toBeChecked();
    expect(await editing.box('최관리')).not.toBeChecked();
    // The picker of the new comment is its own: still folded, nobody picked.
    expect(await picker.toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(summary()).toBe(NONE);

    await user.click(await editing.box('최관리'));
    await user.click(save);

    const [patch] = await sent('PATCH', `${COMMENTS_7}/5`);
    const body = await patch?.body;
    expect(Object.keys(body as object)).toEqual(['mentions']);
    expect(mentionsOf(body)).toEqual([1, 3]);
    await said('댓글을 고쳤습니다');
    await eventually(() => {
      expect(editForm()).toBeNull();
    });
    expect((await commentWith('내부 확인 중')).textContent).toMatch(/알림: (최관리, 정상담|정상담, 최관리)/);
  });

  test('S4-AC-63 C-4 알릴 사람(고치기 폼에서 모두 뺌): 멘션된 사람을 모두 빼고 저장하면 PATCH에 빈 목록 mentions: []를 보내고, 댓글의 "알림:" 줄이 사라진다', async () => {
    asAgent();
    state.comments = [comment({ id: 5, author: AGENT_PERSON, content: '내부 확인 중', internal: true, mentions: [OTHER_AGENT_PERSON] })];
    const { user } = renderAt(URL_7);
    expect((await commentWith('내부 확인 중')).textContent).toContain('알림: 정상담');
    await user.click(await commentAction('내부 확인 중', EDIT));
    await editFormShown();
    const editing = people(editForm);
    await user.click(await editing.toggle());
    await editing.opened();

    await user.click(await editing.box('정상담'));
    await eventually(() => {
      expect(editing.summary()).toBe(NONE);
    });
    await user.click(await buttonIn(editForm, '고친 댓글 저장'));

    const [patch] = await sent('PATCH', `${COMMENTS_7}/5`);
    expect(await patch?.body).toEqual({ mentions: [] });
    await said('댓글을 고쳤습니다');
    await eventually(() => {
      expect(editForm()).toBeNull();
    });
    expect((await commentWith('내부 확인 중')).textContent).not.toContain('알림:');
  });

  test('C-4 axe (알릴 사람): 후보를 펼쳐 둘을 고른 댓글 쓰기 폼에 접근성 위반이 없다', async () => {
    asAgent();
    const { user } = renderAt(URL_7);
    await openPicker(user);
    await user.click(await picker.box('정상담'));
    await user.click(await picker.box('김고객'));
    await eventually(() => {
      expect(summary()).toContain('김고객');
    });

    await expectNoAxeViolations();
  });
});
