import type { Meta, StoryObj } from '@storybook/react-vite';
import { StatusBadge } from './StatusBadge';

const meta = {
  component: StatusBadge,
  args: { kind: 'issue', status: 'RECEIVED', audience: 'customer' },
} satisfies Meta<typeof StatusBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

// 문의 상태, 고객용 말
export const IssueReceived: Story = {};
export const IssueConfirmed: Story = { args: { kind: 'issue', status: 'CONFIRMED', audience: 'customer' } };
export const IssueInProgress: Story = { args: { kind: 'issue', status: 'IN_PROGRESS', audience: 'customer' } };
export const IssueResolved: Story = { args: { kind: 'issue', status: 'RESOLVED', audience: 'customer' } };
export const IssueClosed: Story = { args: { kind: 'issue', status: 'CLOSED', audience: 'customer' } };

// 문의 상태, 상담용 말
export const IssueAgentReceived: Story = { args: { kind: 'issue', status: 'RECEIVED', audience: 'agent' } };
export const IssueAgentClosed: Story = { args: { kind: 'issue', status: 'CLOSED', audience: 'agent' } };

// 고객사 상태
export const ProjectActive: Story = { args: { kind: 'project', status: 'ACTIVE' } };
export const ProjectSuspended: Story = { args: { kind: 'project', status: 'SUSPENDED' } };
export const ProjectArchived: Story = { args: { kind: 'project', status: 'ARCHIVED' } };

// 계정 상태
export const AccountActive: Story = { args: { kind: 'account', status: 'ACTIVE' } };
export const AccountInvited: Story = { args: { kind: 'account', status: 'INVITED' } };
export const AccountSuspended: Story = { args: { kind: 'account', status: 'SUSPENDED' } };
