// 6a 묶음 screens.md 1절 마감일 고르기, 5절 DueDatePicker: 단추(aria-expanded)로 열고 닫는다. Esc와
// 바깥 누르기로 닫히고 초점이 단추로 돈다. 날짜 입력 하나(min은 힌트), "정하기"가 onSubmit(YYYY-MM-DD),
// 마감일이 있을 때만 "지우기"가 onClear다. busy면 막히고 error는 입력에 이어진다(S6A-AC-80, S6A-AC-86).
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { describedText, expectNoAxeViolations, isBlocked, must } from '../__tests__/support';
import { DueDatePicker, type DueDatePickerProps } from './DueDatePicker';

function picker(over: Partial<DueDatePickerProps> = {}) {
  return (
    <div>
      <p>바깥 글</p>
      <DueDatePicker
        label="마감일"
        toggleLabel="바꾸기"
        value={null}
        min="2026-10-14"
        onSubmit={() => undefined}
        onClear={() => undefined}
        submitLabel="정하기"
        clearLabel="지우기"
        {...over}
      />
    </div>
  );
}

type User = ReturnType<typeof userEvent.setup>;

const toggle = () => screen.getByRole('button', { name: '바꾸기' });
const dateInput = () => screen.queryByLabelText<HTMLInputElement>('마감일');
const submit = () => screen.queryByRole('button', { name: '정하기' });
const clear = () => screen.queryByRole('button', { name: '지우기' });

describe('DueDatePicker', () => {
  test('S6A-AC-80 DueDatePicker 열고 닫기: 단추가 aria-expanded로 열고 닫는다. 열면 이름이 "마감일"인 날짜 입력이 지금 값과 min을 갖고 있다', async () => {
    const onOpenChange = vi.fn();
    render(picker({ value: '2026-10-20', onOpenChange }));
    const user = userEvent.setup();

    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(dateInput(), '닫힌 동안 날짜 입력').toBeNull();

    await user.click(toggle());

    expect(onOpenChange).toHaveBeenLastCalledWith(true);
    expect(toggle()).toHaveAttribute('aria-expanded', 'true');
    const input = must(dateInput(), '날짜 입력 "마감일"');
    expect(input.type).toBe('date');
    expect(input).toHaveValue('2026-10-20');
    expect(input).toHaveAttribute('min', '2026-10-14');

    await user.click(toggle());

    expect(onOpenChange).toHaveBeenLastCalledWith(false);
    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(dateInput()).toBeNull();
  });

  test.each([
    ['Esc', async (user: User) => user.keyboard('{Escape}')],
    ['바깥 누르기', async (user: User) => user.click(screen.getByText('바깥 글'))],
  ])('S6A-AC-80 DueDatePicker %s: 닫히고 초점이 "바꾸기" 단추로 돈다. onOpenChange(false)가 불린다', async (_, close) => {
    const onOpenChange = vi.fn();
    render(picker({ onOpenChange }));
    const user = userEvent.setup();
    await user.click(toggle());
    must(dateInput(), '날짜 입력').focus();

    await close(user);

    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(toggle()).toHaveFocus();
    expect(onOpenChange).toHaveBeenLastCalledWith(false);
    expect(dateInput()).toBeNull();
  });

  test('S6A-AC-80 DueDatePicker 정하기: 날짜를 넣고 "정하기"를 누르면 onSubmit이 YYYY-MM-DD로 한 번 불린다', async () => {
    const onSubmit = vi.fn<(date: string) => void>();
    render(picker({ open: true, onSubmit }));
    const user = userEvent.setup();

    await user.type(must(dateInput(), '날짜 입력'), '2026-10-20');
    await user.click(must(submit(), '"정하기"'));

    expect(onSubmit.mock.calls).toEqual([['2026-10-20']]);
  });

  test('S6A-AC-80 DueDatePicker 지우기: 값이 있을 때만 "지우기"가 있고 누르면 onClear가 한 번 불린다', async () => {
    const onClear = vi.fn();
    const { rerender } = render(picker({ open: true, value: null, onClear }));
    expect(submit(), '"정하기"').not.toBeNull();
    expect(clear(), '값이 없을 때 "지우기"').toBeNull();

    rerender(picker({ open: true, value: '2026-10-20', onClear }));
    const user = userEvent.setup();
    await user.click(must(clear(), '"지우기"'));

    expect(onClear).toHaveBeenCalledTimes(1);
  });

  test('S6A-AC-80 DueDatePicker 요청 중: busy면 날짜 입력, "정하기", "지우기"가 막힌다. 아니면 막히지 않는다', () => {
    const { rerender } = render(picker({ open: true, value: '2026-10-20', busy: true }));

    expect(isBlocked(must(dateInput(), '날짜 입력')), '날짜 입력').toBe(true);
    expect(isBlocked(must(submit(), '"정하기"')), '"정하기"').toBe(true);
    expect(isBlocked(must(clear(), '"지우기"')), '"지우기"').toBe(true);

    rerender(picker({ open: true, value: '2026-10-20', busy: false }));
    expect(isBlocked(must(dateInput(), '날짜 입력'))).toBe(false);
    expect(isBlocked(must(submit(), '"정하기"'))).toBe(false);
    expect(isBlocked(must(clear(), '"지우기"'))).toBe(false);
  });

  test('S6A-AC-80 DueDatePicker 오류: error가 날짜 입력에 이어진 글(aria-describedby)로 보이고 입력이 aria-invalid다. axe 위반이 없다', async () => {
    render(picker({ open: true, value: '2026-10-20', error: '오늘이나 그 뒤의 날짜를 고르세요.' }));

    const input = must(dateInput(), '날짜 입력');
    expect(screen.queryAllByText('오늘이나 그 뒤의 날짜를 고르세요.')).toHaveLength(1);
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(describedText(input)).toContain('오늘이나 그 뒤의 날짜를 고르세요.');
    await expectNoAxeViolations();
  });
});
