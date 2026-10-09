// verify.md 6절 V-1. 고객 글 하나로 상담원 콘솔이 멈추지 않는다: 제목 줄 끝의 `#`을 지우는 데
// 드는 시간이 줄 길이에 비례하고, 인용 중첩은 10겹을 넘지 않는다(상한은 메인이 정함).
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { text } from '../__tests__/support';
import { MarkdownView } from './MarkdownView';

/** 본문 상한(10,000자)에 가까운 9,999자 한 줄. 끝의 `#`을 정규식으로 지우면 되짚기가 길이의 세제곱으로 는다. */
const SLOW_HEADING = '# a' + ' '.repeat(3332) + '#'.repeat(3332) + ' '.repeat(3331) + 'x';

const LIMIT_MS = 200;
const QUOTE_DEPTH_LIMIT = 10;

/** Deepest chain of `blockquote` inside `root`. */
function quoteDepth(root: Element): number {
  let deepest = 0;
  root.querySelectorAll('blockquote').forEach((q) => {
    let depth = 0;
    for (let el: Element | null = q; el && el !== root; el = el.parentElement) {
      if (el.tagName === 'BLOCKQUOTE') depth += 1;
    }
    deepest = Math.max(deepest, depth);
  });
  return deepest;
}

describe('MarkdownView 긴 입력', () => {
  test(`C-4 V-1 MarkdownView: 9,999자 제목 줄(# a, 공백, #, 공백, x)을 ${String(LIMIT_MS)}ms 안에 그린다`, { timeout: 5_000 }, () => {
    expect(SLOW_HEADING).toHaveLength(9_999);
    // 처음 그리기의 준비 비용을 재는 시간에서 뺀다.
    render(<MarkdownView source="# 준비" />).unmount();

    const started = performance.now();
    const { container } = render(<MarkdownView source={SLOW_HEADING} />);
    const elapsed = performance.now() - started;

    expect(elapsed, `그리는 데 걸린 시간(ms)`).toBeLessThan(LIMIT_MS);
    expect(container.querySelector('h3'), '제목 h3').not.toBeNull();
  });

  test('C-4 V-1 MarkdownView: 제목 끝의 # 묶음은 지금처럼 지운다(## 제목 ##)', () => {
    render(<MarkdownView source="## 제목 ##" />);

    const heading = screen.getByRole('heading', { name: '제목' });
    expect(text(heading)).toBe('제목');
    expect(heading.tagName).toBe('H4');
  });

  test(`C-4 V-1 MarkdownView: > 5,000개 인용도 예외 없이 그리고, 중첩 blockquote는 ${String(QUOTE_DEPTH_LIMIT)}겹 이하`, { timeout: 5_000 }, () => {
    const source = '>'.repeat(5_000) + ' x';
    let container: HTMLElement | undefined;
    expect(() => {
      container = render(<MarkdownView source={source} />).container;
    }).not.toThrow();

    expect(container, '그린 결과').toBeDefined();
    expect(quoteDepth(container as HTMLElement), '가장 깊은 blockquote 중첩').toBeLessThanOrEqual(QUOTE_DEPTH_LIMIT);
  });
});
