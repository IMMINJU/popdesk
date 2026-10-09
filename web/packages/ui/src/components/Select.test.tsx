// screens.md 5절 5 Select. 브라우저 화살표 대신 직접 그린 화살표(tokens.md 7절).
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, test } from 'vitest';
import { describedText, expectNoAxeViolations } from '../__tests__/support';
import { Select, type SelectProps } from './Select';

const companies = [
  { value: 'ACME', label: '에이크미' },
  { value: 'BETA', label: '베타상사' },
];

function Controlled(props: Omit<SelectProps, 'value' | 'onChange' | 'options'> & { initial?: string }) {
  const { initial, ...rest } = props;
  const [value, setValue] = useState(initial ?? '');
  return <Select {...rest} options={companies} value={value} onChange={setValue} />;
}

const select = () => screen.getByRole('combobox', { name: '고객사' });

describe('Select', () => {
  test('Select 기본: 이름표로 찾고 고르면 값이 바뀐다, 첫 빈 선택지는 placeholder', async () => {
    render(<Controlled label="고객사" placeholder="고객사를 고르세요" />);
    const user = userEvent.setup();

    expect(screen.getByRole('option', { name: '고객사를 고르세요' })).toHaveValue('');
    await user.selectOptions(select(), 'BETA');
    expect(select()).toHaveValue('BETA');
  });

  test('Select 직접 그린 화살표: 화살표 그림을 두고 읽지 않는다(aria-hidden)', () => {
    const { container } = render(<Controlled label="고객사" />);

    expect(container.querySelectorAll('[aria-hidden="true"]').length, '직접 그린 화살표(aria-hidden)').toBeGreaterThan(0);
  });

  test('Select 오류: aria-invalid와 오류·도움말 문구를 aria-describedby로', () => {
    render(<Controlled label="고객사" error="고객사를 고르세요." helpText="소속된 고객사만 보입니다" />);

    expect(select()).toHaveAttribute('aria-invalid', 'true');
    expect(describedText(select())).toContain('고객사를 고르세요.');
    expect(describedText(select())).toContain('소속된 고객사만 보입니다');
  });

  test('Select 불러오는 중: aria-busy이고 뼈대(aria-hidden)를 보인다', () => {
    const { container } = render(<Controlled label="고객사" loading />);

    expect(select()).toHaveAttribute('aria-busy', 'true');
    expect(container.querySelectorAll('[aria-hidden="true"]').length, '뼈대(aria-hidden)').toBeGreaterThan(0);
  });

  test('Select 읽기 전용(소속 하나): 고른 값이 보이고 바꿀 수 없다', async () => {
    render(<Controlled label="고객사" readOnly initial="ACME" />);
    const user = userEvent.setup();

    expect(select()).toHaveValue('ACME');
    await user.selectOptions(select(), 'BETA').catch(() => undefined);
    expect(select()).toHaveValue('ACME');
  });

  test('Select 막힘: disabled', () => {
    render(<Controlled label="고객사" disabled />);

    expect(select()).toBeDisabled();
  });

  test('Select axe: 기본·오류·불러오는 중·읽기 전용에 접근성 위반이 없다', async () => {
    render(
      <div>
        <Select label="고객사" options={companies} value="" onChange={() => undefined} placeholder="고객사를 고르세요" />
        <Select label="고객사 2" options={companies} value="" onChange={() => undefined} error="고객사를 고르세요." />
        <Select label="고객사 3" options={companies} value="" onChange={() => undefined} loading />
        <Select label="고객사 4" options={companies} value="ACME" onChange={() => undefined} readOnly />
      </div>,
    );

    await expectNoAxeViolations();
  });
});
