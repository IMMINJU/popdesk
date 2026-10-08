import type { Meta, StoryObj } from '@storybook/react-vite';
import { FormErrorSummary } from './FormErrorSummary';

const meta = {
  component: FormErrorSummary,
  args: {
    title: '1개 칸을 고쳐 주세요',
    errors: [{ fieldId: 'title', message: '제목을 넣으세요.' }],
  },
} satisfies Meta<typeof FormErrorSummary>;

export default meta;
type Story = StoryObj<typeof meta>;

export const OneError: Story = {};
export const Several: Story = {
  args: {
    title: '2개 칸을 고쳐 주세요',
    errors: [
      { fieldId: 'title', message: '제목은 200자까지입니다.' },
      { fieldId: 'body', message: '내용은 10,000자까지입니다.' },
    ],
  },
};
export const WithServerMessage: Story = {
  args: { serverMessage: 'priority: must be one of LOW, NORMAL, HIGH, URGENT' },
};
