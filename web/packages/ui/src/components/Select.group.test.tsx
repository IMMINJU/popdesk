// 1b design.md 5절 Select 선택지의 묶음: `group`이 같은 선택지는 그 이름 아래 묶여 보이고(접근 가능한 묶음 이름),
// `group`이 없는 선택지는 그대로다.
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, test } from 'vitest';
import { text } from '../__tests__/support';
import { Select, type SelectOption } from './Select';

const kinds: SelectOption[] = [
  { value: 'NO_SIGN_IN', label: '로그인 기록 빼고' },
  { value: 'ACCOUNT', label: '계정' },
  { value: 'SIGN_IN_FAILED', label: '로그인 실패', group: '로그인 기록' },
  { value: 'ACCOUNT_SUSPENDED', label: '계정 정지', group: '계정' },
  { value: 'ACCOUNT_REACTIVATED', label: '정지 풀기', group: '계정' },
];

function Kinds() {
  const [value, setValue] = useState('');
  return <Select label="종류" placeholder="전체" options={kinds} value={value} onChange={setValue} />;
}

const optionsIn = (el: HTMLElement) => within(el).queryAllByRole('option').map((o) => text(o));

describe('Select 선택지 묶음', () => {
  test('Select group: 같은 group의 선택지는 그 이름의 묶음 안에, group이 없는 선택지는 묶음 밖에 그대로 있다. 묶인 선택지도 고를 수 있다', async () => {
    render(<Kinds />);
    const user = userEvent.setup();
    const select = screen.getByRole('combobox', { name: '종류' });

    expect(optionsIn(select)).toEqual(['전체', '로그인 기록 빼고', '계정', '로그인 실패', '계정 정지', '정지 풀기']);
    const groups = within(select).queryAllByRole('group');
    expect(groups, '묶음 둘').toHaveLength(2);
    expect(optionsIn(within(select).getByRole('group', { name: '계정' }))).toEqual(['계정 정지', '정지 풀기']);
    expect(optionsIn(within(select).getByRole('group', { name: '로그인 기록' }))).toEqual(['로그인 실패']);
    expect(groups.flatMap((g) => optionsIn(g)), 'group이 없는 선택지는 묶음 밖').not.toContain('계정');

    await user.selectOptions(select, 'ACCOUNT_REACTIVATED');
    expect(select).toHaveValue('ACCOUNT_REACTIVATED');
    await user.selectOptions(select, 'ACCOUNT');
    expect(select).toHaveValue('ACCOUNT');
  });
});
