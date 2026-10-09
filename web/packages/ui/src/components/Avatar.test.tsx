// screens.md 5절 30 Avatar. 머리글자, 지원팀 표시. 옆에 이름 글자가 있으면 장식(tokens.md 9절).
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, spokenText, text } from '../__tests__/support';
import { Avatar } from './Avatar';

describe('Avatar', () => {
  test('Avatar 머리글자: 사진이 없으면 이름의 첫 글자, 이름만 있을 때는 이름이 대체 글자', () => {
    render(<Avatar name="김고객" />);

    const img = screen.getByRole('img', { name: '김고객' });
    expect(text(img)).toBe('김');
  });

  test('Avatar 장식: 옆에 이름이 있으면 읽지 않는다(이름을 두 번 읽지 않는다)', () => {
    const { container } = render(
      <p>
        <Avatar name="김고객" decorative /> 김고객
      </p>,
    );

    expect(screen.queryByRole('img')).toBeNull();
    expect(spokenText(container)).toBe('김고객');
  });

  test('Avatar 지원팀 표시: 사진과 이름 대신 지원팀 표시를 그리고 사람 이름 머리글자는 없다', () => {
    render(<Avatar name="지원팀" kind="support" />);

    const img = screen.getByRole('img', { name: '지원팀' });
    expect(text(img)).not.toBe('지');
  });

  test('Avatar axe: 머리글자·장식·지원팀에 접근성 위반이 없다', async () => {
    render(
      <div>
        <Avatar name="김고객" size="sm" />
        <span>
          <Avatar name="박동료" decorative /> 박동료
        </span>
        <Avatar name="지원팀" kind="support" />
      </div>,
    );

    await expectNoAxeViolations();
  });
});
