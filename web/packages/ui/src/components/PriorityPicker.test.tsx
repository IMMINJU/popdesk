// screens.md 5절 20-1 PriorityPicker. PriorityLabel 태그 네 개를 RadioGroup으로, 화살표로 바꾼다(P-4).
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, test } from 'vitest';
import { describedText, expectNoAxeViolations, found } from '../__tests__/support';
import type { Priority } from '../types';
import { PriorityPicker } from './PriorityPicker';

function Controlled(props: { initial?: Priority | null; error?: string; disabled?: boolean }) {
  const [value, setValue] = useState<Priority | null>(props.initial === undefined ? 'NORMAL' : props.initial);
  return (
    <PriorityPicker
      legend="우선순위"
      value={value}
      onChange={setValue}
      {...(props.error === undefined ? {} : { error: props.error })}
      disabled={props.disabled ?? false}
    />
  );
}

const radio = (name: string) => found(() => screen.queryByRole('radio', { name }), `라디오 "${name}"`);

describe('PriorityPicker', () => {
  test('PriorityPicker 이름: 라디오 네 개의 이름이 낮음, 보통, 높음, 긴급이다', async () => {
    render(<Controlled />);

    for (const name of ['낮음', '보통', '높음', '긴급']) await radio(name);
    expect(screen.queryAllByRole('radio')).toHaveLength(4);
  });

  test('PriorityPicker 묶음: 이름이 "우선순위"인 묶음이고 처음 값 보통이 골라져 있다', async () => {
    render(<Controlled />);

    await found(() => screen.queryByRole('radiogroup', { name: '우선순위' }) ?? screen.queryByRole('group', { name: '우선순위' }), '묶음 "우선순위"');
    expect(await radio('보통')).toBeChecked();
  });

  test('PriorityPicker 키보드: Tab 자리는 하나, 화살표로 높음 → 긴급, 거꾸로 보통', async () => {
    render(
      <>
        <button type="button">앞</button>
        <Controlled />
        <button type="button">뒤</button>
      </>,
    );
    const user = userEvent.setup();

    const normal = await radio('보통');
    screen.getByRole('button', { name: '앞' }).focus();
    await user.tab();
    expect(document.activeElement, '묶음의 Tab 자리는 고른 값').toBe(normal);
    await user.tab();
    expect(document.activeElement, '한 번 더 Tab이면 묶음을 떠난다').toBe(screen.getByRole('button', { name: '뒤' }));
    normal.focus();
    await user.keyboard('{ArrowRight}');
    expect(await radio('높음')).toBeChecked();
    await user.keyboard('{ArrowRight}');
    expect(await radio('긴급')).toBeChecked();
    await user.keyboard('{ArrowLeft}{ArrowLeft}');
    expect(await radio('보통')).toBeChecked();
  });

  test('PriorityPicker 안 고름: value가 null이면 아무것도 고르지 않는다', async () => {
    render(<Controlled initial={null} />);

    for (const name of ['낮음', '보통', '높음', '긴급']) expect(await radio(name)).not.toBeChecked();
  });

  test('PriorityPicker 오류: 오류 문구를 묶음의 aria-describedby로 잇는다', async () => {
    render(<Controlled error="우선순위 값이 틀렸습니다." />);

    const group = await found(() => screen.queryByRole('radiogroup', { name: '우선순위' }) ?? screen.queryByRole('group', { name: '우선순위' }), '묶음 "우선순위"');
    expect(describedText(group)).toContain('우선순위 값이 틀렸습니다.');
  });

  test('PriorityPicker 막힘: 네 값 모두 막는다', async () => {
    render(<Controlled disabled />);

    for (const name of ['낮음', '보통', '높음', '긴급']) expect(await radio(name)).toBeDisabled();
  });

  test('PriorityPicker axe: 기본과 오류에 접근성 위반이 없다', async () => {
    render(
      <div>
        <PriorityPicker legend="우선순위" value="NORMAL" onChange={() => undefined} />
        <PriorityPicker legend="다른 우선순위" value={null} onChange={() => undefined} error="우선순위를 고르세요" />
      </div>,
    );

    await expectNoAxeViolations();
  });
});
