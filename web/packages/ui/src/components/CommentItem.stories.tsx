import type { Meta, StoryObj } from '@storybook/react-vite';
import { CommentItem } from './CommentItem';

const meta = {
  component: CommentItem,
  args: {
    author: { kind: 'person', name: '김하나' },
    visibility: 'public',
    createdAt: '2026-10-09T01:00:00Z',
    body: '첨부를 올리면 오류가 납니다.',
    supportLabel: '지원팀',
    meLabel: '나',
    internalLabel: '내부 메모',
    deletedLabel: '지워진 댓글',
  },
} satisfies Meta<typeof CommentItem>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Public: Story = {};
export const Mine: Story = { args: { author: { kind: 'person', name: '김하나', isMe: true } } };
/** Portal: agents show as 지원팀 (D-49). */
export const Support: Story = { args: { author: { kind: 'support' }, body: '확인했습니다. 로그를 보내 주세요.' } };
export const Internal: Story = {
  args: { author: { kind: 'person', name: '박상담' }, visibility: 'internal', body: '서버 로그 확인 중.' },
};
export const Deleted: Story = { args: { deleted: true } };
