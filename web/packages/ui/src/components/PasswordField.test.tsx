// screens.md 5절 4 PasswordField. TextField 상태와 보이기·숨기기 전환.
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, test } from 'vitest';
import { describedText, expectNoAxeViolations, found } from '../__tests__/support';
import { PasswordField, type PasswordFieldProps } from './PasswordField';

function Controlled(props: Omit<PasswordFieldProps, 'value' | 'onChange'>) {
  const [value, setValue] = useState('');
  return <PasswordField {...props} value={value} onChange={setValue} />;
}

const labels = { showLabel: '비밀번호 보이기', hideLabel: '비밀번호 숨기기' } as const;
const field = () => screen.getByLabelText('비밀번호', { selector: 'input' });
const toggle = (name: string) => found(() => screen.queryByRole('button', { name }), `버튼 "${name}"`);

describe('PasswordField', () => {
  test('PasswordField 처음: 가려진 칸과 "비밀번호 보이기" 버튼, autocomplete를 그대로 둔다', async () => {
    render(<Controlled label="비밀번호" autoComplete="current-password" {...labels} />);

    expect(field()).toHaveAttribute('type', 'password');
    expect(field()).toHaveAttribute('autocomplete', 'current-password');
    expect(await toggle('비밀번호 보이기')).toHaveAttribute('type', 'button');
  });

  test('PasswordField 보이기·숨기기: 누르면 글자로 보이고 버튼 이름이 "비밀번호 숨기기"로, 다시 누르면 가린다', async () => {
    render(<Controlled label="비밀번호" autoComplete="current-password" {...labels} />);
    const user = userEvent.setup();
    await user.type(field(), 'secret-value');

    await user.click(await toggle('비밀번호 보이기'));
    expect(field()).toHaveAttribute('type', 'text');
    expect(field()).toHaveValue('secret-value');
    await user.click(await toggle('비밀번호 숨기기'));
    expect(field()).toHaveAttribute('type', 'password');
  });

  test('PasswordField 제어: revealed를 주면 그 값을 따르고 누르면 onRevealedChange로 알린다', async () => {
    const changes: boolean[] = [];
    render(
      <PasswordField
        label="비밀번호"
        autoComplete="new-password"
        value="abc"
        onChange={() => undefined}
        revealed
        onRevealedChange={(r) => changes.push(r)}
        {...labels}
      />,
    );
    const user = userEvent.setup();

    expect(field()).toHaveAttribute('type', 'text');
    await user.click(await toggle('비밀번호 숨기기'));
    expect(changes).toEqual([false]);
  });

  test('PasswordField 키보드: 칸 다음 Tab은 전환 버튼, Space로 바꾼다', async () => {
    render(<Controlled label="비밀번호" autoComplete="current-password" {...labels} />);
    const user = userEvent.setup();
    field().focus();

    await user.tab();
    expect(document.activeElement).toBe(await toggle('비밀번호 보이기'));
    await user.keyboard(' ');
    expect(field()).toHaveAttribute('type', 'text');
  });

  test('PasswordField 도움말·오류: 규칙과 오류 문구를 aria-describedby로 잇고 aria-invalid', () => {
    render(
      <Controlled
        label="비밀번호"
        autoComplete="new-password"
        helpText="10~72자, 공백만은 안 됩니다"
        error="이메일과 같은 비밀번호는 쓸 수 없습니다."
        {...labels}
      />,
    );

    expect(field()).toHaveAttribute('aria-invalid', 'true');
    expect(describedText(field())).toContain('10~72자');
    expect(describedText(field())).toContain('이메일과 같은 비밀번호는 쓸 수 없습니다.');
  });

  test('PasswordField 막힘: 칸과 전환 버튼을 함께 막는다', async () => {
    render(<Controlled label="비밀번호" autoComplete="current-password" disabled {...labels} />);

    expect(field()).toBeDisabled();
    expect(await toggle('비밀번호 보이기')).toBeDisabled();
  });

  test('PasswordField axe: 기본·오류·보이기에 접근성 위반이 없다', async () => {
    render(
      <div>
        <PasswordField label="비밀번호" autoComplete="current-password" value="" onChange={() => undefined} {...labels} />
        <PasswordField
          label="비밀번호 확인"
          autoComplete="new-password"
          value="x"
          onChange={() => undefined}
          revealed
          error="두 칸이 다릅니다."
          {...labels}
        />
      </div>,
    );

    await expectNoAxeViolations();
  });
});
