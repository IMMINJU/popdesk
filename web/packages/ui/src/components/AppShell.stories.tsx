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
/**
 * 2번 묶음: "고객사"에 걸린 소속 요청의 합을 글자 뒤에 그린다. 링크의 이름은 "고객사 소속 요청 3건"이다.
 * "구성원"처럼 0을 주면 주지 않은 것과 같다.
 */
export const ConsoleNavCount: Story = {
  args: {
    nav: [
      { href: '/issues', label: '문의', current: true },
      { href: '/companies', label: '고객사', count: 3, countLabel: '소속 요청 3건' },
      { href: '/members', label: '구성원', count: 0 },
      { href: '/system', label: '시스템 상태' },
    ],
  },
};
/** 2번 묶음: 담당자의 포털 메뉴. 위 띠에서 이름 바로 옆에 붙고, 640px 아래에서는 띠 아래 둘째 줄로 내려간다. */
export const PortalManager: Story = {
  args: {
    app: 'portal',
    userName: '김하나',
    navLabel: '메뉴',
    nav: [
      { href: '/issues', label: '문의', current: true },
      { href: '/members', label: '우리 회사 사람' },
    ],
  },
};
