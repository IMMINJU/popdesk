// 1a screens.md 2절 C-8 고객사 목록: 줄마다 상태 배지(사용 중, 중지, 보관). 보관된 고객사는 기본으로 숨기고
// "보관된 고객사 보기"를 켜면 나온다(S1A-AC-49).
import { describe, expect, test } from 'vitest';
import { ACME, company, found, renderAt, screen, state, text, within } from '../__tests__/console';
import { rowOf } from '../__tests__/console1a';

const showArchived = () =>
  found(
    () =>
      screen.queryByRole('checkbox', { name: '보관된 고객사 보기' }) ??
      screen.queryByRole('switch', { name: '보관된 고객사 보기' }) ??
      screen.queryByRole('button', { name: '보관된 고객사 보기' }),
    '"보관된 고객사 보기"',
  );

const codes = () =>
  screen
    .queryAllByRole('row')
    .map((r) => text(within(r).queryAllByRole('cell')[0]))
    .filter((code) => code !== '');

describe('C-8 고객사 목록 (1a)', () => {
  test('S1A-AC-49 C-8 상태 배지와 보관 숨기기: 중지된 고객사는 "중지", 보관된 고객사는 숨겼다가 "보관된 고객사 보기"를 켜면 "보관"으로 나온다', async () => {
    state.companies = [
      ACME,
      company({ code: 'BETA', name: '베타상사', legalName: '베타상사 유한회사', status: 'SUSPENDED' }),
      company({ code: 'OLD', name: '올드', legalName: '올드 주식회사', status: 'ARCHIVED' }),
    ];
    const { user } = renderAt('/companies');

    expect(text(await rowOf('ACME'))).toContain('사용 중');
    const suspended = text(await rowOf('BETA'));
    expect(suspended).toContain('중지');
    expect(suspended).not.toContain('멈춤');
    const toggle = await showArchived();
    expect(codes(), '처음에는 보관된 고객사가 없다').toEqual(['ACME', 'BETA']);

    await user.click(toggle);

    expect(text(await rowOf('OLD'))).toContain('보관');
    expect(codes()).toEqual(['ACME', 'BETA', 'OLD']);
  });
});
