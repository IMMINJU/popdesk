import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { EmptyState } from './EmptyState';
import { InboxList, type InboxListItem } from './InboxList';

const issue = { issueKey: 'ACME-7', issueTitle: '로그인이 안 됩니다', href: '/issues/ACME-7' };

/** 콘솔의 문장, 종류마다 한 줄. */
const staff: InboxListItem[] = [
  { id: 1, text: '김고객 님이 댓글을 남겼습니다', ...issue, href: '/issues/ACME-7#comment-12', unread: true, createdAt: '2026-10-09T01:00:00Z' },
  { id: 2, text: '정상담 님이 댓글에서 나를 언급했습니다', ...issue, unread: true, createdAt: '2026-10-09T00:40:00Z' },
  { id: 3, text: '최관리 님이 이 문의를 나에게 맡겼습니다', ...issue, createdAt: '2026-10-08T05:30:00Z' },
  { id: 4, text: '김고객 님이 문의를 종료했습니다', ...issue, createdAt: '2026-10-08T03:00:00Z' },
  { id: 5, text: '김고객 님이 문의를 다시 열었습니다', ...issue, unread: true, createdAt: '2026-10-07T03:00:00Z' },
  { id: 6, text: '고객사 ACME에 소속 요청이 있습니다', href: '/companies/ACME', createdAt: '2026-10-06T03:00:00Z' },
  { id: 7, text: '보내지 못한 메일이나 웹훅이 있습니다', href: '/system', unread: true, createdAt: '2026-10-05T03:00:00Z' },
  { id: 8, text: '첨부 용량이 한도의 80%를 넘었습니다', href: '/system', createdAt: '2026-10-04T03:00:00Z' },
];

/** 포털의 문장: 일으킨 사람은 늘 "지원팀"이다. */
const portal: InboxListItem[] = [
  { id: 1, text: '지원팀이 답변했습니다', ...issue, href: '/issues/ACME-7#comment-12', unread: true, createdAt: '2026-10-09T01:00:00Z' },
  { id: 2, text: '지원팀이 문의를 해결로 바꿨습니다. 확인해 주세요', ...issue, unread: true, createdAt: '2026-10-08T05:30:00Z' },
  { id: 3, text: '지원팀이 문의를 종료했습니다', ...issue, createdAt: '2026-10-08T03:00:00Z' },
  { id: 4, text: '지원팀이 문의를 다시 열었습니다', ...issue, createdAt: '2026-10-07T03:00:00Z' },
  { id: 5, text: '지원팀이 문의를 대신 올렸습니다', ...issue, createdAt: '2026-10-06T03:00:00Z' },
  { id: 6, text: '지원팀이 댓글에서 회원님을 언급했습니다', ...issue, createdAt: '2026-10-05T03:00:00Z' },
];

const meta = {
  component: InboxList,
  args: { items: staff, unreadLabel: '안 읽음', onOpen: fn() },
} satisfies Meta<typeof InboxList>;

export default meta;
type Story = StoryObj<typeof meta>;

/** 안 읽음과 읽음이 섞임, 종류마다 한 줄(콘솔). */
export const Mixed: Story = {};
/** 종류마다 한 줄(포털). */
export const Portal: Story = { args: { items: portal } };
/** 긴 제목과 띄어쓰기 없는 긴 낱말: 낱말 사이에서 줄을 바꾸고 줄 밖으로 넘치지 않는다. */
export const LongTitles: Story = {
  args: {
    items: [
      {
        id: 1,
        text: '김고객 님이 댓글을 남겼습니다',
        issueKey: 'LONGCODE-1024',
        issueTitle: '결제 화면에서 카드 정보를 넣고 다음으로 넘어가면 화면이 하얗게 바뀌고 아무 반응이 없습니다 (크롬과 엣지 모두)',
        href: '/issues/LONGCODE-1024',
        unread: true,
        createdAt: '2026-10-09T01:00:00Z',
      },
      {
        id: 2,
        text: '최관리 님이 이 문의를 나에게 맡겼습니다',
        issueKey: 'ACME-8',
        issueTitle: 'https://example.test/very/long/path/that/has/no/space/at/all/and/keeps/going/until/the/line/ends?query=1',
        href: '/issues/ACME-8',
        createdAt: '2026-10-08T01:00:00Z',
      },
    ],
  },
};
/** 모르는 종류: 문의가 있으면 그 문의로 가는 링크, 없으면 글자만 있는 줄이다. */
export const UnknownKind: Story = {
  args: {
    items: [
      { id: 1, text: '새 알림이 있습니다', ...issue, unread: true, createdAt: '2026-10-09T01:00:00Z' },
      { id: 2, text: '새 알림이 있습니다', unread: true, createdAt: '2026-10-08T01:00:00Z' },
    ],
  },
};
/** 빈 상태는 화면이 `EmptyState`로 그린다. */
export const Empty: Story = {
  render: () => <EmptyState title="알림이 없습니다" />,
};
