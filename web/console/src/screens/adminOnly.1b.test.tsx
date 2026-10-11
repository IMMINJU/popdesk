// 1b screens.md 2절 옆 메뉴: 관리자에게 "감사 로그"가 "구성원"과 "시스템 상태" 사이에 있고 상담원에게는 없다.
// 상담원이 주소로 오면 화면 전체가 "권한이 없습니다"(S1B-AC-35).
import { describe, expect, test } from 'vitest';
import { AGENT, callsTo, eventually, found, hrefPath, menuLinks, pageText, renderAt, screen, state } from '../__tests__/console';
import { AUDIT } from '../__tests__/console1b';

const menuShown = () => found(() => screen.queryByRole('link', { name: '문의' }), '메뉴 "문의"');

describe('옆 메뉴와 관리자 전용 화면 (1b)', () => {
  test('S1B-AC-35 메뉴(관리자): 고객사, 구성원, 감사 로그, 시스템 상태 차례이고 "감사 로그"는 /audit-log로 간다', async () => {
    renderAt('/issues');
    await menuShown();

    expect(menuLinks()).toEqual(['문의', '고객사', '구성원', '감사 로그', '시스템 상태']);
    expect(hrefPath(screen.getByRole('link', { name: '감사 로그' }))).toBe('/audit-log');
  });

  test('S1B-AC-35 메뉴(상담원): "감사 로그"는 없다(막아 두지 않고 없다)', async () => {
    state.me = AGENT;
    renderAt('/issues');
    await menuShown();

    expect(menuLinks()).toEqual(['문의']);
    expect(screen.queryByRole('button', { name: '감사 로그' })).toBeNull();
  });

  test('S1B-AC-35 C-12 상담원이 감사 로그 주소로 오면 화면 전체가 "권한이 없습니다"이고 목록을 부르지 않는다', async () => {
    state.me = AGENT;
    renderAt('/audit-log');

    await eventually(() => {
      expect(pageText()).toContain('권한이 없습니다');
    });
    expect(screen.queryAllByRole('table')).toHaveLength(0);
    expect(screen.queryByRole('button', { name: /내보내기|무결성 확인/ })).toBeNull();
    expect(callsTo('GET', AUDIT)).toHaveLength(0);
  });
});
