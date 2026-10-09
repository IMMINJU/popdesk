// screens.md 5절 25 CommentItem, 28 Avatar (D-49). C-10 변이 테스트가 찾은 빈칸:
// 지원팀 댓글의 아바타, 이름 앞뒤 빈칸, 첨부가 없을 때.
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { text } from '../__tests__/support';
import { Avatar } from './Avatar';
import { CommentItem } from './CommentItem';

const T0 = '2026-10-08T03:15:00Z';

describe('CommentItem 동작', () => {
  test('CommentItem 지원팀(D-49): 아바타에도 머리글자를 쓰지 않고 지원팀 표시를 그린다', () => {
    const { container } = render(
      <CommentItem author={{ kind: 'support' }} supportLabel="지원팀" visibility="public" createdAt={T0} body="확인했습니다" />,
    );

    const avatar = container.querySelector('header [aria-hidden="true"]');
    expect(avatar, '머리의 장식 아바타').not.toBeNull();
    expect(text(avatar)).toBe('');
    expect(avatar?.querySelector('svg')).not.toBeNull();
  });

  test('CommentItem 사람: 아바타는 쓴 사람 이름의 첫 글자', () => {
    const { container } = render(
      <CommentItem author={{ kind: 'person', name: '김상담' }} visibility="public" createdAt={T0} body="확인했습니다" />,
    );

    expect(text(container.querySelector('header [aria-hidden="true"]'))).toBe('김');
  });

  test('CommentItem 첨부 없음: attachments를 안 주면 본문 뒤에 빈 자리를 두지 않는다', () => {
    render(<CommentItem author={{ kind: 'person', name: '김상담' }} visibility="public" createdAt={T0} body="본문" />);

    const article = screen.getByRole('article');
    expect(article.lastElementChild?.textContent).toBe('본문');
  });
});

describe('Avatar 동작', () => {
  test('Avatar 머리글자: 이름 앞의 빈칸은 건너뛴다', () => {
    render(<Avatar name="  박고객" />);

    expect(text(screen.getByRole('img', { name: '박고객' }))).toBe('박');
  });
});
