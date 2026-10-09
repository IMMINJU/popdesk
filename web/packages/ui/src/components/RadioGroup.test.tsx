// screens.md 5절 7 RadioGroup. C-10 역할(라디오 목록)과 C-4 공개·내부 탭(탭 모양이지만 radiogroup).
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, test } from 'vitest';
import { describedText, expectNoAxeViolations, found } from '../__tests__/support';
import { RadioGroup, type RadioGroupProps } from './RadioGroup';

type Role = 'CUSTOMER' | 'AGENT' | 'ADMIN';
type Visibility = 'public' | 'internal';

const roles = [
  { value: 'CUSTOMER' as const, label: '고객' },
  { value: 'AGENT' as const, label: '상담원' },
  { value: 'ADMIN' as const, label: '관리자' },
];

const visibilities = [
  { value: 'public' as const, label: '공개 답변' },
  { value: 'internal' as const, label: '내부 메모' },
];

function RoleGroup(props: Partial<Omit<RadioGroupProps<Role>, 'value' | 'onChange' | 'options'>> & { initial?: Role | null }) {
  const { initial, ...rest } = props;
  const [value, setValue] = useState<Role | null>(initial ?? null);
  return <RadioGroup legend="역할" options={roles} {...rest} value={value} onChange={setValue} />;
}

function VisibilityTabs() {
  const [value, setValue] = useState<Visibility>('public');
  return (
    <RadioGroup
      legend="댓글 종류"
      legendHidden
      appearance="tabs"
      options={visibilities}
      value={value}
      onChange={setValue}
    />
  );
}

const radio = (name: string) => screen.getByRole('radio', { name });

describe('RadioGroup', () => {
  test('RadioGroup 안 고름·고름: 처음에는 아무것도 고르지 않고, 누르면 그것만 고른다', async () => {
    render(<RoleGroup />);
    const user = userEvent.setup();

    for (const r of roles) expect(radio(r.label)).not.toBeChecked();
    await user.click(radio('상담원'));
    expect(radio('상담원')).toBeChecked();
    expect(radio('고객')).not.toBeChecked();
  });

  test('RadioGroup 키보드: 묶음에 Tab 자리는 하나이고 화살표로 옮겨 고른다', async () => {
    render(
      <>
        <button type="button">앞</button>
        <RoleGroup initial="CUSTOMER" />
        <button type="button">뒤</button>
      </>,
    );
    const user = userEvent.setup();

    screen.getByRole('button', { name: '앞' }).focus();
    await user.tab();
    expect(document.activeElement, '묶음의 Tab 자리는 고른 칸').toBe(radio('고객'));
    await user.tab();
    expect(document.activeElement, '한 번 더 Tab이면 묶음을 떠난다').toBe(screen.getByRole('button', { name: '뒤' }));
    radio('고객').focus();
    await user.keyboard('{ArrowDown}');
    expect(radio('상담원')).toBeChecked();
    expect(document.activeElement).toBe(radio('상담원'));
  });

  test('RadioGroup 탭 모양(공개·내부): 역할은 radiogroup이고 숨긴 legend가 이름, 좌우 화살표로 바꾼다', async () => {
    render(<VisibilityTabs />);
    const user = userEvent.setup();

    const group = await found(() => screen.queryByRole('radiogroup', { name: '댓글 종류' }), 'radiogroup "댓글 종류"');
    expect(within(group).queryAllByRole('tab'), '탭 역할을 쓰지 않는다').toHaveLength(0);
    expect(radio('공개 답변')).toBeChecked();
    radio('공개 답변').focus();
    await user.keyboard('{ArrowRight}');
    expect(radio('내부 메모')).toBeChecked();
    await user.keyboard('{ArrowLeft}');
    expect(radio('공개 답변')).toBeChecked();
  });

  test('RadioGroup 묶음 오류: 오류·도움말 문구를 묶음의 aria-describedby로 잇는다', async () => {
    render(<RoleGroup error="역할을 고르세요" helpText="고객이면 소속 고객사를 고릅니다" />);

    const group = await found(
      () => screen.queryByRole('radiogroup', { name: '역할' }) ?? screen.queryByRole('group', { name: '역할' }),
      '역할 묶음',
    );
    expect(describedText(group)).toContain('역할을 고르세요');
    expect(describedText(group)).toContain('고객이면 소속 고객사를 고릅니다');
  });

  test('RadioGroup 막힘: 묶음 전체를 막는다', () => {
    render(<RoleGroup disabled />);

    for (const r of roles) expect(radio(r.label)).toBeDisabled();
  });

  test('RadioGroup axe: 라디오 목록·탭 모양·오류에 접근성 위반이 없다', async () => {
    render(
      <div>
        <RoleGroup initial="ADMIN" />
        <VisibilityTabs />
        <RadioGroup legend="오류 묶음" options={roles} value={null} onChange={() => undefined} error="역할을 고르세요" />
      </div>,
    );

    await expectNoAxeViolations();
  });
});
