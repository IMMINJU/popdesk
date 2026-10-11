import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { focusFirst } from '../storybook/states';
import { Select } from './Select';

const meta = {
  component: Select,
  args: {
    label: '고객사',
    placeholder: '고객사를 고르세요',
    options: [
      { value: 'ACME', label: 'Acme' },
      { value: 'GLOBEX', label: 'Globex' },
    ],
    value: '',
    onChange: fn(),
  },
} satisfies Meta<typeof Select>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
export const Focus: Story = { play: focusFirst('select') };
/** 펼침 is the browser's list for a native select; it opens by interaction only. */
export const Open: Story = { play: focusFirst('select') };
export const Error: Story = { args: { error: '이 고객사에는 지금 문의를 올릴 수 없습니다.' } };
export const Disabled: Story = { args: { disabled: true } };
export const Loading: Story = { args: { loading: true, options: [] } };
export const SingleReadOnly: Story = {
  args: { options: [{ value: 'ACME', label: 'Acme' }], value: 'ACME', readOnly: true },
};
/**
 * 묶음: options that carry the same `group` belong under that name (C-12 종류 필터).
 * Shell only: `group` is taken but the options are not shown grouped yet (C-9 fills it in).
 */
export const Grouped: Story = {
  args: {
    label: '종류',
    placeholder: '전체',
    options: [
      { value: 'SIGN_IN', label: '로그인 기록' },
      { value: 'SIGN_IN_SUCCEEDED', label: '로그인', group: '로그인 기록' },
      { value: 'SIGN_IN_FAILED', label: '로그인 실패', group: '로그인 기록' },
      { value: 'ACCOUNT', label: '계정' },
      { value: 'ACCOUNT_SUSPENDED', label: '계정 정지', group: '계정' },
      { value: 'ACCOUNT_REACTIVATED', label: '정지 풀기', group: '계정' },
    ],
  },
};
