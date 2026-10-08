import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import { AppShell } from './AppShell';

const meta = {
  component: AppShell,
  args: {
    app: 'console',
    displayName: 'popdesk',
    nav: [
      { href: '/issues', label: '문의', current: true },
      { href: '/companies', label: '고객사' },
      { href: '/members', label: '구성원' },
      { href: '/system', label: '시스템 상태' },
    ],
    userName: '관리자',
    onLogout: fn(),
    logoutLabel: '로그아웃',
    skipLinkLabel: '본문으로 건너뛰기',
    onSidebarToggle: fn(),
    children: '본문',
  },
  parameters: { layout: 'fullscreen' },
} satisfies Meta<typeof AppShell>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ConsoleAdmin: Story = {};
/** 상담원에게 관리자 메뉴는 없다. */
export const ConsoleAgent: Story = { args: { userName: '박상담', nav: [{ href: '/issues', label: '문의', current: true }] } };
export const ConsoleCollapsed: Story = { args: { sidebarCollapsed: true } };
export const Portal: Story = { args: { app: 'portal', nav: [], userName: '김하나' } };
