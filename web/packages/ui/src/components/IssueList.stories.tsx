import type { Meta, StoryObj } from '@storybook/react-vite';
import { IssueList } from './IssueList';

const meta = {
  component: IssueList,
  args: {
    privateLabel: '비공개',
    empty: '아직 올린 문의가 없습니다.',
    items: [
      {
        key: 'ACME-7',
        title: '첨부가 안 올라갑니다',
        href: '/issues/ACME-7',
        projectName: 'Acme',
        status: 'RECEIVED',
        priority: 'HIGH',
        authorLabel: '나',
        isPrivate: true,
        createdAt: '2026-10-09T01:00:00Z',
      },
      {
        key: 'ACME-6',
        title: '로그인 화면이 하얗게 나옵니다',
        href: '/issues/ACME-6',
        projectName: 'Acme',
        status: 'IN_PROGRESS',
        priority: 'NORMAL',
        authorLabel: '김하나',
        isPrivate: false,
        createdAt: '2026-10-08T05:30:00Z',
      },
    ],
  },
} satisfies Meta<typeof IssueList>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Loading: Story = { args: { status: 'loading' } };
export const Empty: Story = { args: { items: [] } };
export const WithData: Story = {};
/**
 * 라벨과 마감일 (6a 묶음). 껍데기라 칩을 다 보이고 날짜를 받은 그대로 보인다.
 * 칩 둘과 "+n", "10월 20일" 꼴, "지남"은 C-9가 채운다.
 */
export const WithLabelsAndDue: Story = {
  args: {
    moreLabelsText: (names) => `라벨 더: ${names.join(', ')}`,
    dueLabel: '마감일',
    overdueText: '지남',
    items: [
      {
        key: 'ACME-7',
        title: '첨부가 안 올라갑니다',
        href: '/issues/ACME-7',
        projectName: 'Acme',
        status: 'RECEIVED',
        priority: 'HIGH',
        authorLabel: '나',
        isPrivate: false,
        createdAt: '2026-10-09T01:00:00Z',
        labels: [
          { id: '1', name: '결제', color: '#245FB0' },
          { id: '2', name: '로그인', color: '#1F7A3A' },
          { id: '3', name: '모바일', color: '#F2C14E' },
          { id: '4', name: '보안', color: '#6D28D9' },
        ],
        dueDate: '2026-10-08',
        overdue: true,
      },
      {
        key: 'ACME-6',
        title: '로그인 화면이 하얗게 나옵니다',
        href: '/issues/ACME-6',
        projectName: 'Acme',
        status: 'IN_PROGRESS',
        priority: 'NORMAL',
        authorLabel: '김하나',
        isPrivate: false,
        createdAt: '2026-10-08T05:30:00Z',
        labels: [{ id: '2', name: '로그인', color: '#1F7A3A' }],
        dueDate: '2027-01-05',
        overdue: false,
      },
    ],
  },
};
