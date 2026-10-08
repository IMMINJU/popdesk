import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { Alert } from './Alert';

const meta = {
  component: Alert,
  args: { tone: 'error', children: '서버에 연결하지 못했습니다. 잠시 뒤 다시 하세요.' },
} satisfies Meta<typeof Alert>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Error: Story = {};
export const Warning: Story = { args: { tone: 'warning', children: '첨부 2개를 올리지 못했습니다.' } };
export const Info: Story = { args: { tone: 'info', children: '로그인이 끝났습니다. 다시 로그인하세요.' } };
export const Success: Story = { args: { tone: 'success', children: '저장했습니다.' } };
export const Closable: Story = { args: { tone: 'info', onClose: fn(), closeLabel: '닫기' } };
export const WithAction: Story = {
  args: {
    action: (
      <button type="button" onClick={fn()}>
        다시 불러오기
      </button>
    ),
  },
};
