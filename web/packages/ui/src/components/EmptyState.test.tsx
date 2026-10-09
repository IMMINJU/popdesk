// screens.md 5절 16 EmptyState. 아이콘, 제목, 설명, 동작 버튼(선택). 빈 목록, 404, 권한 없음.
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, spokenText } from '../__tests__/support';
import { EmptyState } from './EmptyState';

describe('EmptyState', () => {
  test('EmptyState 빈 목록: 제목은 h2, 설명과 동작 링크', () => {
    render(
      <EmptyState
        title="아직 올린 문의가 없습니다."
        description="궁금한 것이 있으면 문의를 올리세요."
        action={<a href="/issues/new">문의 올리기</a>}
      />,
    );

    expect(screen.getByRole('heading', { level: 2, name: '아직 올린 문의가 없습니다.' })).toBeInTheDocument();
    expect(screen.getByText('궁금한 것이 있으면 문의를 올리세요.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '문의 올리기' })).toHaveAttribute('href', '/issues/new');
  });

  test('EmptyState 화면 전체(404·권한 없음): headingLevel 1이면 h1', () => {
    render(<EmptyState title="찾을 수 없습니다" headingLevel={1} action={<a href="/issues">목록으로</a>} />);

    expect(screen.getByRole('heading', { level: 1, name: '찾을 수 없습니다' })).toBeInTheDocument();
  });

  test('EmptyState 아이콘: 장식이라 읽지 않는다', () => {
    const { container } = render(<EmptyState title="권한이 없습니다" icon={<svg data-icon="lock" />} />);

    const icon = container.querySelector('svg');
    expect(icon?.closest('[aria-hidden="true"]') ?? null, '아이콘은 aria-hidden').not.toBeNull();
    expect(spokenText(container)).toBe('권한이 없습니다');
  });

  test('EmptyState axe: 아이콘·설명·동작이 있는 빈 상태에 접근성 위반이 없다', async () => {
    render(
      <EmptyState
        title="첫 고객사를 만드세요."
        description="고객사를 만들어야 고객을 초대할 수 있습니다."
        icon={<svg data-icon="building" />}
        action={<button type="button">고객사 만들기</button>}
      />,
    );

    await expectNoAxeViolations();
  });
});
