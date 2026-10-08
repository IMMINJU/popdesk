import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { ConfirmDialog } from './ConfirmDialog';

const meta = {
  component: ConfirmDialog,
  args: {
    open: true,
    title: '쓰던 글이 있습니다',
    message: '이 화면을 떠나면 쓰던 글이 사라집니다.',
    confirmLabel: '떠나기',
    cancelLabel: '머무르기',
    onConfirm: fn(),
    onCancel: fn(),
  },
} satisfies Meta<typeof ConfirmDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
export const Danger: Story = {
  args: {
    tone: 'danger',
    title: '배정 빼기',
    message: '이 상담원은 이 고객사의 문의를 바로 볼 수 없게 됩니다.',
    confirmLabel: '배정 빼기',
    cancelLabel: '취소',
  },
};
export const Pending: Story = { args: { tone: 'danger', pending: true } };
