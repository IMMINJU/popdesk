// b1 묶음 screens.md 2절 C-4 문의 상세: 화면 안 구역(이력)의 읽기 실패 띠에서 "다시 불러오기"를 누르면 초점이 화면 제목(h1)으로
// 간다(B1-AC-02). 담당자 대화상자의 후보 읽기 실패 띠에서 누르면 초점이 대화상자 제목으로 가고 대화상자 밖으로 나가지 않는다(B1-AC-03).
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import { callsTo, errors, eventually, expectNoAxeViolations, found, on, paged, renderAt, screen, sent, state, text, within } from '../__tests__/console';
import { ISSUES, history, issueIn, opener, s3 } from '../__tests__/console3';

const URL_7 = '/issues/ACME-7';
const HISTORY_7 = `${ISSUES}/ACME-7/history`;
const CANDIDATES = '/api/console/companies/ACME/assignees';
const ISSUE_TITLE = '로그인이 안 됩니다';
const RETRY = '다시 불러오기';

const issueTitle = () => screen.queryByRole('heading', { level: 1, name: ISSUE_TITLE });

describe('C-4 구역의 다시 불러오기 뒤 초점 (B1-AC-02)', () => {
  test('B1-AC-02 C-4 이력 읽기 실패에서 "다시 불러오기"를 누르면 초점이 화면 제목 h1(문의 제목)으로 가고 이력 줄이 나온다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    s3.history = [history()];
    let broken = true;
    on('get', HISTORY_7, () => (broken ? errors.internal() : HttpResponse.json(paged(s3.history, { size: 20 }))));
    const { user } = renderAt(URL_7);
    await found(issueTitle, `h1 "${ISSUE_TITLE}"`);
    await user.click(await opener(/^이력/));
    const band = await found(() => screen.queryAllByRole('alert').find((a) => within(a).queryByRole('button', { name: RETRY }) !== null), '이력의 실패 띠');
    const retry = within(band).getByRole('button', { name: RETRY });
    const before = callsTo('GET', HISTORY_7).length;
    broken = false;

    await user.click(retry);

    await sent('GET', HISTORY_7, before + 1);
    await found(() => screen.queryAllByRole('listitem').find((li) => li.closest('.pd-history') !== null), '이력 줄');
    await eventually(() => {
      expect(document.activeElement, '초점').toBe(issueTitle());
    });
    expect(issueTitle(), `h1 "${ISSUE_TITLE}"`).not.toBeNull();
    await expectNoAxeViolations();
  });
});

describe('담당자 대화상자의 다시 불러오기 뒤 초점 (B1-AC-03)', () => {
  test('B1-AC-03 C-4 담당자 후보 읽기 실패에서 "다시 불러오기"를 누르면 초점이 대화상자 제목 "담당자"로 가고 후보가 나온다', async () => {
    let broken = true;
    on('get', CANDIDATES, () => (broken ? errors.internal() : HttpResponse.json({ items: s3.candidates })));
    const { user } = renderAt(URL_7);
    await user.click(await found(() => screen.queryByRole('button', { name: /^(담당자 )?바꾸기$/ }), '담당자 "바꾸기"'));
    const box = await found(() => screen.queryByRole('dialog', { name: '담당자' }), '대화상자 "담당자"');
    const titleId = box.getAttribute('aria-labelledby') ?? '';
    const dialogTitle = () => (titleId === '' ? null : document.getElementById(titleId));
    expect(text(dialogTitle()), '대화상자 제목').toBe('담당자');
    await found(() => within(box).queryAllByRole('alert')[0], '대화상자 안의 띠');
    const retry = await found(() => within(box).queryByRole('button', { name: RETRY }), `"${RETRY}"`);
    const before = callsTo('GET', CANDIDATES).length;
    broken = false;

    await user.click(retry);

    await sent('GET', CANDIDATES, before + 1);
    await found(() => within(box).queryByRole('radio', { name: /정상담/ }), '대화상자의 "정상담"');
    await eventually(() => {
      expect(document.activeElement, '초점').toBe(dialogTitle());
    });
    expect(box.contains(document.activeElement), '초점이 대화상자 안').toBe(true);
    expect(dialogTitle()).not.toBeNull();
  });
});
