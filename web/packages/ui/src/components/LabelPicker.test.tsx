// 6a 묶음 screens.md 1절 라벨 고르기, 5절 LabelPicker: 단추(aria-expanded)로 열고 닫는다. Esc와
// 바깥 누르기로 닫히고 초점이 단추로 돈다. 그 고객사 라벨의 체크 목록이고, 체크·풀기마다 onToggle이
// 한 번이다. 요청 중인 줄과 한도에 찬 뒤의 고르지 않은 줄은 막힌다. 8개를 넘으면 거르기 입력이 있다
// (S6A-AC-80, S6A-AC-86).
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { expectNoAxeViolations, isBlocked, must } from '../__tests__/support';
import type { LabelItem } from '../types';
import { LabelPicker, type LabelPickerProps } from './LabelPicker';

const LABELS: LabelItem[] = [
  { id: '61', name: '결제', color: '#245FB0' },
  { id: '62', name: '긴급', color: '#C0262D' },
  { id: '63', name: '모바일', color: '#F2C14E' },
];

/** `n` labels named 라벨01, 라벨02, … */
const many = (n: number): LabelItem[] =>
  Array.from({ length: n }, (_, i) => ({ id: String(100 + i), name: `라벨${String(i + 1).padStart(2, '0')}`, color: '#808080' }));

function picker(over: Partial<LabelPickerProps> = {}) {
  return (
    <div>
      <p>바깥 글</p>
      <LabelPicker
        label="라벨"
        toggleLabel="바꾸기"
        labels={LABELS}
        selected={[]}
        onToggle={() => undefined}
        limitText="라벨은 20개까지 붙입니다."
        filterLabel="라벨 거르기"
        noMatchText="맞는 라벨이 없습니다."
        emptyText="이 고객사에 라벨이 없습니다."
        {...over}
      />
    </div>
  );
}

const toggle = () => screen.getByRole('button', { name: '바꾸기' });
const group = () => screen.queryByRole('group', { name: '라벨' });
const boxes = () => screen.queryAllByRole<HTMLInputElement>('checkbox');
const box = (name: string) => must(screen.queryByRole<HTMLInputElement>('checkbox', { name }), `상자 "${name}"`);
const filterBox = () => screen.queryByRole<HTMLInputElement>('textbox', { name: '라벨 거르기' }) ?? screen.queryByRole<HTMLInputElement>('searchbox', { name: '라벨 거르기' });
const shown = (words: string) => must(screen.queryByText(words), `글 "${words}"`);

describe('LabelPicker', () => {
  test('S6A-AC-80 LabelPicker 열고 닫기: 단추가 aria-expanded로 열고 닫는다. 열면 이름이 "라벨"인 group 안에 라벨마다 이름이 라벨 이름인 체크 상자가 있고 붙은 것이 체크되어 있다', async () => {
    const onOpenChange = vi.fn();
    render(picker({ selected: ['62'], onOpenChange }));
    const user = userEvent.setup();

    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(boxes(), '닫힌 동안 상자').toHaveLength(0);

    await user.click(toggle());

    expect(onOpenChange).toHaveBeenLastCalledWith(true);
    expect(toggle()).toHaveAttribute('aria-expanded', 'true');
    const g = must(group(), 'group "라벨"');
    const inGroup = within(g).queryAllByRole('checkbox');
    expect(inGroup).toHaveLength(LABELS.length);
    LABELS.forEach((l, i) => {
      expect(inGroup[i]).toHaveAccessibleName(l.name);
    });
    expect(box('긴급')).toBeChecked();
    expect(box('결제')).not.toBeChecked();
    expect(box('모바일')).not.toBeChecked();

    await user.click(toggle());

    expect(onOpenChange).toHaveBeenLastCalledWith(false);
    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(boxes()).toHaveLength(0);
  });

  test.each([
    ['Esc', async (user: ReturnType<typeof userEvent.setup>) => user.keyboard('{Escape}')],
    ['바깥 누르기', async (user: ReturnType<typeof userEvent.setup>) => user.click(screen.getByText('바깥 글'))],
  ])('S6A-AC-80 LabelPicker %s: 닫히고 초점이 "바꾸기" 단추로 돈다. onOpenChange(false)가 불린다', async (_, close) => {
    const onOpenChange = vi.fn();
    render(picker({ onOpenChange }));
    const user = userEvent.setup();
    await user.click(toggle());
    box('결제').focus();

    await close(user);

    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(toggle()).toHaveFocus();
    expect(onOpenChange).toHaveBeenLastCalledWith(false);
    expect(boxes()).toHaveLength(0);
  });

  test('S6A-AC-80 LabelPicker 체크·풀기: 상자를 누를 때마다 onToggle(id, on)이 한 번 불린다', async () => {
    const onToggle = vi.fn<(id: string, on: boolean) => void>();
    render(picker({ open: true, selected: ['62'], onToggle }));
    const user = userEvent.setup();

    await user.click(box('결제'));
    expect(onToggle.mock.calls).toEqual([['61', true]]);

    await user.click(box('긴급'));
    expect(onToggle.mock.calls).toEqual([
      ['61', true],
      ['62', false],
    ]);
  });

  test('S6A-AC-80 LabelPicker 요청 중: busyIds의 줄은 막히고 다른 줄은 막히지 않는다', () => {
    render(picker({ open: true, selected: ['62'], busyIds: ['62', '63'] }));

    expect(isBlocked(box('긴급')), '요청 중인 체크된 줄').toBe(true);
    expect(isBlocked(box('모바일')), '요청 중인 체크 안 된 줄').toBe(true);
    expect(isBlocked(box('결제')), '요청 중이 아닌 줄').toBe(false);
  });

  test('S6A-AC-80 LabelPicker 한도: limit만큼 골랐으면 고르지 않은 줄이 막히고 limitText가 보인다. 고른 줄은 풀 수 있다. 덜 골랐으면 막지 않는다', () => {
    const { rerender } = render(picker({ open: true, limit: 2, selected: ['61', '62'] }));

    expect(isBlocked(box('모바일')), '고르지 않은 줄').toBe(true);
    expect(isBlocked(box('결제')), '고른 줄').toBe(false);
    expect(isBlocked(box('긴급')), '고른 줄').toBe(false);
    shown('라벨은 20개까지 붙입니다.');

    rerender(picker({ open: true, limit: 2, selected: ['61'] }));
    expect(boxes().filter(isBlocked)).toHaveLength(0);
    expect(screen.queryByText('라벨은 20개까지 붙입니다.')).toBeNull();
  });

  test('S6A-AC-80 LabelPicker 거르기: 8개에는 거르기 입력이 없고, 9개면 "라벨 거르기"가 있다. 거르면 줄이 줄고, 맞는 것이 없으면 noMatchText다', async () => {
    const { rerender } = render(picker({ open: true, labels: many(8) }));
    expect(boxes()).toHaveLength(8);
    expect(filterBox(), '8개일 때 거르기 입력').toBeNull();

    rerender(picker({ open: true, labels: many(9) }));
    const user = userEvent.setup();
    const input = must(filterBox(), '거르기 입력 "라벨 거르기"');
    expect(boxes()).toHaveLength(9);

    await user.type(input, '09');
    expect(boxes()).toHaveLength(1);
    expect(boxes()[0]).toHaveAccessibleName('라벨09');
    expect(screen.queryByText('맞는 라벨이 없습니다.')).toBeNull();

    await user.clear(input);
    await user.type(input, '없는 이름');
    expect(boxes()).toHaveLength(0);
    shown('맞는 라벨이 없습니다.');
  });

  test('S6A-AC-80 LabelPicker 빈 목록: 상자 대신 emptyText와 emptyAction이 보인다', () => {
    render(picker({ open: true, labels: [], emptyAction: <a href="/companies/ACME#labels">고객사 설정에서 만들기</a> }));

    shown('이 고객사에 라벨이 없습니다.');
    expect(screen.queryByRole('link', { name: '고객사 설정에서 만들기' })).not.toBeNull();
    expect(boxes()).toHaveLength(0);
  });

  test('S6A-AC-80 LabelPicker 오류: error가 목록 아래에 한 줄로 보인다. 없으면 없다', () => {
    const { rerender } = render(picker({ open: true }));
    expect(screen.queryByText('라벨을 바꾸지 못했습니다. 다시 해 보세요.')).toBeNull();

    rerender(picker({ open: true, error: '라벨을 바꾸지 못했습니다. 다시 해 보세요.' }));

    expect(screen.queryAllByText('라벨을 바꾸지 못했습니다. 다시 해 보세요.')).toHaveLength(1);
    const line = shown('라벨을 바꾸지 못했습니다. 다시 해 보세요.');
    const lastBox = boxes().at(-1) as HTMLElement;
    expect(lastBox.compareDocumentPosition(line) & Node.DOCUMENT_POSITION_FOLLOWING, '오류 줄이 목록 뒤에').toBeTruthy();
  });

  test('S6A-AC-80 LabelPicker 열린 꼴(거르기, 한도, 오류)에 axe 위반이 없다', async () => {
    const labels = many(9);
    render(picker({ open: true, labels, selected: ['100', '101'], limit: 2, busyIds: ['100'], error: '라벨을 바꾸지 못했습니다. 다시 해 보세요.' }));

    expect(boxes()).toHaveLength(9);
    await expectNoAxeViolations();
  });
});
