import type { Meta, StoryObj } from '@storybook/react-vite';
import { commentActionDescribedBy } from '../aria';
import { Button } from './Button';
import { CommentItem } from './CommentItem';
import { MarkdownEditor } from './MarkdownEditor';

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
    editedLabel: '고침',
    mentionsLabel: '알림',
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

// 4번 묶음(screens.md 2절 "P-5, C-4 댓글 구역")의 껍데기다. 받은 것을 제자리에 그리기만 하고 모양은 C-9가 채운다.
const about = commentActionDescribedBy('comment-7');
const edit = (
  <Button variant="quiet" size="sm" describedBy={about}>
    댓글 고치기
  </Button>
);
const remove = (
  <Button variant="quiet" size="sm" describedBy={about}>
    댓글 지우기
  </Button>
);

/** 고침: 쓴 시각 옆에 "고침"과 고친 시각. */
export const Edited: Story = { args: { editedAt: '2026-10-09T03:30:00Z' } };
/** 내 댓글: 머리 줄 끝에 "댓글 고치기"와 "댓글 지우기". 버튼은 쓴 사람과 시각을 `aria-describedby`로 잇는다. */
export const ActionsBoth: Story = {
  args: {
    id: 'comment-7',
    author: { kind: 'person', name: '김하나', isMe: true },
    actions: (
      <>
        {edit}
        {remove}
      </>
    ),
  },
};
/** 관리자가 보는 남의 댓글: "댓글 지우기"만. */
export const ActionsDeleteOnly: Story = {
  args: { id: 'comment-7', author: { kind: 'person', name: '박상담' }, actions: remove },
};
/** 콘솔: 본문 아래 멘션 줄. 사진은 그리지 않는다. */
export const Mentions: Story = {
  args: { author: { kind: 'person', name: '박상담' }, mentions: ['김상담', '이관리'] },
};
/** 콘솔: 지운 사람과 지운 시각. 포털은 `deletedByLabel` 없이 시각만 준다. */
export const DeletedBy: Story = {
  args: { deleted: true, deletedByLabel: '이관리 님이 지움', deletedAt: '2026-10-09T05:00:00Z' },
};
/** 고치는 중: 본문 자리에 편집기와 버튼. 이때 동작 버튼은 주지 않는다. */
export const Editing: Story = {
  args: {
    id: 'comment-7',
    author: { kind: 'person', name: '김하나', isMe: true },
    editing: (
      <form aria-label="댓글 고치기">
        <MarkdownEditor label="댓글 내용 고치기" labelHidden value="첨부를 올리면 오류가 납니다." onChange={() => undefined} minHeight={112} />
        <Button type="submit" size="sm">
          고친 댓글 저장
        </Button>
        <Button variant="secondary" size="sm">
          고치기 취소
        </Button>
      </form>
    ),
  },
};
