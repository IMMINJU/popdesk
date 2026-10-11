import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { RowMenu } from './RowMenu';

const meta = {
  component: RowMenu,
  args: {
    label: '이상담 동작',
    items: [
      { label: '이름 고치기', onSelect: fn() },
      { label: '비밀번호 초기화', onSelect: fn() },
      { label: '정지', onSelect: fn(), tone: 'danger' },
    ],
  },
} satisfies Meta<typeof RowMenu>;

export default meta;
type Story = StoryObj<typeof meta>;

/** 닫힌 채로 그린다. 버튼을 누르면 목록이 열린다. */
export const Default: Story = {};
