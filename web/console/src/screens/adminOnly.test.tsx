// screens.md 4절 공통 머리: 관리자 메뉴(고객사·구성원·시스템 상태)는 관리자에게만 보인다.
// 상담원이 관리자 화면 주소로 바로 오면 403 → 화면 전체 "권한이 없습니다" 빈 상태(S0-AC-30, S0-AC-81).
import { describe, expect, test } from 'vitest';
import {
  AGENT,
  callsTo,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  hrefPath,
  menuLinks,
  on,
  pageText,
  queryAction,
  renderAt,
  screen,
  state,
} from '../__tests__/console';

const ADMIN_MENU = ['고객사', '구성원', '시스템 상태'] as const;

async function menuShown() {
  return found(() => screen.queryByRole('link', { name: '문의' }), '메뉴 "문의"');
}

async function noPermissionScreen() {
  await eventually(() => {
    expect(pageText()).toContain('권한이 없습니다');
  });
}

describe('관리자 전용 화면', () => {
  test('S0-AC-30 S0-AC-81 메뉴(관리자): 문의, 고객사, 구성원, 시스템 상태', async () => {
    renderAt('/issues');
    await menuShown();

    const menu = menuLinks();
    for (const name of ['문의', ...ADMIN_MENU]) expect(menu, `메뉴 "${name}"`).toContain(name);
    const targets = Object.fromEntries(
      ADMIN_MENU.map((name) => [name, hrefPath(screen.getByRole('link', { name }))]),
    );
    expect(targets).toEqual({ 고객사: '/companies', 구성원: '/members', '시스템 상태': '/system' });
  });

  test('S0-AC-30 S0-AC-81 메뉴(상담원): 관리자 메뉴는 숨긴다(막아 두지 않고 없다)', async () => {
    state.me = AGENT;
    renderAt('/issues');
    await menuShown();

    for (const name of ADMIN_MENU) {
      expect(screen.queryByRole('link', { name }), `메뉴 "${name}"`).toBeNull();
      expect(screen.queryByRole('button', { name }), `메뉴 "${name}"`).toBeNull();
    }
  });

  test('S0-AC-30 C-8 상담원이 고객사 목록 주소로 오면 화면 전체가 "권한이 없습니다", "고객사 만들기"가 없다', async () => {
    state.me = AGENT;
    renderAt('/companies');

    await noPermissionScreen();
    expect(queryAction('고객사 만들기')).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(callsTo('POST', '/api/console/companies')).toHaveLength(0);
  });

  test('S0-AC-30 C-9 상담원이 고객사 상세 주소로 오면 "권한이 없습니다", 배정·초대 버튼이 없다', async () => {
    state.me = AGENT;
    renderAt('/companies/ACME');

    await noPermissionScreen();
    expect(screen.queryByRole('button', { name: '상담원 배정' })).toBeNull();
    expect(queryAction('이 고객사로 고객 초대')).toBeNull();
    expect(callsTo('PUT', /\/api\/console\/companies\/[^/]+\/agents\/\d+$/)).toHaveLength(0);
  });

  test('C-10 상담원이 구성원 주소로 오면 "권한이 없습니다", 초대 버튼이 없다', async () => {
    state.me = AGENT;
    renderAt('/members');

    await noPermissionScreen();
    expect(screen.queryByRole('button', { name: /^초대$/ })).toBeNull();
    expect(screen.queryAllByRole('button', { name: '초대 링크 복사' })).toHaveLength(0);
  });

  test('S0-AC-81 C-18 상담원이 시스템 상태 주소로 오면 "권한이 없습니다", 채널 표가 없다', async () => {
    state.me = AGENT;
    renderAt('/system');

    await noPermissionScreen();
    expect(pageText()).not.toContain('메일 보내기');
    expect(screen.queryAllByRole('table')).toHaveLength(0);
  });

  test('S0-AC-81 C-18 API가 403이면(역할이 그 사이 바뀜) 화면 전체 "권한이 없습니다"', async () => {
    on('get', '/api/console/system/status', () => errors.forbidden());
    on('get', '/api/console/system/failed-deliveries', () => errors.forbidden());
    renderAt('/system');

    await noPermissionScreen();
    expect(pageText()).not.toContain('메일 보내기');
  });

  test('S0-AC-30 axe: 상담원의 "권한이 없습니다" 화면에 접근성 위반이 없다', async () => {
    state.me = AGENT;
    renderAt('/companies');
    await noPermissionScreen();

    await expectNoAxeViolations();
  });
});
