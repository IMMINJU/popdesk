import type { Meta, StoryObj } from '@storybook/react-vite';
import { EmptyState } from './EmptyState';

const meta = {
  component: EmptyState,
  args: { title: '아직 댓글이 없습니다.' },
} satisfies Meta<typeof EmptyState>;

export default meta;
type Story = StoryObj<typeof meta>;

export const TitleOnly: Story = {};
export const WithAction: Story = {
  args: {
    title: '아직 올린 문의가 없습니다.',
    action: <a href="/issues/new">문의 올리기</a>,
  },
};
export const WithDescription: Story = {
  args: {
    title: '아직 문의가 없습니다.',
    description: '고객사를 만들고 고객을 초대하면 고객이 포털에서 문의를 올립니다.',
    icon: '📭',
  },
};
export const NotFound: Story = {
  args: { headingLevel: 1, title: '찾을 수 없습니다', action: <a href="/issues">목록으로</a> },
};
export const Forbidden: Story = { args: { headingLevel: 1, title: '권한이 없습니다' } };
