// screens.md 5절 7 CheckboxGroup, 8 RadioGroup. C-10 변이 테스트가 찾은 빈칸:
// 끄기, 받은 id(오류 요약 링크가 가리키는 곳), 선택지 설명, 기본 묶음 이름.
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, test, vi } from 'vitest';
import { text } from '../__tests__/support';
import { CheckboxGroup } from './CheckboxGroup';
import { RadioGroup } from './RadioGroup';

const COMPANIES = [
  { value: 'acme', label: '에이크미' },
  { value: 'beta', label: '베타 상사' },
];

describe('CheckboxGroup 동작', () => {
  test('CheckboxGroup 끄기: 켜진 칸을 다시 누르면 그 값만 빠진 목록으로 onChange', async () => {
    const onChange = vi.fn();
    render(<CheckboxGroup legend="소속 고객사" options={COMPANIES} value={['acme', 'beta']} onChange={onChange} />);
    const user = userEvent.setup();

    await user.click(screen.getByRole('checkbox', { name: '에이크미' }));
    expect(onChange).toHaveBeenLastCalledWith(['beta']);
  });

  test('CheckboxGroup 켜고 끄기: 제어 값으로 켜졌다 꺼진다', async () => {
    function Harness() {
      const [value, setValue] = useState<string[]>([]);
      return <CheckboxGroup legend="소속 고객사" options={COMPANIES} value={value} onChange={setValue} />;
    }
    render(<Harness />);
    const user = userEvent.setup();
    const box = screen.getByRole('checkbox', { name: '베타 상사' });

    await user.click(box);
    expect(box).toBeChecked();
    await user.click(box);
    expect(box).not.toBeChecked();
  });

  test('CheckboxGroup id: 받은 id가 묶음의 id라 오류 요약 링크(#id)가 묶음을 가리킨다', () => {
    render(<CheckboxGroup id="memberships" legend="소속 고객사" options={COMPANIES} value={[]} onChange={() => undefined} />);

    expect(screen.getByRole('group', { name: '소속 고객사' })).toHaveAttribute('id', 'memberships');
    expect(screen.getByRole('checkbox', { name: '에이크미' })).toHaveAttribute('id', 'memberships-acme');
  });
});

describe('RadioGroup 동작', () => {
  test('RadioGroup 선택지 설명: description이 있는 선택지만 설명 글자를 보인다', () => {
    const { container } = render(
      <RadioGroup
        legend="역할"
        options={[
          { value: 'AGENT', label: '상담원', description: '배정된 고객사의 문의를 봅니다' },
          { value: 'ADMIN', label: '관리자' },
        ]}
        value={null}
        onChange={() => undefined}
      />,
    );

    expect(text(container)).toContain('배정된 고객사의 문의를 봅니다');
    const admin = screen.getByRole('radio', { name: '관리자' }).closest('div');
    expect(text(admin)).toBe('관리자');
  });

  test('RadioGroup 이름: legend가 radiogroup의 이름이고 받은 id로 선택지 id를 만든다', () => {
    render(
      <RadioGroup id="role" legend="역할" options={[{ value: 'AGENT', label: '상담원' }]} value="AGENT" onChange={() => undefined} />,
    );

    expect(screen.getByRole('radiogroup', { name: '역할' })).toHaveAttribute('id', 'role');
    expect(screen.getByRole('radio', { name: '상담원' })).toHaveAttribute('id', 'role-AGENT');
  });
});
