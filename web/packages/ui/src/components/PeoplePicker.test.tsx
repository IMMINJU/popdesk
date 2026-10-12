// 4번 묶음 screens.md 2절 C-4 "알릴 사람", 3절 "여러 사람 고르기": PeoplePicker.
// 접힌 한 줄(펼침 버튼과 요약)이고, 펼치면 이름이 label인 group의 체크 상자들이다. radiogroup이 아니다(S4-AC-63).
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { expectNoAxeViolations, isBlocked, must } from '../__tests__/support';
import { PeoplePicker, type PeoplePickerProps } from './PeoplePicker';

const CANDIDATES = [
  { id: '1', label: '최관리 (관리자)' },
  { id: '3', label: '정상담 (상담원)' },
  { id: '11', label: '김고객 (올린 고객)' },
];

function picker(over: Partial<PeoplePickerProps> = {}) {
  return (
    <PeoplePicker
      label="알릴 사람"
      toggleLabel="알릴 사람 고르기"
      candidates={CANDIDATES}
      value={[]}
      onChange={() => undefined}
      summary="알릴 사람 없음"
      maxText="10명까지 고를 수 있습니다"
      loadingText="불러오는 중…"
      failedText="후보를 불러오지 못했습니다"
      retryLabel="다시 불러오기"
      emptyText="알릴 수 있는 사람이 없습니다"
      {...over}
    />
  );
}

const toggle = () => screen.getByRole('button', { name: '알릴 사람 고르기' });
const group = () => screen.queryByRole('group', { name: '알릴 사람' });
const boxes = () => screen.queryAllByRole<HTMLInputElement>('checkbox');
const box = (name: string) => must(screen.queryByRole<HTMLInputElement>('checkbox', { name }), `상자 "${name}"`);
/** A line of text the picker shows; fails through `expect` when it is not there. */
const shown = (words: string) => must(screen.queryByText(words), `글 "${words}"`);
const sorted = (ids: readonly string[]) => [...ids].sort();

describe('PeoplePicker', () => {
  test('S4-AC-63 PeoplePicker 접힘과 펼침: 접힌 한 줄은 aria-expanded="false"인 버튼과 요약 글이다. 버튼을 누르면 펼쳐지고 onOpenChange가 불린다', async () => {
    const onOpenChange = vi.fn();
    render(picker({ summary: '알릴 사람: 정상담, 최관리', value: ['3', '1'], onOpenChange }));
    const user = userEvent.setup();

    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    shown('알릴 사람: 정상담, 최관리');
    expect(boxes(), '접힌 동안 상자').toHaveLength(0);

    await user.click(toggle());

    expect(onOpenChange).toHaveBeenLastCalledWith(true);
    expect(toggle()).toHaveAttribute('aria-expanded', 'true');
    expect(group(), 'group "알릴 사람"').not.toBeNull();
    expect(boxes().map((b) => b.checked)).toEqual([true, true, false]);

    await user.click(toggle());

    expect(onOpenChange).toHaveBeenLastCalledWith(false);
    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(boxes()).toHaveLength(0);
  });

  test('S4-AC-63 PeoplePicker open: 펼치면 이름이 label인 group 안에 후보마다 체크 상자가 있다. 사진이 없고 radiogroup이 아니다. notice는 한 줄로 보인다', () => {
    render(picker({ open: true, value: ['3'], notice: '내부 메모에서는 고객에게 알릴 수 없어 김고객 님을 뺐습니다.' }));

    expect(toggle()).toHaveAttribute('aria-expanded', 'true');
    const g = group();
    expect(g, 'group "알릴 사람"').not.toBeNull();
    const inGroup = within(g as HTMLElement).queryAllByRole('checkbox');
    expect(inGroup).toHaveLength(CANDIDATES.length);
    CANDIDATES.forEach((c, i) => {
      expect(inGroup[i]).toHaveAccessibleName(c.label);
    });
    expect(box('정상담 (상담원)')).toBeChecked();
    expect(box('최관리 (관리자)')).not.toBeChecked();
    expect(screen.queryByRole('radiogroup')).toBeNull();
    expect(screen.queryAllByRole('radio')).toHaveLength(0);
    expect(document.querySelectorAll('img')).toHaveLength(0);
    shown('내부 메모에서는 고객에게 알릴 수 없어 김고객 님을 뺐습니다.');
  });

  test('S4-AC-63 PeoplePicker 고르기: 상자를 누르면 onChange가 고른 ID들로 불린다. 고른 것을 다시 누르면 그 ID가 빠진다', async () => {
    const onChange = vi.fn<(value: string[]) => void>();
    render(picker({ open: true, value: ['3'], onChange }));
    const user = userEvent.setup();

    await user.click(box('김고객 (올린 고객)'));
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(sorted(onChange.mock.calls[0]?.[0] ?? [])).toEqual(['11', '3']);

    await user.click(box('정상담 (상담원)'));
    expect(onChange).toHaveBeenCalledTimes(2);
    expect(onChange.mock.calls[1]?.[0]).toEqual([]);
  });

  test('S4-AC-63 PeoplePicker 상한: max만큼 골랐으면 나머지 상자가 막히고 maxText가 보인다. 고른 상자는 뺄 수 있다. 덜 골랐으면 막지 않는다', () => {
    const { rerender } = render(picker({ open: true, max: 2, value: ['1', '3'] }));

    expect(isBlocked(box('김고객 (올린 고객)')), '고르지 않은 상자').toBe(true);
    expect(isBlocked(box('최관리 (관리자)')), '고른 상자').toBe(false);
    expect(isBlocked(box('정상담 (상담원)')), '고른 상자').toBe(false);
    shown('10명까지 고를 수 있습니다');

    rerender(picker({ open: true, max: 2, value: ['1'] }));
    expect(boxes().filter(isBlocked)).toHaveLength(0);
    expect(screen.queryByText('10명까지 고를 수 있습니다')).toBeNull();
  });

  test.each([
    ['받는 중', { status: 'loading' as const }, '불러오는 중…'],
    ['후보 없음', { status: 'ready' as const, candidates: [] }, '알릴 수 있는 사람이 없습니다'],
  ])('S4-AC-63 PeoplePicker %s: 펼친 자리에 상자 대신 글이 보인다', (_, over, says) => {
    render(picker({ open: true, ...over }));

    shown(says);
    expect(boxes()).toHaveLength(0);
    expect(screen.queryByRole('button', { name: '다시 불러오기' })).toBeNull();
  });

  test('S4-AC-63 PeoplePicker 후보 받기 실패: failedText와 "다시 불러오기" 버튼이 보이고 누르면 onRetry가 불린다', async () => {
    const onRetry = vi.fn();
    render(picker({ open: true, status: 'failed', onRetry }));
    const user = userEvent.setup();

    shown('후보를 불러오지 못했습니다');
    expect(boxes()).toHaveLength(0);
    await user.click(must(screen.queryByRole('button', { name: '다시 불러오기' }), '"다시 불러오기"'));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  test('S4-AC-63 PeoplePicker 키보드: 펼친 채 Escape를 누르면 접히고 포커스가 버튼으로 간다', async () => {
    const onOpenChange = vi.fn();
    render(picker({ onOpenChange }));
    const user = userEvent.setup();
    await user.click(toggle());
    box('정상담 (상담원)').focus();

    await user.keyboard('{Escape}');

    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(toggle()).toHaveFocus();
    expect(onOpenChange).toHaveBeenLastCalledWith(false);
    expect(boxes()).toHaveLength(0);
  });

  test.each([
    ['펼침', { value: ['3'] }],
    ['10명 참', { max: 1, value: ['3'] }],
    ['실패', { status: 'failed' as const }],
  ])('PeoplePicker axe (%s): 펼친 모습에 접근성 위반이 없다', async (_, over) => {
    render(picker({ open: true, ...over }));

    expect(toggle()).toHaveAttribute('aria-expanded', 'true');
    await expectNoAxeViolations();
  });
});
