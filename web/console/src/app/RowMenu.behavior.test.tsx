// 1a screens.md C-10 줄 메뉴: 버튼 하나 뒤의 메뉴. 열면 첫 항목에 포커스, 화살표로 옮기고, Esc로 닫으면 포커스가 버튼으로 돌아온다.
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test } from 'vitest';
import { RowMenu } from './RowMenu';

function setup() {
  const chosen: string[] = [];
  const items = ['이름 고치기', '비밀번호 초기화', '정지'].map((label) => ({
    label,
    onSelect: () => {
      chosen.push(label);
    },
  }));
  render(<RowMenu label="이상담 동작" items={items} />);
  return { user: userEvent.setup(), chosen, trigger: screen.getByRole('button', { name: '이상담 동작' }) };
}

describe('RowMenu', () => {
  test('RowMenu 열기: 누르면 menu가 열리고 aria-expanded가 true, 포커스는 첫 항목', async () => {
    const { user, trigger } = setup();
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('menu')).toBeNull();

    await user.click(trigger);

    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getAllByRole('menuitem').map((i) => i.textContent)).toEqual(['이름 고치기', '비밀번호 초기화', '정지']);
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: '이름 고치기' }));
  });

  test('RowMenu 화살표: 아래·위로 옮기고 끝에서 처음으로 돈다. Home·End는 처음과 끝', async () => {
    const { user, trigger } = setup();
    await user.click(trigger);

    await user.keyboard('{ArrowDown}');
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: '비밀번호 초기화' }));
    await user.keyboard('{End}');
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: '정지' }));
    await user.keyboard('{ArrowDown}');
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: '이름 고치기' }));
    await user.keyboard('{ArrowUp}');
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: '정지' }));
    await user.keyboard('{Home}');
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: '이름 고치기' }));
  });

  test('RowMenu Esc: 고르지 않고 닫으며 포커스가 버튼으로 돌아온다', async () => {
    const { user, trigger, chosen } = setup();
    await user.click(trigger);

    await user.keyboard('{Escape}');

    expect(screen.queryByRole('menu')).toBeNull();
    expect(document.activeElement).toBe(trigger);
    expect(chosen).toEqual([]);
  });

  test('RowMenu 고르기: 항목을 누르면 그 동작을 한 번 부르고 닫는다', async () => {
    const { user, trigger, chosen } = setup();
    await user.click(trigger);

    await user.click(screen.getByRole('menuitem', { name: '정지' }));

    expect(chosen).toEqual(['정지']);
    expect(screen.queryByRole('menu')).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });

  test('RowMenu 밖을 누름: 메뉴가 닫힌다', async () => {
    const { user, trigger } = setup();
    await user.click(trigger);

    await user.click(document.body);

    expect(screen.queryByRole('menu')).toBeNull();
  });
});
