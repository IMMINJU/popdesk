// 3번 묶음 screens.md 3절 SearchField. 이름표가 있는 검색 입력, 입력을 멈춘 뒤와 Enter에 알린다.
import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { describedText, expectNoAxeViolations } from '../__tests__/support';
import { SearchField } from './SearchField';

const CLEAR = '검색어 지우기';

const box = () => screen.getByRole<HTMLInputElement>('searchbox', { name: '문의 검색' });
const clearButton = () => screen.queryByRole('button', { name: CLEAR });

/**
 * Fake timers that still move with real time, so Testing Library's own waits end,
 * with a user whose key presses move them. The checks below leave 100ms or more of room for that.
 */
function fakeUser() {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  return userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) });
}

/** What typing leaves in the box, in one step and without waiting: for a clock that stands still. */
function enter(value: string) {
  fireEvent.change(box(), { target: { value } });
}

function pass(ms: number) {
  act(() => {
    vi.advanceTimersByTime(ms);
  });
}

/** A screen that keeps the searched text, as C-3 keeps it in the address. */
function Screen(props: { first?: string; onSearch: (value: string) => void }) {
  const [value, setValue] = useState(props.first ?? '');
  return (
    <SearchField
      label="문의 검색"
      value={value}
      onSearch={(next) => {
        setValue(next);
        props.onSearch(next);
      }}
    />
  );
}

afterEach(() => {
  vi.useRealTimers();
});

describe('SearchField', () => {
  test('SearchField 꼴: role="search" 안의 이름표 있는 검색 입력. hint는 aria-describedby로 잇고 maxLength는 기본 100, disabled면 막힌다', () => {
    const { rerender } = render(<SearchField label="문의 검색" value="" onSearch={() => undefined} hint="제목과 내용에서 찾습니다" />);

    expect(box()).toHaveAttribute('type', 'search');
    expect(screen.getByRole('search').contains(box()), 'role="search" 안').toBe(true);
    expect(describedText(box())).toBe('제목과 내용에서 찾습니다');
    expect(box()).toHaveAttribute('maxlength', '100');
    expect(box()).toBeEnabled();

    rerender(<SearchField label="문의 검색" value="" onSearch={() => undefined} maxLength={40} disabled />);
    expect(box()).toHaveAttribute('maxlength', '40');
    expect(box()).not.toHaveAttribute('aria-describedby');
    expect(box()).toBeDisabled();
  });

  test.each([
    ['기본 300ms', undefined, 300],
    ['delayMs 500', 500, 500],
  ])('SearchField 지연(%s): 입력을 멈추고 그 시간이 지나야 onSearch를 한 번 부른다. 사라진 뒤에는 부르지 않는다', (_, delayMs, wait) => {
    // A clock that only `pass` moves. With one that also moves with real time, a busy machine eats the room below.
    vi.useFakeTimers();
    const onSearch = vi.fn();
    const { unmount } = render(<SearchField label="문의 검색" value="" onSearch={onSearch} {...(delayMs === undefined ? {} : { delayMs })} />);

    enter('결');
    pass(wait - 100);
    enter('결제');
    expect(box()).toHaveValue('결제');
    // The clock starts again at the last key: counted from the first one, the time is over here.
    pass(wait - 1);
    expect(onSearch, '시간이 지나기 전').not.toHaveBeenCalled();

    pass(1);
    expect(onSearch.mock.calls).toEqual([['결제']]);

    enter('결제 오류');
    unmount();
    pass(wait * 2);
    expect(onSearch.mock.calls, '사라진 뒤').toEqual([['결제']]);
  });

  test('SearchField Enter: 기다리지 않고 바로 부르고, 지연이 지나도 같은 글자로 다시 부르지 않는다', async () => {
    const user = fakeUser();
    const onSearch = vi.fn();
    render(<SearchField label="문의 검색" value="" onSearch={onSearch} />);

    await user.type(box(), '로그인{Enter}');
    expect(onSearch.mock.calls).toEqual([['로그인']]);

    pass(600);
    expect(onSearch.mock.calls).toEqual([['로그인']]);
  });

  test('SearchField 지우기: 값이 있을 때만 "검색어 지우기" 버튼이 있고, 누르면 입력을 비우고 빈 값으로 바로 부른다', async () => {
    const user = fakeUser();
    const onSearch = vi.fn();
    render(<Screen onSearch={onSearch} />);
    expect(clearButton(), '빈 입력').toBeNull();

    await user.type(box(), '환불');
    expect(clearButton(), '글자를 친 뒤').not.toBeNull();
    pass(300);
    expect(onSearch.mock.calls).toEqual([['환불']]);

    await user.click(screen.getByRole('button', { name: CLEAR }));
    expect(onSearch.mock.calls).toEqual([['환불'], ['']]);
    expect(box()).toHaveValue('');
    expect(clearButton(), '지운 뒤').toBeNull();

    pass(600);
    expect(onSearch.mock.calls, '지운 뒤에 더 부르지 않는다').toEqual([['환불'], ['']]);
  });

  test('SearchField 밖에서 바뀐 value: 입력의 글자가 따라간다(뒤로 가기, 필터 지우기)', () => {
    const { rerender } = render(<SearchField label="문의 검색" value="결제" onSearch={() => undefined} />);
    expect(box()).toHaveValue('결제');
    expect(clearButton(), '처음부터 값이 있음').not.toBeNull();

    rerender(<SearchField label="문의 검색" value="환불" onSearch={() => undefined} />);
    expect(box()).toHaveValue('환불');

    rerender(<SearchField label="문의 검색" value="" onSearch={() => undefined} />);
    expect(box()).toHaveValue('');
    expect(clearButton()).toBeNull();
  });

  // One at a time: a page has one search landmark (the landmark has no name of its own).
  test('SearchField axe: 값과 도움말이 있는 것, 막힌 빈 것에 접근성 위반이 없다', async () => {
    const { rerender } = render(<SearchField label="문의 검색" value="kim" onSearch={() => undefined} hint="제목과 내용에서 찾습니다" />);
    expect(clearButton(), '값이 있는 것의 지우기 버튼').not.toBeNull();
    await expectNoAxeViolations();

    rerender(<SearchField label="문의 검색" value="" onSearch={() => undefined} disabled />);
    await expectNoAxeViolations();
  });
});
