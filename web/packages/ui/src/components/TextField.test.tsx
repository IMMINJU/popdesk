// screens.md 5절 3 TextField. field 바탕에 아래 선(tokens.md 7절).
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, test } from 'vitest';
import { describedText, expectNoAxeViolations } from '../__tests__/support';
import { TextField, type TextFieldProps } from './TextField';

function Controlled(props: Omit<TextFieldProps, 'value' | 'onChange'> & { initial?: string }) {
  const { initial, ...rest } = props;
  const [value, setValue] = useState(initial ?? '');
  return <TextField {...rest} value={value} onChange={setValue} />;
}

describe('TextField', () => {
  test('TextField 기본: 이름표로 찾고 쓴 값이 onChange로 나간다', async () => {
    render(<Controlled label="제목" />);
    const user = userEvent.setup();

    const input = screen.getByRole('textbox', { name: '제목' });
    await user.type(input, '프린터가 안 됩니다');
    expect(input).toHaveValue('프린터가 안 됩니다');
  });

  test('TextField 오류: aria-invalid="true"이고 오류 문구를 aria-describedby로 잇는다', () => {
    render(<Controlled label="이메일" type="email" error="이메일 모양이 아닙니다." />);

    const input = screen.getByRole('textbox', { name: '이메일' });
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(describedText(input)).toContain('이메일 모양이 아닙니다.');
  });

  test('TextField 도움말: 도움말을 aria-describedby로 잇고, 오류가 생기면 도움말과 오류를 함께 잇는다', () => {
    const { rerender } = render(<TextField label="코드" value="" onChange={() => undefined} helpText="나중에 바꿀 수 없습니다" />);
    const input = screen.getByRole('textbox', { name: '코드' });
    expect(describedText(input)).toContain('나중에 바꿀 수 없습니다');

    rerender(
      <TextField label="코드" value="" onChange={() => undefined} helpText="나중에 바꿀 수 없습니다" error="이미 쓰는 코드입니다." />,
    );
    expect(describedText(input)).toContain('나중에 바꿀 수 없습니다');
    expect(describedText(input)).toContain('이미 쓰는 코드입니다.');
  });

  test('TextField 글자 수 표시: "12 / 200" 꼴로 보이고 쓰면 바뀐다', async () => {
    render(<Controlled label="제목" maxLength={200} showCount initial="프린터가 안 됩니다" />);
    const user = userEvent.setup();

    expect(document.body.textContent).toMatch(/10\s*\/\s*200/);
    await user.type(screen.getByRole('textbox', { name: '제목' }), '!!');
    expect(document.body.textContent).toMatch(/12\s*\/\s*200/);
  });

  test('TextField 막힘: disabled면 쓸 수 없다', async () => {
    render(<Controlled label="제목" disabled />);
    const user = userEvent.setup();

    const input = screen.getByRole('textbox', { name: '제목' });
    expect(input).toBeDisabled();
    await user.type(input, 'x');
    expect(input).toHaveValue('');
  });

  test('TextField 읽기 전용: readOnly이고 포커스는 받는다', async () => {
    render(<Controlled label="고객사" readOnly initial="에이크미" />);
    const user = userEvent.setup();

    const input = screen.getByRole('textbox', { name: '고객사' });
    expect(input).toHaveAttribute('readonly');
    await user.type(input, 'x');
    expect(input).toHaveValue('에이크미');
    expect(document.activeElement).toBe(input);
  });

  test('TextField axe: 기본·오류·도움말·읽기 전용·막힘에 접근성 위반이 없다', async () => {
    render(
      <div>
        <TextField label="제목" value="" onChange={() => undefined} maxLength={200} showCount />
        <TextField label="이메일" type="email" value="kim" onChange={() => undefined} error="이메일 모양이 아닙니다." />
        <TextField label="코드" value="" onChange={() => undefined} helpText="나중에 바꿀 수 없습니다" />
        <TextField label="고객사" value="에이크미" onChange={() => undefined} readOnly />
        <TextField label="이름" value="" onChange={() => undefined} disabled />
      </div>,
    );

    await expectNoAxeViolations();
  });
});
