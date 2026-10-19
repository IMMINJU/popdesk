// b1 묶음 screens.md 2절 C-5 통계: 읽기 실패 띠의 "다시 불러오기"를 누르면 초점이 화면 제목(h1 "통계")으로 간다.
// 다시 실패하면 새 띠가 role="alert"로 알리고 초점은 제목에 남는다(B1-AC-02).
import { describe, expect, test } from 'vitest';
import { callsTo, errors, eventually, expectNoAxeViolations, found, renderAt, screen, sent, text } from '../__tests__/console';
import { answered, route, settle } from '../__tests__/console1a';
import { ACME_STAT, BETA_STAT, READ_FAILED, RETRY, STATS, URL_STATS, s7b, statsResponse, statsRow } from '../__tests__/console7b';

const statsTitle = () => screen.queryByRole('heading', { level: 1, name: '통계' });
const failedBands = () => screen.queryAllByRole('alert').filter((a) => text(a).includes(READ_FAILED));

/** C-5 drawn with its read failure: returns the "다시 불러오기" button. */
async function failedShown(): Promise<HTMLElement> {
  await found(() => failedBands()[0], `role="alert" "${READ_FAILED}"`);
  return found(() => screen.queryByRole('button', { name: RETRY }), `"${RETRY}"`);
}

describe('C-5 다시 불러오기 뒤 초점 (B1-AC-02)', () => {
  test('B1-AC-02 C-5 읽기 실패에서 "다시 불러오기"를 누르면 초점이 화면 제목 h1 "통계"로 가고 표가 나온다', async () => {
    s7b.stats = [ACME_STAT, BETA_STAT];
    let fail = true;
    route('get', STATS, () => (fail ? errors.internal() : statsResponse()));
    const { user } = renderAt(URL_STATS);
    const retry = await failedShown();
    const before = callsTo('GET', STATS).length;
    fail = false;

    await user.click(retry);

    await sent('GET', STATS, before + 1);
    await statsRow('BETA');
    await eventually(() => {
      expect(document.activeElement, '초점').toBe(statsTitle());
    });
    expect(statsTitle(), 'h1 "통계"').not.toBeNull();
    await expectNoAxeViolations();
  });

  test('B1-AC-02 C-5 다시 읽기도 실패하면 role="alert" 띠가 하나 있고 초점은 h1 "통계"에 남는다', async () => {
    route('get', STATS, () => errors.internal());
    const { user } = renderAt(URL_STATS);
    const retry = await failedShown();
    const again = answered('GET', STATS);

    await user.click(retry);

    await again;
    await settle();
    await failedShown();
    expect(failedBands(), '실패 띠').toHaveLength(1);
    await eventually(() => {
      expect(document.activeElement, '초점').toBe(statsTitle());
    });
    expect(statsTitle(), 'h1 "통계"').not.toBeNull();
  });
});
