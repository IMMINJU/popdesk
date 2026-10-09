// screens.md 5절 18 Badge. 비공개, 내부, 수락 전, 설정 없음. 내부는 자물쇠 아이콘과 "내부" 글자, 왼쪽 띠 없음.
import { render } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, spokenText } from '../__tests__/support';
import { Badge } from './Badge';

describe('Badge', () => {
  test('Badge 글자: 늘 글자를 보인다(비공개, 수락 전, 설정 없음)', () => {
    const { container } = render(
      <div>
        <Badge tone="neutral">비공개</Badge>
        <Badge tone="warning">수락 전</Badge>
        <Badge tone="neutral">설정 없음</Badge>
      </div>,
    );

    expect(spokenText(container)).toBe('비공개수락 전설정 없음');
  });

  test('Badge 내부: 자물쇠 아이콘을 따로 주지 않아도 그리고, 아이콘은 읽지 않으며 글자 "내부"만 읽는다', () => {
    const { container } = render(<Badge tone="internal">내부</Badge>);

    expect(container.querySelectorAll('[aria-hidden="true"]').length, '자물쇠 아이콘(aria-hidden)').toBeGreaterThan(0);
    expect(spokenText(container)).toBe('내부');
  });

  test('Badge 아이콘: 넘겨 준 아이콘은 장식이라 읽지 않는다', () => {
    const { container } = render(
      <Badge tone="accent" icon={<svg data-icon="lock" />}>
        비공개
      </Badge>,
    );

    const icon = container.querySelector('svg');
    expect(icon?.closest('[aria-hidden="true"]') ?? null, '아이콘은 aria-hidden').not.toBeNull();
    expect(spokenText(container)).toBe('비공개');
  });

  test('Badge axe: 네 가지 색에 접근성 위반이 없다', async () => {
    render(
      <div>
        <Badge tone="neutral">설정 없음</Badge>
        <Badge tone="accent">비공개</Badge>
        <Badge tone="warning">수락 전</Badge>
        <Badge tone="internal">내부</Badge>
      </div>,
    );

    await expectNoAxeViolations();
  });
});
