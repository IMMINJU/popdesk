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

// 4번 묶음(screens.md 1절 "머리의 알림 링크")의 껍데기다. 링크를 머리 안, 메뉴 밖, 사용자 줄 앞에 그리기만 하고 모양은 C-9가 채운다.
const inbox = { href: '/inbox', label: '알림' };

/** 안 읽은 알림 0: 숫자가 없고 링크의 이름은 "알림"이다. */
export const InboxNone: Story = { args: { inbox: { ...inbox, count: 0 } } };
/** 안 읽은 알림 3: 링크의 이름은 "알림 3건"이다. */
export const InboxThree: Story = { args: { inbox: { ...inbox, count: 3, countLabel: '3건' } } };
/** 100 이상은 "99+"로 보이고 이름은 실제 수를 쓴다("알림 120건"). */
export const InboxMany: Story = { args: { inbox: { ...inbox, count: 120, countLabel: '120건' } } };
/** 지금 화면이 알림함: `aria-current="page"`. */
export const InboxCurrent: Story = {
  args: {
    nav: [
      { href: '/issues', label: '문의' },
      { href: '/companies', label: '고객사' },
      { href: '/members', label: '구성원' },
      { href: '/system', label: '시스템 상태' },
    ],
    inbox: { ...inbox, count: 3, countLabel: '3건', current: true },
  },
};
export const PortalInbox: Story = {
  args: { app: 'portal', nav: [], userName: '김하나', inbox: { ...inbox, count: 3, countLabel: '3건' } },
};
/** 담당자의 포털: 메뉴와 알림 링크가 함께 있다. */
export const PortalManagerInbox: Story = {
  args: {
    app: 'portal',
    userName: '김하나',
    navLabel: '메뉴',
    nav: [
      { href: '/issues', label: '문의', current: true },
      { href: '/members', label: '우리 회사 사람' },
    ],
    inbox: { ...inbox, count: 120, countLabel: '120건' },
  },
};
// 좁은 화면: 따로 story를 두지 않는다. 창을 1024px(콘솔), 640px(포털) 아래로 줄여 InboxThree와 PortalManagerInbox를 본다.
