// 2번 묶음 design.md 6절: RowMenu를 콘솔 앱에서 @popdesk/ui로 옮겼다(props는 그대로).
// 패키지가 내보내는 것으로 그려지고, 버튼과 목록에 접근 가능한 이름이 있다. 키보드 동작은 콘솔의 RowMenu.behavior.test.tsx가 본다.
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations } from '../__tests__/support';
import { RowMenu, type RowMenuItem } from '../index';

const ITEMS: RowMenuItem[] = [
  { label: '초대 다시 보내기', onSelect: () => undefined },
  { label: '초대 취소', onSelect: () => undefined, tone: 'danger' },
];

describe('RowMenu (@popdesk/ui)', () => {
  test('RowMenu 이름: 버튼과 열린 목록이 label을 접근 가능한 이름으로 갖고, 항목은 준 순서대로다', async () => {
    render(<RowMenu label="박초대 동작" items={ITEMS} />);
    const user = userEvent.setup();

    const trigger = screen.getByRole('button', { name: '박초대 동작' });
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu');
    await user.click(trigger);

    expect(screen.getByRole('menu', { name: '박초대 동작' })).toBeInTheDocument();
    expect(screen.getAllByRole('menuitem').map((i) => i.textContent)).toEqual(['초대 다시 보내기', '초대 취소']);
  });

  test('RowMenu axe: 닫혔을 때와 열렸을 때 접근성 위반이 없다', async () => {
    render(<RowMenu label="박초대 동작" items={ITEMS} />);
    const user = userEvent.setup();

    await expectNoAxeViolations();
    await user.click(screen.getByRole('button', { name: '박초대 동작' }));
    await expectNoAxeViolations();
  });
});
