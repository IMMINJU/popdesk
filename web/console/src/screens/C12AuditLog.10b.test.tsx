// 10b 묶음 screens.md "C-12 감사 로그": 새 종류 둘(COMPANY_EXPORTED "고객사 내보내기", COMPANY_EXPORT_DOWNLOADED "고객사 내보내기
// 받기")은 "고객사" 묶음에 들고 "내보내기" 묶음은 그대로 둘이다(10a의 잠긴 테스트, design.md 2절). 대상은 "고객사 {코드}",
// 바뀐 값 이름은 includeInternal "내부 넣기", includePrivate "비공개 넣기", exportId "작업"(S10B-AC-09의 화면).
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, renderAt, text } from '../__tests__/console';
import { audit, column, entry, entryRows, kindSelect, kindsOf, listedKinds } from '../__tests__/console1b';
import { AUDIT_WORDS, CHANGE_WORDS, exportId } from '../__tests__/console10b';

const URL_LOG = '/audit-log';
const COMPANY_ACME = { type: 'COMPANY', code: 'ACME' } as const;

const EXPORTED = entry({ id: 9401, action: 'COMPANY_EXPORTED', target: COMPANY_ACME, changes: { includeInternal: true, includePrivate: false } });
const DOWNLOADED = entry({ id: 9402, action: 'COMPANY_EXPORT_DOWNLOADED', target: COMPANY_ACME, changes: { exportId: exportId(3) } });

describe('C-12 감사 로그의 고객사 내보내기 항목 (10b 묶음)', () => {
  test.each([
    ['COMPANY_EXPORTED', EXPORTED, AUDIT_WORDS.COMPANY_EXPORTED],
    ['COMPANY_EXPORT_DOWNLOADED', DOWNLOADED, AUDIT_WORDS.COMPANY_EXPORT_DOWNLOADED],
  ])('S10B-AC-09 C-12 %s: 일의 이름과 대상 "고객사 ACME"', async (_, one, word) => {
    audit.entries = [one];
    renderAt(URL_LOG);

    await entryRows(1);
    expect(column('일')).toEqual([word]);
    expect(column('대상')).toEqual(['고객사 ACME']);
  });

  test('S10B-AC-09 C-12 고객사 내보내기의 바뀐 값: "내부 넣기: 예", "비공개 넣기: 아니오". 받기의 바뀐 값: "작업: {작업 ID}". 칸 이름은 그대로 쓰지 않는다', async () => {
    audit.entries = [DOWNLOADED, EXPORTED];
    renderAt(URL_LOG);

    await entryRows(2);
    const [downloaded, exported] = column('바뀐 값');
    expect(exported).toContain(`${CHANGE_WORDS.includeInternal}: 예`);
    expect(exported).toContain(`${CHANGE_WORDS.includePrivate}: 아니오`);
    expect(downloaded).toContain(`${CHANGE_WORDS.exportId}: ${exportId(3)}`);
    for (const raw of ['includeInternal', 'includePrivate', 'exportId']) expect(`${String(exported)} ${String(downloaded)}`).not.toContain(raw);
  });

  test('S10B-AC-09 C-12 종류 고르기: "고객사" 아래에 고객사 내보내기와 받기가 있고 "내보내기" 묶음은 그대로 둘이다. "고객사"를 고르면 둘도 보낸다. axe 위반이 없다', async () => {
    audit.entries = [EXPORTED, DOWNLOADED];
    const { user } = renderAt(URL_LOG);
    await entryRows(2);
    const kind = await kindSelect();

    const groups = Array.from(kind.querySelectorAll('optgroup'));
    const wordsOf = (name: string) => Array.from(groups.find((g) => g.label === name)?.querySelectorAll('option') ?? []).map((o) => text(o));
    expect(wordsOf('고객사')).toEqual(expect.arrayContaining([AUDIT_WORDS.COMPANY_EXPORTED, AUDIT_WORDS.COMPANY_EXPORT_DOWNLOADED]));
    expect(wordsOf('내보내기').sort()).toEqual(['감사 로그 내보내기', '계정 정보 내보내기']);
    await expectNoAxeViolations();

    await user.selectOptions(kind, '고객사');
    expect(kindsOf('고객사')).toEqual(expect.arrayContaining(['COMPANY_EXPORTED', 'COMPANY_EXPORT_DOWNLOADED']));
    await listedKinds(kindsOf('고객사'));
  });
});
