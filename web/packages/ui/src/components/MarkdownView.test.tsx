// screens.md 5절 24 MarkdownView. 허용 목록 렌더(COM-6), 링크는 새 탭(rel="noopener noreferrer"), 긴 코드 블록은 가로 넘김.
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, found, text } from '../__tests__/support';
import { MarkdownView } from './MarkdownView';

describe('MarkdownView', () => {
  test('MarkdownView 서식: 굵게, 기울임, 목록, 인용을 요소로 그리고 마크다운 기호는 남기지 않는다', () => {
    const { container } = render(<MarkdownView source={'**굵게** 와 *기울임*\n\n- 하나\n- 둘\n\n> 인용한 글'} />);

    expect(text(container.querySelector('strong'))).toBe('굵게');
    expect(text(container.querySelector('em'))).toBe('기울임');
    expect(Array.from(container.querySelectorAll('ul > li')).map((li) => text(li))).toEqual(['하나', '둘']);
    expect(text(container.querySelector('blockquote'))).toBe('인용한 글');
    expect(text(container)).not.toContain('**');
  });

  test('MarkdownView 링크: 새 탭으로 열고 rel="noopener noreferrer"', async () => {
    render(<MarkdownView source="[설치 안내](https://example.com/help)를 따라 했습니다." />);

    const a = await found(() => screen.queryByRole('link', { name: '설치 안내' }), '링크 "설치 안내"');
    expect(a).toHaveAttribute('href', 'https://example.com/help');
    expect(a).toHaveAttribute('target', '_blank');
    expect((a.getAttribute('rel') ?? '').split(/\s+/)).toEqual(expect.arrayContaining(['noopener', 'noreferrer']));
  });

  test('MarkdownView 허용 목록: javascript: 주소는 링크로 만들지 않는다', async () => {
    const { container } = render(<MarkdownView source={'[좋은 링크](https://example.com) [나쁜 링크](javascript:alert(1))'} />);

    await found(() => screen.queryByRole('link', { name: '좋은 링크' }), '링크 "좋은 링크"');
    const hrefs = Array.from(container.querySelectorAll('a')).map((a) => a.getAttribute('href') ?? '');
    expect(hrefs.filter((h) => /^\s*javascript:/i.test(h))).toEqual([]);
  });

  test('MarkdownView 허용 목록: 원문 HTML은 요소로 만들지 않는다(script, img onerror, iframe)', () => {
    const { container } = render(
      <MarkdownView source={'**본문**\n\n<script>alert(1)</script>\n\n<img src=x onerror="alert(1)">\n\n<iframe src="https://evil.example"></iframe>'} />,
    );

    expect(text(container.querySelector('strong')), '마크다운은 그린다').toBe('본문');
    expect(container.querySelectorAll('script, iframe, [onerror]')).toHaveLength(0);
    expect(container.querySelectorAll('img[src="x"]')).toHaveLength(0);
  });

  test('MarkdownView 코드 블록: pre > code로 그리고 줄을 바꾸지 않은 원문 그대로 둔다', () => {
    const long = `const value = "${'x'.repeat(200)}";`;
    const { container } = render(<MarkdownView source={`\`\`\`\n${long}\n\`\`\``} />);

    const pre = container.querySelector('pre');
    expect(pre, '<pre>').not.toBeNull();
    expect(pre?.querySelector('code')?.textContent.trim()).toBe(long);
  });

  test('MarkdownView axe: 서식·링크·코드 블록에 접근성 위반이 없다', async () => {
    render(<MarkdownView source={'**굵게**\n\n[설치 안내](https://example.com/help)\n\n```\nnpm run build\n```'} />);
    await found(() => screen.queryByRole('link', { name: '설치 안내' }), '링크');

    await expectNoAxeViolations();
  });
});
