// screens.md 5절 17 EmptyState. C-10 변이 테스트가 찾은 빈칸: 화면 전체 제목의 포커스 자리, 빈 자리.
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { EmptyState } from './EmptyState';

describe('EmptyState 동작', () => {
  test('EmptyState 화면 전체: h1은 tabindex=-1이라 화면이 포커스를 옮길 수 있다', () => {
    render(<EmptyState title="문의를 찾을 수 없습니다" headingLevel={1} />);

    expect(screen.getByRole('heading', { level: 1 })).toHaveAttribute('tabindex', '-1');
  });

  test('EmptyState 빈 목록: h2는 포커스 자리가 아니다(tabindex 없음)', () => {
    render(<EmptyState title="문의가 없습니다" />);

    expect(screen.getByRole('heading', { level: 2 })).not.toHaveAttribute('tabindex');
  });

  test('EmptyState 제목만: 설명·동작·아이콘이 없으면 제목 하나만 그린다', () => {
    const { container } = render(<EmptyState title="문의가 없습니다" />);

    expect(container.firstElementChild?.children).toHaveLength(1);
  });
});
