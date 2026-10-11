// 1b screens.md 1절 C-12 감사 로그의 동작 줄: 내보내기(S1B-AC-23, 28), 무결성 확인(S1B-AC-37, 42),
// 떠나거나 필터가 바뀐 뒤 온 답 버리기(S1B-AC-38).
// 내려받기는 화면이 주소로 옮겨 가는 것이라 jsdom에서는 보이지 않는다. 첨부 내려받기 테스트처럼 HEAD와 띠로 본다.
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  action,
  alertWith,
  atPath,
  button,
  callsTo,
  errors,
  eventually,
  gate,
  isBlocked,
  link,
  on,
  pageText,
  queryAction,
  renderAt,
  sent,
  text,
} from '../__tests__/console';
import { answered, rejected, settle } from '../__tests__/console1a';
import {
  AUDIT,
  AUDIT_EXPORT,
  AUDIT_VERIFY,
  EXPORTED,
  SIGNED_IN,
  SUSPENDED,
  VALID,
  audit,
  chosenKind,
  column,
  entry,
  entryRows,
  exportBusy,
  kindSelect,
  lastList,
  listedKinds,
  type AuditVerification,
} from '../__tests__/console1b';

const URL_LOG = '/audit-log';
const EXPORT_HELP = '지금 건 조건의 항목을 모두 받습니다. 내보낸 일은 감사 로그에 남습니다.';
const EXPORT_FILTERED = '조건을 건 파일로는 사슬을 확인할 수 없습니다. 확인하려면 조건을 지우고 JSON Lines로 받으세요.';
const EXPORT_BUSY = '다른 내보내기가 돌고 있습니다. 잠시 뒤 다시 하세요.';
const VERIFY_HELP = '항목의 해시를 처음부터 다시 계산해, 고치거나 지운 흔적이 있는지 봅니다.';
const VERIFY_LIMIT = 'DB를 직접 고칠 수 있는 사람이 사슬 전체를 다시 쓴 것은 찾지 못합니다. 바깥 사본과 함께 쓰세요.';
const VERIFY_RUNNING = '이미 확인이 돌고 있습니다. 끝난 뒤 다시 하세요.';
const VERIFY_PROXY = '항목이 많으면 앞단 프록시의 시간 제한에 걸릴 수 있습니다.';
const BROKEN_AT = '2026-10-06T05:07:00.000000Z';

const exportQuery = () => new URL(callsTo('HEAD', AUDIT_EXPORT).at(-1)?.url ?? 'http://x/').searchParams;

/** "2026년 10월 6일 14:07" in the time zone the test runs in; the hour may have a leading zero. */
function localMinute(iso: string): RegExp {
  const at = new Date(iso);
  const date = `${String(at.getFullYear())}년 ${String(at.getMonth() + 1)}월 ${String(at.getDate())}일`;
  return new RegExp(`${date} 0?${String(at.getHours())}:${String(at.getMinutes()).padStart(2, '0')}`);
}

describe('C-12 감사 로그: 내보내기', () => {
  test('S1B-AC-23 C-12 내보내기 처음: 버튼 둘과 안내문. 필터가 없으면 사슬 안내는 없다', async () => {
    renderAt(URL_LOG);

    await button('CSV로 내보내기');
    await button('JSON Lines로 내보내기');
    expect(pageText()).toContain(EXPORT_HELP);
    expect(pageText()).not.toContain(EXPORT_FILTERED);
  });

  test.each([
    ['CSV로 내보내기', 'CSV'],
    ['JSON Lines로 내보내기', 'JSONL'],
  ])('S1B-AC-23 C-12 "%s": 먼저 HEAD로 묻고, 200이면 띠 없이 내려받고 표를 다시 불러온다', async (name, format) => {
    audit.entries = [SUSPENDED, SIGNED_IN];
    on('head', AUDIT_EXPORT, () => {
      // The server records the export when the download starts.
      audit.entries = [EXPORTED, SUSPENDED, SIGNED_IN];
      return new HttpResponse(null, { status: 200, headers: { 'Content-Disposition': 'attachment; filename="audit-log"' } });
    });
    const { user } = renderAt(URL_LOG);
    await entryRows(2);
    await user.click(await button(name));

    await sent('HEAD', AUDIT_EXPORT);
    expect(exportQuery().get('format')).toBe(format);
    expect(exportQuery().has('page'), '내보내기는 쪽을 나누지 않는다').toBe(false);
    await entryRows(3);
    expect(column('일')[0]).toBe('감사 로그 내보내기');
    expect(pageText()).not.toContain(EXPORT_BUSY);
  });

  test('S1B-AC-23 C-12 내보내기(필터가 걸려 있음): 사슬 안내 한 줄을 더하고, 보기와 같은 필터를 보낸다', async () => {
    const { user } = renderAt(URL_LOG);
    await entryRows(2);
    await user.selectOptions(await kindSelect(), '계정 정지');
    await listedKinds(['ACCOUNT_SUSPENDED']);

    await eventually(() => {
      expect(pageText()).toContain(EXPORT_FILTERED);
    });
    expect(pageText()).toContain(EXPORT_HELP);
    await user.click(await button('JSON Lines로 내보내기'));
    await sent('HEAD', AUDIT_EXPORT);
    expect(exportQuery().getAll('action')).toEqual(['ACCOUNT_SUSPENDED']);
    expect(exportQuery().get('format')).toBe('JSONL');
  });

  test('S1B-AC-28 C-12 내보내기 503: HEAD가 503이면 띠 "다른 내보내기가 돌고 있습니다…"를 보이고 내려받지 않는다', async () => {
    on('head', AUDIT_EXPORT, () => exportBusy());
    const { user } = renderAt(URL_LOG);
    await entryRows(2);
    await user.click(await button('CSV로 내보내기'));

    await alertWith(EXPORT_BUSY);
    await settle();
    expect(callsTo('HEAD', AUDIT_EXPORT)).toHaveLength(1);
    expect(callsTo('GET', AUDIT_EXPORT), '내려받기 요청').toHaveLength(0);
    expect(isBlocked(await button('CSV로 내보내기')), '다시 누를 수 있다').toBe(false);
  });
});

describe('C-12 감사 로그: 무결성 확인', () => {
  test('S1B-AC-37 C-12 무결성 확인: 도는 동안 버튼을 막고(aria-disabled) "확인하는 중…", 필터는 그대로 쓴다. 맞으면 건수와 한계 문구', async () => {
    const release = gate();
    on('post', AUDIT_VERIFY, async () => {
      await release.opened;
      return HttpResponse.json(VALID);
    });
    const { user } = renderAt(URL_LOG);
    await entryRows(2);
    const verify = await button('무결성 확인');
    expect(pageText()).toContain(VERIFY_HELP);
    await user.click(verify);

    await sent('POST', AUDIT_VERIFY);
    await eventually(() => {
      expect(verify).toHaveAttribute('aria-disabled', 'true');
      expect(pageText()).toContain('확인하는 중…');
    });
    await user.click(verify);
    expect(isBlocked(await kindSelect()), '도는 동안의 필터').toBe(false);

    release.open();
    await eventually(() => {
      expect(pageText()).toContain('모두 맞습니다. 항목 57건을 확인했습니다.');
    });
    expect(pageText()).toContain(VERIFY_LIMIT);
    expect(isBlocked(await button('무결성 확인')), '끝난 뒤').toBe(false);
    expect(callsTo('POST', AUDIT_VERIFY), '막힌 동안 누른 것은 보내지 않는다').toHaveLength(1);
  });

  test('S1B-AC-37 C-12 무결성 확인 끊긴 곳: 오류 띠에 그 항목의 시각과 종류, "이 항목 보기"', async () => {
    const broken: AuditVerification = {
      valid: false,
      checkedCount: 41,
      reason: 'ENTRY_MISMATCH',
      brokenEntry: { id: SUSPENDED.id, occurredAt: BROKEN_AT, action: 'ACCOUNT_SUSPENDED' },
    };
    audit.verification = broken;
    const { user } = renderAt(URL_LOG);
    await entryRows(2);
    await user.click(await button('무결성 확인'));

    const band = await alertWith("'계정 정지' 항목부터 맞지 않습니다. 이 항목이나 그 앞 항목이 고쳐지거나 지워졌습니다.");
    expect(text(band)).toMatch(localMinute(BROKEN_AT));
    expect(queryAction('이 항목 보기'), '"이 항목 보기"').not.toBeNull();
    expect(pageText()).not.toContain('모두 맞습니다');
  });

  test('S1B-AC-37 C-12 "이 항목 보기": 다른 필터를 지우고 기간을 그 항목의 날 하루로 걸어(부터 그 날 0시, 까지 다음 날 0시) 1쪽을 받는다', async () => {
    const at = new Date(BROKEN_AT);
    const brokenEntry = entry({ id: 7600, occurredAt: BROKEN_AT });
    // 25 suspensions: 24 on another day, and the broken one last, on the second page.
    audit.entries = [...Array.from({ length: 24 }, (_, i) => entry({ id: 7900 - i })), brokenEntry];
    audit.verification = {
      valid: false,
      checkedCount: 41,
      reason: 'ENTRY_MISMATCH',
      brokenEntry: { id: brokenEntry.id, occurredAt: BROKEN_AT, action: 'ACCOUNT_SUSPENDED' },
    };
    const first = renderAt(URL_LOG);
    await entryRows(20);
    await first.user.selectOptions(await kindSelect(), '계정 정지');
    await listedKinds(['ACCOUNT_SUSPENDED']);
    await eventually(() => {
      expect(window.location.search, '필터가 주소의 쿼리에').not.toBe('');
    });
    const filtered = `${window.location.pathname}${window.location.search}`;
    first.unmount();
    const { user } = renderAt(`${filtered}&page=2`);
    await entryRows(5);
    expect(lastList().get('page')).toBe('2');
    expect(lastList().getAll('action')).toEqual(['ACCOUNT_SUSPENDED']);
    await user.click(await button('무결성 확인'));
    await alertWith("'계정 정지' 항목부터 맞지 않습니다. 이 항목이나 그 앞 항목이 고쳐지거나 지워졌습니다.");

    await user.click(await action('이 항목 보기'));

    await eventually(() => {
      expect(lastList().has('from')).toBe(true);
    });
    const query = lastList();
    expect(new Date(query.get('from') ?? '').getTime(), '부터: 그 항목의 날 0시').toBe(
      new Date(at.getFullYear(), at.getMonth(), at.getDate()).getTime(),
    );
    expect(new Date(query.get('to') ?? '').getTime(), '까지: 다음 날 0시').toBe(
      new Date(at.getFullYear(), at.getMonth(), at.getDate() + 1).getTime(),
    );
    expect(
      ['action', 'actorAccountId', 'targetType', 'targetAccountId', 'targetCode'].filter((name) => query.has(name)),
      '기간 말고 남은 필터',
    ).toEqual([]);
    expect(query.get('page') ?? '1').toBe('1');
    const rows = await entryRows(1);
    expect(new Date(rows[0]?.querySelector('time')?.getAttribute('datetime') ?? '').getTime()).toBe(at.getTime());
    expect(chosenKind(await kindSelect())).toBe('전체');
  });

  test('S1B-AC-42 C-12 무결성 확인 맨 끝이 없어짐: 오류 띠 "마지막 항목 뒤가 맞지 않습니다. 가장 최근 항목이 지워졌습니다."', async () => {
    audit.verification = { valid: false, checkedCount: 57, reason: 'HEAD_MISMATCH', brokenEntry: null };
    const { user } = renderAt(URL_LOG);
    await entryRows(2);
    await user.click(await button('무결성 확인'));

    await alertWith('마지막 항목 뒤가 맞지 않습니다. 가장 최근 항목이 지워졌습니다.');
    expect(queryAction('이 항목 보기')).toBeNull();
    expect(pageText()).not.toContain('모두 맞습니다');
  });

  test.each([
    ['409: "이미 확인이 돌고 있습니다…"', () => rejected('AUDIT_VERIFICATION_RUNNING', 409), VERIFY_RUNNING],
    ['5xx: 공통 오류 띠에 프록시 시간 제한 한 줄', () => errors.internal(), VERIFY_PROXY],
    ['연결 끊김: 공통 오류 띠에 프록시 시간 제한 한 줄', () => HttpResponse.error(), VERIFY_PROXY],
  ])('S1B-AC-37 C-12 무결성 확인 %s. 버튼은 다시 눌린다', async (_, answer, wanted) => {
    on('post', AUDIT_VERIFY, answer);
    const { user } = renderAt(URL_LOG);
    await entryRows(2);
    await user.click(await button('무결성 확인'));

    await alertWith(wanted);
    expect(isBlocked(await button('무결성 확인'))).toBe(false);
    expect(pageText()).not.toContain('모두 맞습니다');
  });
});

describe('C-12 감사 로그: 늦게 온 답', () => {
  test('S1B-AC-38 C-12 필터가 바뀐 뒤 온 목록: 앞 필터의 답이 늦게 와도 지금 필터의 줄을 덮지 않는다', async () => {
    audit.entries = [SUSPENDED, SIGNED_IN];
    const release = gate();
    on('get', AUDIT, async ({ request }) => {
      if (!new URL(request.url).searchParams.getAll('action').includes('SIGN_IN_SUCCEEDED')) return undefined;
      await release.opened;
      return HttpResponse.json({ items: [SIGNED_IN], page: 1, size: 20, totalElements: 1, totalPages: 1 });
    });
    const { user } = renderAt(URL_LOG);
    await entryRows(2);
    const kind = await kindSelect();
    await user.selectOptions(kind, '로그인 기록');
    await eventually(() => {
      expect(callsTo('GET', AUDIT).some((c) => new URL(c.url).searchParams.getAll('action').includes('SIGN_IN_SUCCEEDED'))).toBe(true);
    });
    await user.selectOptions(kind, '계정 정지');
    await listedKinds(['ACCOUNT_SUSPENDED']);
    await entryRows(1);

    const done = answered('GET', AUDIT);
    release.open();
    await done;
    await settle();
    expect(column('일')).toEqual(['계정 정지']);
  });

  test('S1B-AC-38 C-12 떠난 뒤 온 목록 401: 주소를 /login으로 바꾸지 않는다', async () => {
    const release = gate();
    on('get', AUDIT, async () => {
      await release.opened;
      return errors.unauthenticated();
    });
    const { user } = renderAt(URL_LOG);
    await sent('GET', AUDIT);
    await user.click(await link('구성원'));
    await atPath('/members');

    const done = answered('GET', AUDIT);
    release.open();
    await done;
    await settle();
    expect(window.location.pathname).toBe('/members');
  });

  test('S1B-AC-38 C-12 떠난 뒤 온 무결성 확인: 결과 띠를 보이지 않고, 돌아오면 버튼이 눌리는 처음 상태다', async () => {
    const release = gate();
    on('post', AUDIT_VERIFY, async () => {
      await release.opened;
      return HttpResponse.json(VALID);
    });
    const { user } = renderAt(URL_LOG);
    await entryRows(2);
    await user.click(await button('무결성 확인'));
    await sent('POST', AUDIT_VERIFY);
    await user.click(await link('구성원'));
    await atPath('/members');

    const done = answered('POST', AUDIT_VERIFY);
    release.open();
    await done;
    await settle();
    expect(pageText()).not.toContain('모두 맞습니다');

    await user.click(await link('감사 로그'));
    await entryRows(2);
    expect(isBlocked(await button('무결성 확인'))).toBe(false);
    expect(pageText()).not.toContain('모두 맞습니다');
    expect(pageText()).not.toContain('확인하는 중…');
  });

  test.each(['떠난 뒤', '필터가 바뀐 뒤'] as const)('S1B-AC-38 C-12 %s 온 내보내기 HEAD 503: 띠를 보이지 않는다', async (when) => {
    const release = gate();
    on('head', AUDIT_EXPORT, async () => {
      await release.opened;
      return exportBusy();
    });
    const { user } = renderAt(URL_LOG);
    await entryRows(2);
    await user.click(await button('CSV로 내보내기'));
    await sent('HEAD', AUDIT_EXPORT);
    if (when === '떠난 뒤') {
      await user.click(await link('구성원'));
      await atPath('/members');
    } else {
      await user.selectOptions(await kindSelect(), '계정 정지');
      await listedKinds(['ACCOUNT_SUSPENDED']);
    }

    const done = answered('HEAD', AUDIT_EXPORT);
    release.open();
    await done;
    await settle();
    expect(pageText()).not.toContain(EXPORT_BUSY);
    expect(callsTo('GET', AUDIT_EXPORT)).toHaveLength(0);
  });
});
