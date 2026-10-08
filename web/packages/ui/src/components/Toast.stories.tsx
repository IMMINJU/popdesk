import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { Toast } from './Toast';

const meta = {
  component: Toast,
  args: {
    toasts: [{ id: '1', tone: 'success', message: '문의를 올렸습니다(ACME-7)' }],
    onDismiss: fn(),
  },
} satisfies Meta<typeof Toast>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Success: Story = {};
export const Info: Story = { args: { toasts: [{ id: '1', tone: 'info', message: '복사했습니다' }] } };
export const Stacked: Story = {
  args: {
    toasts: [
      { id: '1', tone: 'success', message: '초대 메일을 보냈습니다' },
      { id: '2', tone: 'info', message: '복사했습니다' },
      { id: '3', tone: 'success', message: '저장했습니다' },
      { id: '4', tone: 'success', message: '저장했습니다(넷째, 첫째는 빠진다)' },
    ],
  },
};
