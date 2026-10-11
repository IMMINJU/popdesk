// 1a design.md 5절 Avatar: 사진 주소를 받으면 그림, 못 받으면(null) 머리글자(S1A-AC-60, ACCT-8).
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { text } from '../__tests__/support';
import { Avatar } from './Avatar';

const PHOTO = '/api/console/photos/11?v=2026-10-08T03%3A15%3A00Z';

describe('Avatar 사진', () => {
  test('S1A-AC-60 Avatar 사진 있음: 그 주소의 그림을 그리고 머리글자는 없다. 이름이 대체 글자다', () => {
    const { rerender } = render(<Avatar name="김고객" photoUrl={PHOTO} />);

    const avatar = screen.getByRole('img', { name: '김고객' });
    expect(Array.from(avatar.querySelectorAll('img')).map((img) => img.getAttribute('src'))).toEqual([PHOTO]);
    expect(text(avatar)).toBe('');

    // 반대 경우: 주소가 null이면 그림을 청하지 않는다. 머리글자는 Avatar.test.tsx가 본다.
    rerender(<Avatar name="김고객" photoUrl={null} />);
    expect(screen.getByRole('img', { name: '김고객' }).querySelectorAll('img')).toHaveLength(0);
  });
});
