import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { focusFirst, hover, pressed } from '../storybook/states';
import { IconButton } from './IconButton';

const meta = {
  component: IconButton,
  args: { label: '닫기', icon: '×', onClick: fn() },
} satisfies Meta<typeof IconButton>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
export const Hover: Story = { parameters: hover };
export const Pressed: Story = { parameters: pressed };
export const Focus: Story = { play: focusFirst('button') };
export const Disabled: Story = { args: { disabled: true } };
export const Pending: Story = { args: { pending: true } };
export const Small: Story = { args: { size: 'sm' } };
export const Danger: Story = { args: { variant: 'danger', label: '지우기', icon: '🗑' } };
export const Toggle: Story = { args: { label: '비밀번호 보이기', icon: '👁', pressed: false } };
