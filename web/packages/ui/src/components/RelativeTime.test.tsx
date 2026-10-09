// screens.md 5절 26 RelativeTime. 상대 시각 글자와 <time datetime>, 마우스·포커스에 절대 시각.
import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test } from 'vitest';
import { describedText, expectNoAxeViolations, found, text } from '../__tests__/support';
import { RelativeTime } from './RelativeTime';

const T0 = '2026-10-08T03:15:00Z';
const NOW = new Date('2026-10-08T06:15:00Z');

const time = () => found(() => document.querySelector('time'), '<time>');

/** Where an absolute time can show: `title`, an `aria-describedby` text, or text on the page. */
function absoluteShown(el: Element): string {
  return [el.getAttribute('title') ?? '', describedText(el), text(document.body)].join(' ');
}

describe('RelativeTime', () => {
  test('RelativeTime 상대 시각: 세 시간 전이면 "3시간 전", datetime은 그 시각', async () => {
    render(<RelativeTime dateTime={T0} now={NOW} locale="ko" />);

    const t = await time();
    expect(text(t)).toBe('3시간 전');
    expect(new Date(t.getAttribute('datetime') ?? '').toISOString()).toBe(new Date(T0).toISOString());
  });

  test('RelativeTime 단위: 방금, 분, 일 단위로 바꾼다', () => {
    const cases: [string, RegExp][] = [
      ['2026-10-08T06:14:30Z', /방금|30초 전/],
      ['2026-10-08T06:10:00Z', /^5분 전$/],
      ['2026-10-06T06:15:00Z', /^(2일 전|그저께)$/],
    ];
    for (const [at, want] of cases) {
      const { container, unmount } = render(<RelativeTime dateTime={at} now={NOW} locale="ko" />);
      expect(text(container), at).toMatch(want);
      unmount();
    }
  });

  test('RelativeTime 언어: locale이 없으면 문서 언어(ko)를 쓴다', () => {
    document.documentElement.lang = 'ko';
    const { container } = render(<RelativeTime dateTime={T0} now={NOW} />);

    expect(text(container)).toBe('3시간 전');
  });

  test('RelativeTime 포커스: 키보드로 닿고(tabindex=0) 포커스하면 절대 시각(2026년 10월 8일)이 보인다', async () => {
    render(<RelativeTime dateTime={T0} now={NOW} locale="ko" />);
    const user = userEvent.setup();
    const t = await time();

    const focusable = t.closest('[tabindex="0"]') ?? (t.getAttribute('tabindex') === '0' ? t : null);
    expect(focusable, '포커스가 닿는 자리(tabindex=0)').not.toBeNull();
    await user.tab();
    expect(absoluteShown(t)).toMatch(/2026\.?\s*(년\s*)?10\.?\s*(월\s*)?8/);
  });

  test('RelativeTime 마우스: 올리면 절대 시각이 보인다', async () => {
    render(<RelativeTime dateTime={T0} now={NOW} locale="ko" />);
    const user = userEvent.setup();
    const t = await time();

    await user.hover(t);
    expect(absoluteShown(t)).toMatch(/2026\.?\s*(년\s*)?10\.?\s*(월\s*)?8/);
  });

  test('RelativeTime axe: 접근성 위반이 없다', async () => {
    render(<RelativeTime dateTime={T0} now={NOW} locale="ko" />);
    await time();

    await expectNoAxeViolations();
  });
});
