// screens.md 5절 26 RelativeTime. C-10 변이 테스트가 찾은 빈칸: 주·달·해 단위와 단위 경계, "어제" 같은 말,
// 언어 고르는 순서, 잘못된 시각, 절대 시각을 숨기는 때, 줄 안(focusable={false})의 시각.
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, test } from 'vitest';
import { found, text } from '../__tests__/support';
import { RelativeTime } from './RelativeTime';

const NOW = new Date('2026-10-08T06:15:00Z');
const ago = (seconds: number) => new Date(NOW.getTime() - seconds * 1000).toISOString();
const DAY = 24 * 60 * 60;

const time = () => found(() => document.querySelector('time'), '<time>');

afterEach(() => {
  document.documentElement.lang = '';
});

describe('RelativeTime 동작', () => {
  test.each([
    [60, '1분 전'],
    [DAY, '어제'],
    [14 * DAY, '2주 전'],
    [90 * DAY, '3개월 전'],
    [800 * DAY, '2년 전'],
  ])('RelativeTime 단위: %i초 전은 "%s"', (seconds, want) => {
    render(<RelativeTime dateTime={ago(seconds)} now={NOW} locale="ko" />);

    expect(text(document.querySelector('time'))).toBe(want);
  });

  test('RelativeTime 언어: locale이 없으면 문서 언어를 쓴다(ja)', () => {
    document.documentElement.lang = 'ja';
    render(<RelativeTime dateTime={ago(3 * 60 * 60)} now={NOW} />);

    expect(text(document.querySelector('time'))).toBe('3 時間前');
  });

  test('RelativeTime 언어: locale도 문서 언어도 없으면 브라우저 언어', () => {
    document.documentElement.lang = '';
    render(<RelativeTime dateTime={ago(3 * 60 * 60)} now={NOW} />);

    const want = new Intl.RelativeTimeFormat(navigator.language, { numeric: 'auto' }).format(-3, 'hour');
    expect(text(document.querySelector('time'))).toBe(want);
  });

  test('RelativeTime 언어: locale을 주면 문서 언어보다 먼저 쓴다', () => {
    document.documentElement.lang = 'ja';
    render(<RelativeTime dateTime={ago(3 * 60 * 60)} now={NOW} locale="ko" />);

    expect(text(document.querySelector('time'))).toBe('3시간 전');
  });

  test('RelativeTime 절대 시각: 날짜와 시각을 함께 title에 둔다', async () => {
    render(<RelativeTime dateTime="2026-10-08T03:15:00Z" now={NOW} locale="ko" />);

    const want = new Intl.DateTimeFormat('ko', { dateStyle: 'long', timeStyle: 'short' }).format(new Date('2026-10-08T03:15:00Z'));
    expect(await time()).toHaveAttribute('title', want);
    expect(want).toContain('2026년 10월 8일');
  });

  test('RelativeTime 잘못된 시각: 읽을 수 없는 값이면 그 글자를 그대로 <time>에 둔다', () => {
    render(<RelativeTime dateTime="언젠가" now={NOW} locale="ko" />);

    const t = document.querySelector('time');
    expect(text(t)).toBe('언젠가');
    expect(t).toHaveAttribute('datetime', '언젠가');
  });

  test('RelativeTime 숨김: 포커스를 잃거나 마우스가 떠나면 절대 시각 풍선을 거둔다', async () => {
    render(<RelativeTime dateTime={ago(3 * 60 * 60)} now={NOW} locale="ko" />);
    const user = userEvent.setup();
    const t = await time();

    await user.tab();
    expect(screen.getByRole('tooltip')).toBeInTheDocument();
    expect(t.getAttribute('aria-describedby')).toBe(screen.getByRole('tooltip').id);
    await user.tab();
    expect(screen.queryByRole('tooltip')).toBeNull();
    expect(t).not.toHaveAttribute('aria-describedby');

    fireEvent.mouseEnter(t);
    expect(screen.getByRole('tooltip')).toBeInTheDocument();
    fireEvent.mouseLeave(t);
    expect(screen.queryByRole('tooltip')).toBeNull();
  });

  test('RelativeTime 처음: 마우스도 포커스도 없으면 풍선이 없다', async () => {
    render(<RelativeTime dateTime={ago(3 * 60 * 60)} now={NOW} locale="ko" />);

    expect(await time()).not.toHaveAttribute('aria-describedby');
    expect(screen.queryByRole('tooltip')).toBeNull();
  });

  test('RelativeTime 줄 안(focusable={false}): Tab이 닿지 않고 마우스를 올려도 풍선 없이 title만 둔다', async () => {
    render(<RelativeTime dateTime={ago(3 * 60 * 60)} now={NOW} locale="ko" focusable={false} />);
    const t = await time();

    expect(t).not.toHaveAttribute('tabindex');
    fireEvent.mouseEnter(t);
    expect(screen.queryByRole('tooltip')).toBeNull();
    expect(t).not.toHaveAttribute('aria-describedby');
    expect(t.getAttribute('title')).toContain('2026');
  });
});
