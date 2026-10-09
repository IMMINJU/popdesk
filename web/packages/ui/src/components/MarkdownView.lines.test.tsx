// V-1 follow-up: the other patterns in markdown.tsx that used to backtrack on a long
// line (fence info, link title, bare URL end) keep their output and stay fast.
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { MarkdownView } from './MarkdownView';

const LIMIT_MS = 200;
/**
 * Longer than a body may be (10,000): a quadratic pattern still takes well under
 * the limit at 10,000 characters, so only a longer line tells it from a linear one.
 */
const LONG = 50_000;

/** Milliseconds `MarkdownView` takes to draw `source`, after a warm-up draw. */
function timed(source: string): number {
  render(<MarkdownView source="준비" />).unmount();
  const started = performance.now();
  render(<MarkdownView source={source} />);
  return performance.now() - started;
}

describe('MarkdownView 긴 줄의 다른 패턴', () => {
  test('MarkdownView 코드 울타리: 언어 이름은 앞뒤 공백을 빼고 읽고, 이름 뒤에 다른 글자가 있으면 울타리가 아니다', () => {
    const { container } = render(<MarkdownView source={'```  ts  \nconst a = 1;\n```\n\n``` ts !\nx'} />);
    const codes = container.querySelectorAll('pre > code');
    expect(codes).toHaveLength(1);
    expect(codes[0]?.getAttribute('data-lang')).toBe('ts');
  });

  test(`MarkdownView 코드 울타리: 공백 ${String(LONG)}자 뒤 다른 글자가 오는 줄을 ${String(LIMIT_MS)}ms 안에 그린다`, () => {
    const elapsed = timed('```' + ' '.repeat(LONG) + '!');
    expect(elapsed, '그리는 데 걸린 시간(ms)').toBeLessThan(LIMIT_MS);
  });

  test('MarkdownView 링크 제목: 주소 뒤 "제목"은 주소에서 뺀다', () => {
    render(<MarkdownView source={'[문서](https://example.test/a  "제목")'} />);
    expect(screen.getByRole('link', { name: '문서' }).getAttribute('href')).toBe('https://example.test/a');
  });

  test(`MarkdownView 링크 제목: 공백 ${String(LONG)}자가 든 링크를 ${String(LIMIT_MS)}ms 안에 그린다`, () => {
    const elapsed = timed('[a](https://example.test/' + ' '.repeat(LONG) + 'x)');
    expect(elapsed, '그리는 데 걸린 시간(ms)').toBeLessThan(LIMIT_MS);
  });

  test('MarkdownView 맨 주소: 끝의 문장부호는 링크에서 뺀다', () => {
    render(<MarkdownView source="여기 https://example.test/a?b=1). 보세요" />);
    expect(screen.getByRole('link').getAttribute('href')).toBe('https://example.test/a?b=1');
  });

  test(`MarkdownView 맨 주소: 마침표 ${String(LONG)}개 뒤 글자가 오는 주소를 ${String(LIMIT_MS)}ms 안에 그린다`, () => {
    const elapsed = timed('https://example.test/' + '.'.repeat(LONG) + 'b');
    expect(elapsed, '그리는 데 걸린 시간(ms)').toBeLessThan(LIMIT_MS);
  });
});
