// screens.md 5절 6 Checkbox. 직접 그린 체크박스(tokens.md 9절)지만 역할은 checkbox다.
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, test, vi } from 'vitest';
import { describedText, expectNoAxeViolations } from '../__tests__/support';
import { Checkbox } from './Checkbox';

function Controlled(props: { disabled?: boolean }) {
  const [checked, setChecked] = useState(false);
  return (
    <Checkbox
      label="비공개"
      description="같은 회사 동료에게 보이지 않습니다"
      checked={checked}
      onChange={setChecked}
      disabled={props.disabled ?? false}
    />
  );
}

const box = () => screen.getByRole('checkbox', { name: '비공개' });

describe('Checkbox', () => {
  test('Checkbox 꺼짐·켜짐: 이름표를 누르면 켜지고 다시 누르면 꺼진다', async () => {
    render(<Controlled />);
    const user = userEvent.setup();

    expect(box()).not.toBeChecked();
    await user.click(screen.getByText('비공개'));
    expect(box()).toBeChecked();
    await user.click(box());
    expect(box()).not.toBeChecked();
  });

  test('Checkbox 키보드: 포커스를 받고 Space로 바꾼다', async () => {
    render(<Controlled />);
    const user = userEvent.setup();

    await user.tab();
    expect(document.activeElement).toBe(box());
    await user.keyboard(' ');
    expect(box()).toBeChecked();
  });

  test('Checkbox 설명: "같은 회사 동료에게 보이지 않습니다"를 aria-describedby로 잇는다', () => {
    render(<Controlled />);

    expect(describedText(box())).toContain('같은 회사 동료에게 보이지 않습니다');
  });

  test('Checkbox 막힘: disabled면 눌러도 onChange를 부르지 않는다', async () => {
    const onChange = vi.fn();
    render(<Checkbox label="비공개" checked={false} onChange={onChange} disabled />);
    const user = userEvent.setup();

    expect(box()).toBeDisabled();
    await user.click(box());
    expect(onChange).not.toHaveBeenCalled();
  });

  test('Checkbox axe: 꺼짐·켜짐·막힘에 접근성 위반이 없다', async () => {
    render(
      <div>
        <Checkbox label="비공개" description="같은 회사 동료에게 보이지 않습니다" checked={false} onChange={() => undefined} />
        <Checkbox label="켜진 것" checked onChange={() => undefined} />
        <Checkbox label="막힌 것" checked={false} onChange={() => undefined} disabled />
      </div>,
    );

    await expectNoAxeViolations();
  });
});
