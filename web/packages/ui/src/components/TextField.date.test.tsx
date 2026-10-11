// 1b design.md 5절 TextField의 날짜 칸: `type="date"`를 받고 값은 `YYYY-MM-DD`다.
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, test } from 'vitest';
import { TextField } from './TextField';

function DateField(props: { onValue: (value: string) => void }) {
  const [value, setValue] = useState('');
  return (
    <TextField
      label="부터"
      type="date"
      value={value}
      onChange={(next) => {
        setValue(next);
        props.onValue(next);
      }}
    />
  );
}

describe('TextField 날짜', () => {
  test('TextField date: 이름표로 찾는 날짜 칸이고, 넣은 날짜가 YYYY-MM-DD로 onChange에 나간다', async () => {
    const values: string[] = [];
    render(<DateField onValue={(v) => values.push(v)} />);
    const user = userEvent.setup();

    const input = screen.getByLabelText<HTMLInputElement>('부터');
    expect(input.type).toBe('date');
    await user.type(input, '2026-10-05');
    expect(input).toHaveValue('2026-10-05');
    expect(values.at(-1)).toBe('2026-10-05');
  });
});
