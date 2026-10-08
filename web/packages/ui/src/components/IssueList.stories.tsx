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
