// 10b 묶음 screens.md "C-11 휴지통": 자동 정리가 옮긴 문의는 지운 사람이 null이고, "지운 사람" 칸에 "자동 정리"를 회색 글자
// (`pd-muted`)로 보인다. 사람이 지운 줄은 그대로 이름이고, 복구 단추도 사람이 지운 줄과 같다(S10B-AC-22의 화면).
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, found, renderAt, screen, spokenText, text, within } from '../__tests__/console';
import { ACME_12, URL_TRASH, s7a, trashRow, trashTable, trashed } from '../__tests__/console7a';
import { BY_CLEANUP } from '../__tests__/console10b';

/** The cell of `row` under the column whose head is `column`. */
async function cellOf(row: HTMLElement, column: string): Promise<HTMLElement> {
  const head = await found(() => screen.queryAllByRole('columnheader').find((h) => text(h) === column), `열 머리 "${column}"`);
  const cell = row.children[Array.from(head.parentElement?.children ?? []).indexOf(head)];
  expect(cell ?? null, `"${column}" 칸`).not.toBeNull();
  return cell as HTMLElement;
}

const BY_SYSTEM = trashed({ key: 'ACME-40', title: '오래전에 종료된 문의', status: 'CLOSED', deletedBy: null });

describe('C-11 휴지통의 자동 정리 줄 (10b 묶음)', () => {
  test('S10B-AC-22 C-11 지운 사람이 null인 줄: "지운 사람" 칸이 회색 글자 "자동 정리"이고 복구·영구 삭제 단추가 있다. 사람이 지운 줄은 이름이다. axe 위반이 없다', async () => {
    s7a.trash = [BY_SYSTEM, ACME_12];
    renderAt(URL_TRASH);

    await trashTable(2);
    const row = await trashRow('ACME-40');
    const who = await cellOf(row, '지운 사람');
    expect(spokenText(who)).toBe(BY_CLEANUP);
    const muted = [who, ...Array.from(who.querySelectorAll('*'))].filter((el) => el.classList.contains('pd-muted'));
    expect(muted.map((el) => text(el)), '회색 글자(pd-muted)').toContain(BY_CLEANUP);
    expect(within(row).getByRole('button', { name: 'ACME-40 복구' })).toBeTruthy();
    expect(within(row).getByRole('button', { name: 'ACME-40 영구 삭제' })).toBeTruthy();

    const person = await cellOf(await trashRow('ACME-12'), '지운 사람');
    expect(spokenText(person)).toContain('최관리');
    expect(spokenText(person)).not.toContain(BY_CLEANUP);
    await expectNoAxeViolations();
  });
});
