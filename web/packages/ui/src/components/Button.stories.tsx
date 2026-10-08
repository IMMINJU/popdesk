import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { focusFirst, hover, pressed } from '../storybook/states';
import { Button } from './Button';

const meta = {
  component: Button,
  args: { children: '올리기', onClick: fn() },
} satisfies Meta<typeof Button>;

export default meta;
type Story = StoryObj<typeof meta>;

// 종류
export const Primary: Story = { args: { variant: 'primary' } };
export const Secondary: Story = { args: { variant: 'secondary', children: '취소' } };
export const Quiet: Story = { args: { variant: 'quiet', children: '목록으로' } };
export const Danger: Story = { args: { variant: 'danger', children: '지우기' } };
export const Internal: Story = { args: { variant: 'internal', children: '내부 메모 남기기' } };

// 상태
export const Hover: Story = { parameters: hover };
export const Pressed: Story = { parameters: pressed };
export const Focus: Story = { play: focusFirst('button') };
export const Disabled: Story = { args: { disabled: true } };
export const Pending: Story = { args: { pending: true, pendingLabel: '보내는 중…' } };

// 크기
export const Small: Story = { args: { size: 'sm' } };
