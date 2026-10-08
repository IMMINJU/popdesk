import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { Dialog } from './Dialog';

const meta = {
  component: Dialog,
  args: {
    open: true,
    title: '고객사 만들기',
    onClose: fn(),
    closeLabel: '닫기',
    children: '이름, 코드, 회사명 칸',
    footer: <button type="button">만들기</button>,
  },
} satisfies Meta<typeof Dialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Open: Story = {};
export const Closed: Story = { args: { open: false } };
export const Pending: Story = { args: { pending: true } };
