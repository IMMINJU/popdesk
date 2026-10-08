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
