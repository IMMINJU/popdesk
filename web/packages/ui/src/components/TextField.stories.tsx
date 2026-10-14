import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { focusFirst } from '../storybook/states';
import { TextField } from './TextField';

const meta = {
  component: TextField,
  args: { label: '이메일', value: '', onChange: fn(), type: 'email', autoComplete: 'username' },
} satisfies Meta<typeof TextField>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
export const Focus: Story = { play: focusFirst('input') };
export const Error: Story = { args: { value: 'kim@', error: '이메일 모양이 아닙니다.' } };
export const Disabled: Story = { args: { value: 'kim@example.com', disabled: true } };
export const ReadOnly: Story = { args: { value: 'kim@example.com', readOnly: true } };
export const WithHelp: Story = {
  args: { label: '코드', type: 'text', value: 'ACME', helpText: '나중에 바꿀 수 없습니다.' },
};
export const WithCount: Story = {
  args: { label: '제목', type: 'text', value: '첨부가 안 올라갑니다', maxLength: 200, showCount: true },
};
/** 날짜: the browser's date field (C-12 기간 필터). The value is `YYYY-MM-DD`. */
export const Date: Story = {
  args: { label: '부터', type: 'date', value: '2026-10-01', autoComplete: 'off' },
};
/** 마감일 (6a 묶음): `min`으로 오늘보다 이전 날은 고르지 못하게 한다(힌트, 판정은 서버). */
export const DueDate: Story = {
  args: { label: '마감일', type: 'date', value: '2026-10-20', min: '2026-10-14', autoComplete: 'off' },
};
/** 마감일 400: 입력 아래 오류. */
export const DueDatePast: Story = {
  args: { label: '마감일', type: 'date', value: '2026-10-01', min: '2026-10-14', autoComplete: 'off', error: '오늘이나 그 뒤의 날짜를 고르세요.' },
};
