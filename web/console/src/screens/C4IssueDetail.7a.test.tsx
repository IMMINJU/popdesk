// 7a 묶음 screens.md 1절 C-4 문의 지우기(관리자): 머리 동작 줄의 "문의 지우기", 확인 대화상자, 성공·실패·404, 그리고
// 대화상자가 닫힌 뒤 온 답(S7A-AC-50, S7A-AC-53). 확인의 "지우기"는 댓글 지우기 단추와 이름이 같아 대화상자 안으로 좁힌다.
import { waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { ADMIN, AGENT, callsTo, errors, eventually, expectNoAxeViolations, found, gate, openDialog, pageText, renderAt, screen, sent, state, statusWith, text, within } from '../__tests__/console';
import { answered, closed, route, settle } from '../__tests__/console1a';
import { issueIn, type Status } from '../__tests__/console3';
import { metaValueNow } from '../__tests__/console6a';
import { DELETE_QUESTION, DELETE_TEXT, deleteDialog, deleteDialogNow, deleteIssueButtonNow, dialogButton, issuePath } from '../__tests__/console7a';

const URL_7 = '/issues/ACME-7';
const ISSUE_7 = issuePath('ACME-7');
const DELETE_FAILED = '문의를 지우지 못했습니다. 다시 해 보세요.';
const MOVED = 'ACME-7 문의를 휴지통으로 옮겼습니다.';

type User = ReturnType<typeof renderAt>['user'];

/** Waits generously for C-4: the first wait of a test may meet a busy machine. */
async function opened(): Promise<void> {
  await waitFor(
    () => {
      expect(metaValueNow('담당자'), 'C-4 머리의 "담당자"').not.toBeNull();
    },
    { timeout: 5000 },
  );
}

/** The "문의 지우기" of the C-4 head, waited for. */
function deleteIssueButton(): Promise<HTMLElement> {
  return found(deleteIssueButtonNow, '"문의 지우기" 단추');
}

/** Opens the delete confirmation from the C-4 head. */
async function openDelete(user: User): Promise<HTMLElement> {
  await user.click(await deleteIssueButton());
  return deleteDialog();
}

describe('C-4 문의 지우기 (7a 묶음)', () => {
  test.each<{ status: Status }>([{ status: 'RECEIVED' }, { status: 'IN_PROGRESS' }, { status: 'RESOLVED' }, { status: 'CLOSED' }])(
    'S7A-AC-50 C-4 관리자($status): 문의 머리에 "문의 지우기"가 하나 있다',
    async ({ status }) => {
      state.issue = issueIn(status);
      renderAt(URL_7);
      await opened();

      const button = await deleteIssueButton();
      expect(button.closest('header'), '문의 머리 안').not.toBeNull();
      expect(button.closest('header')?.querySelector('h1'), '제목과 같은 머리').not.toBeNull();
      expect(screen.queryAllByRole('button', { name: '문의 지우기' })).toHaveLength(1);
      // The end of the head's row of buttons, beside the status actions.
      const row = button.parentElement as HTMLElement;
      expect(within(row).queryAllByRole('button').at(-1), '단추 줄의 끝').toBe(button);
      await expectNoAxeViolations();
    },
  );

  test('S7A-AC-50 C-4 상담원에게는 "문의 지우기"가 없다. 같은 문의를 관리자가 열면 있다', async () => {
    state.me = AGENT;
    state.issue = issueIn('IN_PROGRESS');
    const agentView = renderAt(URL_7);
    await opened();
    await settle();
    expect(deleteIssueButtonNow(), '상담원의 "문의 지우기"').toBeNull();
    agentView.unmount();

    state.me = ADMIN;
    renderAt(URL_7);
    await opened();
    await deleteIssueButton();
  });

  test('S7A-AC-50 C-4 확인: 제목 "이 문의를 지울까요?", 휴지통 안내 글, "지우기"와 "그만두기". "그만두기"는 보내지 않고 닫는다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await opened();

    const box = await openDelete(user);
    expect(text(box)).toContain(DELETE_QUESTION);
    expect(text(box)).toContain(DELETE_TEXT);
    expect(
      within(box)
        .queryAllByRole('button')
        .map((b) => text(b))
        .filter((t) => t === '지우기' || t === '그만두기')
        .sort(),
    ).toEqual(['그만두기', '지우기']);
    await expectNoAxeViolations();

    await user.click(await dialogButton(box, '그만두기'));
    await closed();
    await settle();
    expect(callsTo('DELETE', ISSUE_7), '그만두기의 요청').toHaveLength(0);
    expect(window.location.pathname).toBe(URL_7);
  });

  test('S7A-AC-50 C-4 "지우기": DELETE를 보내고 문의 목록으로 가서 "ACME-7 문의를 휴지통으로 옮겼습니다."를 알린다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await opened();

    const box = await openDelete(user);
    await user.click(await dialogButton(box, '지우기'));

    await sent('DELETE', ISSUE_7);
    await eventually(() => {
      expect(window.location.pathname).toBe('/issues');
    });
    await statusWith(MOVED);
    expect(openDialog(), '성공 뒤의 대화상자').toBeNull();
    expect(callsTo('DELETE', ISSUE_7), '지우기 요청 수').toHaveLength(1);
  });

  test('S7A-AC-50 C-4 지우기 실패(500): 대화상자 안 role="alert"에 "문의를 지우지 못했습니다. 다시 해 보세요."이고 대화상자와 문의가 그대로다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    route('delete', ISSUE_7, () => errors.internal());
    const { user } = renderAt(URL_7);
    await opened();

    const box = await openDelete(user);
    await user.click(await dialogButton(box, '지우기'));

    await found(() => within(box).queryAllByRole('alert').find((a) => text(a).includes(DELETE_FAILED)), `대화상자 안의 "${DELETE_FAILED}"`);
    expect(deleteDialogNow(), '실패 뒤의 대화상자').not.toBeNull();
    expect(window.location.pathname).toBe(URL_7);
    expect(pageText()).not.toContain(MOVED);
  });

  test('S7A-AC-50 C-4 지우기 404: 대화상자를 닫고 문의를 다시 읽어 있는 404 화면이 된다. 오류 문구는 없다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    route('delete', ISSUE_7, () => errors.notFound());
    const { user } = renderAt(URL_7);
    await opened();
    const reads = callsTo('GET', ISSUE_7).length;

    const box = await openDelete(user);
    route('get', ISSUE_7, () => errors.notFound());
    await user.click(await dialogButton(box, '지우기'));

    await sent('DELETE', ISSUE_7);
    await closed();
    await eventually(() => {
      expect(callsTo('GET', ISSUE_7).length, '404 뒤 문의 다시 읽기').toBeGreaterThan(reads);
      expect(pageText()).toContain('찾을 수 없습니다');
    });
    expect(pageText()).not.toContain(DELETE_FAILED);
    expect(deleteIssueButtonNow()).toBeNull();
  });
});

describe('C-4 문의 지우기의 늦은 답 (7a 묶음)', () => {
  test('S7A-AC-53 C-4 확인을 닫은 뒤 온 지우기 성공: 대화상자를 다시 열지 않고, 지운 문의를 더는 그리지 않는다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const release = gate();
    route('delete', ISSUE_7, async () => {
      await release.opened;
      route('get', ISSUE_7, () => errors.notFound());
      return new Response(null, { status: 204 });
    });
    const { user } = renderAt(URL_7);
    await opened();
    const box = await openDelete(user);
    await user.click(await dialogButton(box, '지우기'));
    await sent('DELETE', ISSUE_7);

    await user.keyboard('{Escape}');
    await closed();
    const done = answered('DELETE', ISSUE_7);
    release.open();
    await done;
    await settle();

    await eventually(() => {
      expect(deleteIssueButtonNow(), '지운 문의의 "문의 지우기"').toBeNull();
    });
    expect(openDialog(), '늦은 답이 대화상자를 다시 열지 않는다').toBeNull();
    expect(pageText()).not.toContain(DELETE_FAILED);
  });

  test('S7A-AC-53 C-4 확인을 닫은 뒤 온 지우기 실패는 버린다: 오류가 없고 문의가 그대로이며 다시 열면 오류 없이 처음부터다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const release = gate();
    route('delete', ISSUE_7, async () => {
      await release.opened;
      return errors.internal();
    });
    const { user } = renderAt(URL_7);
    await opened();
    const box = await openDelete(user);
    await user.click(await dialogButton(box, '지우기'));
    await sent('DELETE', ISSUE_7);

    await user.keyboard('{Escape}');
    await closed();
    const done = answered('DELETE', ISSUE_7);
    release.open();
    await done;
    await settle();

    expect(pageText(), '닫힌 뒤 온 실패').not.toContain(DELETE_FAILED);
    expect(openDialog(), '늦은 답이 대화상자를 다시 열지 않는다').toBeNull();
    expect(window.location.pathname).toBe(URL_7);
    const again = await openDelete(user);
    expect(within(again).queryAllByRole('alert'), '앞 요청의 오류').toHaveLength(0);
    expect(callsTo('DELETE', ISSUE_7)).toHaveLength(1);
  });
});
