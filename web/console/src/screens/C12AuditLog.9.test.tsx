// 9번 묶음 screens.md 4절 C-12 감사 로그: 종류 BRANDING_CHANGED "브랜딩 바꾸기"와 BRANDING_LOGO_CHANGED "로고 바꾸기"("설정" 묶음),
// 대상 글 "브랜딩"과 "브랜딩 (밝은 테마)"·"(어두운 테마)", 바뀐 값 줄(표시 이름, 강조색의 null은 "기본", 로고는 지문을 보이지 않고
// "없음"·"바뀜")(S9-AC-20, 21의 화면).
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, found, pageText, renderAt, text } from '../__tests__/console';
import { audit, column, entry, entryRows, kindSelect, listedKinds } from '../__tests__/console1b';

const URL_LOG = '/audit-log';
const LIGHT_PRINT = '0123456789abcdef';
const DARK_PRINT = 'fedcba9876543210';

/** An entry of 최관리 changing the name or the accent. */
function brandingChanged(changes: Record<string, unknown>, id = 9101) {
  return entry({ id, action: 'BRANDING_CHANGED', target: { type: 'BRANDING' }, changes });
}

/** An entry of 최관리 changing the logo of `theme`. */
function logoChanged(theme: 'LIGHT' | 'DARK', from: string | null, to: string | null, id = 9201) {
  return entry({ id, action: 'BRANDING_LOGO_CHANGED', target: { type: 'BRANDING', theme }, changes: { logo: { from, to } } });
}

describe('C-12 감사 로그의 브랜딩 항목 (9번 묶음)', () => {
  test('S9-AC-20 C-12 브랜딩 바꾸기: 일 "브랜딩 바꾸기", 대상 "브랜딩", 바뀐 값 "표시 이름: popdesk → Acme 지원"과 "강조색: 기본 → #6D28D9"', async () => {
    audit.entries = [brandingChanged({ displayName: { from: 'popdesk', to: 'Acme 지원' }, accent: { from: null, to: '#6D28D9' } })];
    renderAt(URL_LOG);

    await entryRows(1);
    expect(column('일')).toEqual(['브랜딩 바꾸기']);
    expect(column('대상')).toEqual(['브랜딩']);
    const [shown] = column('바뀐 값');
    expect(shown).toContain('표시 이름: popdesk → Acme 지원');
    expect(shown).toContain('강조색: 기본 → #6D28D9');
    await expectNoAxeViolations();
  });

  test('S9-AC-20 C-12 바뀐 칸만: 강조색을 기본으로 되돌린 항목은 "강조색: #6D28D9 → 기본" 한 줄이고 표시 이름 줄이 없다', async () => {
    audit.entries = [brandingChanged({ accent: { from: '#6D28D9', to: null } })];
    renderAt(URL_LOG);

    await entryRows(1);
    const [shown] = column('바뀐 값');
    expect(shown).toContain('강조색: #6D28D9 → 기본');
    expect(shown).not.toContain('표시 이름');
  });

  test('S9-AC-21 C-12 로고 바꾸기: 대상 "브랜딩 (밝은 테마)"·"브랜딩 (어두운 테마)", 바뀐 값 "로고: 없음 → 바뀜", "로고: 바뀜 → 없음", "로고: 바뀜". 지문은 보이지 않는다', async () => {
    audit.entries = [
      logoChanged('LIGHT', null, LIGHT_PRINT, 9203),
      logoChanged('DARK', DARK_PRINT, null, 9202),
      logoChanged('LIGHT', LIGHT_PRINT, DARK_PRINT, 9201),
    ];
    renderAt(URL_LOG);

    await entryRows(3);
    expect(column('일')).toEqual(['로고 바꾸기', '로고 바꾸기', '로고 바꾸기']);
    expect(column('대상')).toEqual(['브랜딩 (밝은 테마)', '브랜딩 (어두운 테마)', '브랜딩 (밝은 테마)']);
    const shown = column('바뀐 값');
    expect(shown[0]).toContain('로고: 없음 → 바뀜');
    expect(shown[1]).toContain('로고: 바뀜 → 없음');
    expect(shown[2]).toContain('로고: 바뀜');
    expect(shown[2]).not.toContain('→');
    expect(pageText(), '지문').not.toContain(LIGHT_PRINT);
    expect(pageText(), '지문').not.toContain(DARK_PRINT);
  });

  test('S9-AC-20 C-12 종류에서 "브랜딩 바꾸기"만 고르면 action이 BRANDING_CHANGED 하나다', async () => {
    const { user } = renderAt(URL_LOG);
    await entryRows(audit.entries.length);

    const kind = await kindSelect();
    const choice = await found(() => Array.from(kind.querySelectorAll('option')).find((o) => text(o) === '브랜딩 바꾸기'), '고르기 "브랜딩 바꾸기"');

    await user.selectOptions(kind, choice);

    await listedKinds(['BRANDING_CHANGED']);
  });
});
