// screens.md 5절 3 TextField, 4 PasswordField, 6 Select. C-10 변이 테스트가 찾은 빈칸:
// 도움말·오류가 없을 때 aria-describedby, 글자 수 표시 조건, 읽기 전용 Select, 비밀번호 맞춤법 검사.
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { text } from '../__tests__/support';
import { PasswordField } from './PasswordField';
import { Select } from './Select';
import { TextField } from './TextField';

const OPTIONS = [
  { value: 'acme', label: '에이크미' },
  { value: 'beta', label: '베타 상사' },
];

describe('TextField 동작', () => {
  test('TextField 설명 없음: 도움말도 오류도 없으면 aria-describedby가 없고 aria-invalid도 없다', () => {
    render(<TextField label="이름" value="" onChange={() => undefined} />);

    const input = screen.getByRole('textbox', { name: '이름' });
    expect(input).not.toHaveAttribute('aria-describedby');
    expect(input).not.toHaveAttribute('aria-invalid');
    expect(screen.queryByRole('paragraph')).toBeNull();
  });

  test('TextField 오류만: aria-describedby는 오류 문구 하나만 가리킨다', () => {
    render(<TextField label="이름" value="" onChange={() => undefined} error="이름을 쓰세요" />);

    const ids = (screen.getByRole('textbox', { name: '이름' }).getAttribute('aria-describedby') ?? '').split(' ');
    expect(ids).toHaveLength(1);
    expect(text(document.getElementById(ids[0] ?? ''))).toBe('이름을 쓰세요');
  });

  test('TextField 글자 수: showCount만 있고 maxLength가 없으면, 또는 maxLength만 있으면 세지 않는다', () => {
    const { rerender, container } = render(<TextField label="이름" value="가나다" onChange={() => undefined} showCount />);
    expect(text(container)).not.toMatch(/\d+ \//);

    rerender(<TextField label="이름" value="가나다" onChange={() => undefined} maxLength={200} />);
    expect(text(container)).not.toMatch(/\d+ \//);

    rerender(<TextField label="이름" value="가나다" onChange={() => undefined} maxLength={1200} showCount />);
    expect(text(container)).toContain('3 / 1,200');
  });
});

describe('Select 동작', () => {
  test('Select 읽기 전용: 고른 값 하나만 선택지로 두고 placeholder 빈 선택지도 없다', () => {
    render(<Select label="고객사" options={OPTIONS} value="beta" onChange={() => undefined} placeholder="고객사를 고르세요" readOnly />);

    const select = screen.getByRole('combobox', { name: '고객사' });
    expect(Array.from(select.querySelectorAll('option')).map((o) => text(o))).toEqual(['베타 상사']);
  });

  test('Select 읽기 전용: 값이 바뀌는 이벤트가 와도 onChange를 부르지 않는다', async () => {
    const onChange = vi.fn();
    render(
      <Select label="고객사" options={OPTIONS} value="beta" onChange={onChange} readOnly />,
    );
    const user = userEvent.setup();

    await user.selectOptions(screen.getByRole('combobox', { name: '고객사' }), 'beta');
    expect(onChange).not.toHaveBeenCalled();
  });

  test('Select placeholder 없음: 빈 선택지를 두지 않는다', () => {
    render(<Select label="고객사" options={OPTIONS} value="acme" onChange={() => undefined} />);

    const select = screen.getByRole('combobox', { name: '고객사' });
    expect(Array.from(select.querySelectorAll('option')).map((o) => o.value)).toEqual(['acme', 'beta']);
  });

  test('Select 기본: 읽기 전용이 아니면 모든 선택지와 placeholder를 둔다', () => {
    render(<Select label="고객사" options={OPTIONS} value="" onChange={() => undefined} placeholder="고객사를 고르세요" />);

    const select = screen.getByRole('combobox', { name: '고객사' });
    expect(Array.from(select.querySelectorAll('option')).map((o) => text(o))).toEqual(['고객사를 고르세요', '에이크미', '베타 상사']);
  });

  test('Select 불러온 뒤: loading이 아니면 aria-busy도 뼈대도 없다', () => {
    render(
      <div>
        <Select label="불러온 고객사" options={OPTIONS} value="" onChange={() => undefined} />
        <Select label="불러오는 고객사" options={OPTIONS} value="" onChange={() => undefined} loading />
      </div>,
    );
    const hidden = (name: string) => screen.getByRole('combobox', { name }).parentElement?.querySelectorAll('[aria-hidden="true"]').length ?? 0;

    expect(screen.getByRole('combobox', { name: '불러온 고객사' })).not.toHaveAttribute('aria-busy');
    // Both draw the arrow; only the loading one adds the skeleton.
    expect(hidden('불러오는 고객사') - hidden('불러온 고객사')).toBe(1);
  });
});

describe('PasswordField 동작', () => {
  test('PasswordField 맞춤법 검사: 보일 때도 비밀번호를 맞춤법 검사에 넘기지 않는다(spellcheck="false")', async () => {
    render(
      <PasswordField label="비밀번호" value="" onChange={() => undefined} autoComplete="current-password" showLabel="비밀번호 보이기" hideLabel="비밀번호 숨기기" />,
    );
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: '비밀번호 보이기' }));
    expect(screen.getByLabelText('비밀번호', { selector: 'input' })).toHaveAttribute('spellcheck', 'false');
  });
});
