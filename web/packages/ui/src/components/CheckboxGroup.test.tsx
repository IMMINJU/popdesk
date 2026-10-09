// screens.md 5절 6 CheckboxGroup. C-10 초대의 "소속 고객사"(fieldset과 legend).
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, test } from 'vitest';
import { describedText, expectNoAxeViolations, must } from '../__tests__/support';
import { CheckboxGroup, type CheckboxGroupProps } from './CheckboxGroup';

const companies = [
  { value: 'ACME', label: '에이크미' },
  { value: 'BETA', label: '베타상사' },
  { value: 'GAMMA', label: '감마', disabled: true },
];

function Controlled(props: Partial<Omit<CheckboxGroupProps, 'value' | 'onChange'>>) {
  const [value, setValue] = useState<string[]>([]);
  return (
    <>
      <CheckboxGroup legend="소속 고객사" options={companies} {...props} value={value} onChange={setValue} />
      <output aria-label="고른 값">{value.join(',')}</output>
    </>
  );
}

const group = () => screen.getByRole('group', { name: '소속 고객사' });

describe('CheckboxGroup', () => {
  test('CheckboxGroup 묶음: legend가 묶음 이름이고 여러 개를 켤 수 있다', async () => {
    render(<Controlled />);
    const user = userEvent.setup();

    await user.click(within(group()).getByRole('checkbox', { name: '에이크미' }));
    await user.click(within(group()).getByRole('checkbox', { name: '베타상사' }));
    expect(screen.getByLabelText('고른 값')).toHaveTextContent('ACME,BETA');
    await user.click(within(group()).getByRole('checkbox', { name: '에이크미' }));
    expect(screen.getByLabelText('고른 값')).toHaveTextContent('BETA');
  });

  test('CheckboxGroup 키보드: Tab으로 칸마다 가고 Space로 켠다, 막힌 칸은 건너뛴다', async () => {
    render(<Controlled />);
    const user = userEvent.setup();

    await user.tab();
    expect(document.activeElement).toBe(within(group()).getByRole('checkbox', { name: '에이크미' }));
    await user.tab();
    expect(document.activeElement).toBe(within(group()).getByRole('checkbox', { name: '베타상사' }));
    await user.keyboard(' ');
    expect(within(group()).getByRole('checkbox', { name: '베타상사' })).toBeChecked();
    expect(within(group()).getByRole('checkbox', { name: '감마' })).toBeDisabled();
  });

  test('CheckboxGroup 묶음 오류: 오류 문구를 묶음의 aria-describedby로 잇는다', () => {
    render(<Controlled error="고객사를 하나 이상 고르세요" helpText="고객은 고른 고객사의 문의를 봅니다" />);

    const g = group();
    expect(describedText(g)).toContain('고객사를 하나 이상 고르세요');
    expect(describedText(g)).toContain('고객은 고른 고객사의 문의를 봅니다');
  });

  test('CheckboxGroup 막힘: 묶음 전체를 막는다', () => {
    render(<Controlled disabled />);

    for (const name of ['에이크미', '베타상사']) {
      expect(must(within(group()).queryByRole('checkbox', { name }), name)).toBeDisabled();
    }
  });

  test('CheckboxGroup axe: 기본과 묶음 오류에 접근성 위반이 없다', async () => {
    render(
      <div>
        <CheckboxGroup legend="소속 고객사" options={companies} value={['ACME']} onChange={() => undefined} />
        <CheckboxGroup
          legend="다른 묶음"
          options={[{ value: 'X', label: '엑스' }]}
          value={[]}
          onChange={() => undefined}
          error="고객사를 하나 이상 고르세요"
        />
      </div>,
    );

    await expectNoAxeViolations();
  });
});
