// 3번 묶음 screens.md 2절 P-5 문의 상세: 고치기(S3-AC-81). 흐름은 콘솔 C-4와 같고, 409의 "지금 값"에는 고객이 보는 칸만 나온다.
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  alertWith,
  button,
  callsTo,
  dialog,
  eventually,
  expectNoAxeViolations,
  field,
  found,
  hrefPath,
  isBlocked,
  pageText,
  renderAt,
  screen,
  sent,
  state,
  text,
  within,
} from '../__tests__/portal';
import { rejected, route } from '../__tests__/portal1a';
import { openDialog, said } from '../__tests__/portal2';
import { ISSUES, issueIn, type UpdateIssueRequest } from '../__tests__/portal3';

const URL_7 = '/issues/ACME-7';
const ISSUE_7 = `${ISSUES}/ACME-7`;
const CONFLICT = '고치는 동안 다른 사람이 이 문의를 바꿨습니다. 아래 지금 값을 보고 다시 저장해 주세요.';

type User = ReturnType<typeof renderAt>['user'];

const titleBox = () => field('제목', 'input');
const contentBox = () => field<HTMLTextAreaElement>('내용', 'textarea');
const priority = (word: string) =>
  found(() => {
    const group = screen.queryByRole('radiogroup', { name: '우선순위' });
    return group && within(group).queryByRole<HTMLInputElement>('radio', { name: word });
  }, `우선순위 "${word}"`);
const save = () => button('저장');
const editing = () => screen.queryAllByLabelText('제목', { selector: 'input' }).length > 0;
const patches = () => callsTo('PATCH', ISSUE_7);
/** A thing that opens a folded part: a button, or the `summary` of a `details`. */
const opener = (name: RegExp) =>
  found(
    () => screen.queryByRole('button', { name }) ?? Array.from(document.querySelectorAll<HTMLElement>('summary')).find((s) => name.test(text(s))),
    `펼치는 것 ${String(name)}`,
  );

async function openEdit(user: User) {
  await user.click(await button('고치기'));
  return titleBox();
}

describe('P-5 고치기', () => {
  test('S3-AC-81 P-5 고치기: 제목, 내용, 우선순위가 지금 값이 든 입력칸으로 바뀐다. 바뀐 것이 없으면 "저장"이 막혀 있고 "취소"는 묻지 않고 돌아간다', async () => {
    state.issue = issueIn('RECEIVED', { priority: 'HIGH' });
    const { user } = renderAt(URL_7);

    expect(await openEdit(user)).toHaveValue('로그인이 안 됩니다');
    expect(await contentBox()).toHaveValue('어제부터 **로그인**이 안 됩니다.');
    expect(await priority('높음')).toBeChecked();
    expect(isBlocked(await save()), '바뀐 것이 없으면 "저장"').toBe(true);

    await user.type(await titleBox(), '!');
    expect(isBlocked(await save()), '바꾸면 "저장"').toBe(false);
    await user.type(await titleBox(), '{Backspace}');
    expect(isBlocked(await save()), '되돌리면 "저장"').toBe(true);

    await user.click(await button('취소'));
    await eventually(() => {
      expect(editing(), '보기로 돌아감').toBe(false);
    });
    expect(openDialog()).toBeNull();
    expect(patches()).toHaveLength(0);
  });

  test('S3-AC-81 P-5 고치기 저장: 읽어 온 version과 바뀐 칸만 보낸다. 성공하면 보기로 돌아가 새 값을 보이고 토스트 "문의를 고쳤습니다"', async () => {
    state.issue = issueIn('RECEIVED', { version: 4 });
    const { user } = renderAt(URL_7);
    const title = await openEdit(user);
    await user.clear(title);
    await user.type(title, '로그인 화면이 멈춥니다');
    await user.click(await priority('긴급'));
    await user.click(await save());

    const [patch] = await sent('PATCH', ISSUE_7);
    const body: UpdateIssueRequest = { title: '로그인 화면이 멈춥니다', priority: 'URGENT', version: 4 };
    expect(await patch?.body).toEqual(body);
    await said('문의를 고쳤습니다');
    await eventually(() => {
      expect(editing(), '보기로 돌아감').toBe(false);
      expect(text(screen.getByRole('heading', { level: 1 }))).toBe('로그인 화면이 멈춥니다');
    });
  });

  test('S3-AC-81 P-5 고치기 409: 입력은 그대로 두고 Alert로 알리며 지금의 제목, 우선순위, 상태(고객의 말)와 "지금 내용 보기"를 보인다. 내부 칸은 없다. 다시 저장하면 새 version으로 보낸다', async () => {
    state.issue = issueIn('RECEIVED', { version: 4 });
    let first = true;
    route('patch', ISSUE_7, async ({ request }) => {
      const { version, ...fields } = (await request.json()) as UpdateIssueRequest;
      if (first) {
        first = false;
        // The support side saved first.
        state.issue = issueIn('CONFIRMED', { title: '남이 고친 제목', content: '남이 고친 내용', priority: 'URGENT', version: 5 });
        return rejected('VERSION_CONFLICT', 409);
      }
      if (version !== state.issue.version) return rejected('VERSION_CONFLICT', 409);
      state.issue = { ...state.issue, ...fields, version: 6 };
      return HttpResponse.json(state.issue);
    });
    const { user } = renderAt(URL_7);
    const title = await openEdit(user);
    await user.clear(title);
    await user.type(title, '내가 고친 제목');
    await user.click(await save());

    await alertWith(CONFLICT);
    expect(await titleBox(), '입력은 그대로').toHaveValue('내가 고친 제목');
    await eventually(() => {
      for (const now of ['남이 고친 제목', '긴급', '확인됨']) expect(pageText(), `지금 값 "${now}"`).toContain(now);
    });
    for (const staffOnly of ['담당자', '받은 경로']) expect(text(screen.getByRole('main')), `"${staffOnly}"`).not.toContain(staffOnly);
    await user.click(await opener(/지금 내용 보기/));
    await eventually(() => {
      expect(pageText()).toContain('남이 고친 내용');
    });

    await user.click(await save());
    const [, second] = await sent('PATCH', ISSUE_7, 2);
    expect(await second?.body).toMatchObject({ title: '내가 고친 제목', version: 5 });
    await said('문의를 고쳤습니다');
    await eventually(() => {
      expect(editing()).toBe(false);
    });
  });

  test('S3-AC-81 P-5 고치기 400 ISSUE_CLOSED: Alert "문의가 종료되어 고칠 수 없습니다". 입력은 그대로 두고 "저장"을 막는다', async () => {
    state.issue = issueIn('RECEIVED');
    route('patch', ISSUE_7, () => {
      state.issue = issueIn('CLOSED');
      return rejected('ISSUE_CLOSED');
    });
    const { user } = renderAt(URL_7);
    await user.type(await openEdit(user), ' (보탬)');
    await user.click(await save());

    await alertWith('문의가 종료되어 고칠 수 없습니다');
    expect(await titleBox()).toHaveValue('로그인이 안 됩니다 (보탬)');
    await eventually(() => {
      const again = screen.queryByRole('button', { name: '저장' });
      expect(again !== null && isBlocked(again), '"저장"이 막힘').toBe(true);
    });
  });

  test('S3-AC-81 P-5 고치기 취소와 떠나기: 바꾼 글이 있으면 묻고, 그 자리에 글이 남는다', async () => {
    state.issue = issueIn('RECEIVED');
    const { user } = renderAt(URL_7);
    await user.type(await openEdit(user), ' (쓰는 중)');

    await user.click(await button('취소'));
    await dialog();
    await user.keyboard('{Escape}');
    await eventually(() => {
      expect(openDialog()).toBeNull();
    });
    expect(await titleBox(), '취소를 물은 뒤').toHaveValue('로그인이 안 됩니다 (쓰는 중)');

    await user.click(await found(() => screen.queryAllByRole('link').find((a) => hrefPath(a) === '/issues'), '문의 목록으로 가는 링크'));
    await dialog();
    expect(window.location.pathname).toBe(URL_7);
    await user.keyboard('{Escape}');
    expect(await titleBox(), '떠나기를 물은 뒤').toHaveValue('로그인이 안 됩니다 (쓰는 중)');
    expect(patches()).toHaveLength(0);
  });

  test('P-5 axe (고치기): 입력칸으로 바뀐 화면과 409의 지금 값이 보이는 화면에 접근성 위반이 없다', async () => {
    state.issue = issueIn('RECEIVED');
    route('patch', ISSUE_7, () => {
      state.issue = issueIn('CONFIRMED', { title: '남이 고친 제목', version: 2 });
      return rejected('VERSION_CONFLICT', 409);
    });
    const { user } = renderAt(URL_7);
    await user.type(await openEdit(user), ' (보탬)');
    await expectNoAxeViolations();

    await user.click(await save());
    await alertWith(CONFLICT);
    await expectNoAxeViolations();
  });
});
