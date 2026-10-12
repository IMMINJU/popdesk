// 4b screens.md C-18 시스템 상태: 샌드박스 띠(1절), "다시 보내기"(2절), 시험 메일(3절), 정리 작업 표(4절),
// 떠난 뒤 온 답(S4B-AC-32~36). 채널 줄과 꺼진 것의 조건은 6절의 1~5.
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  alertWith,
  atPath,
  callsTo,
  channel,
  errors,
  eventually,
  expectNoAxeViolations,
  failedDelivery,
  found,
  gate,
  heading1,
  hrefPath,
  isBlocked,
  on,
  pageText,
  renderAt,
  screen,
  sent,
  setSmtp,
  state,
  text,
  within,
} from '../__tests__/console';
import { rejected, settle } from '../__tests__/console1a';
import { said } from '../__tests__/console2';
import { liveText, noContent } from '../__tests__/console4';
import {
  FAILED,
  RESEND,
  RESEND_NAME,
  STATUS,
  TEST_MAIL,
  TEST_MAIL_NAME,
  before,
  channelRow,
  cleanupJob,
  failedRow,
  failedRowNow,
  failedTable,
  mailResult,
  resendButton,
  resendOf,
  s4b,
  sandboxed,
  section,
  sectionNow,
  status,
  under,
} from '../__tests__/console4b';

const URL_SYSTEM = '/system';
const SENDING = '보내는 중…';
const SERVER_DOWN = '서버에 연결하지 못했습니다. 잠시 뒤 다시 하세요.';
const NO_RIGHT = '이 작업을 할 권한이 없습니다.';

const SANDBOX_TO_ONE = '샌드박스로 떠 있습니다. 메일은 설정한 한 주소로만 가고, 웹훅과 감사 바깥 사본은 보내지 않습니다.';
const SANDBOX_ALL_OFF = '샌드박스로 떠 있습니다. 메일, 웹훅, 감사 바깥 사본을 보내지 않습니다.';
const OFF_BY_SANDBOX = '꺼짐(샌드박스)';
const SANDBOX_GUIDE = '샌드박스 안내';

const RESENT = '다시 보냈습니다. 결과는 잠시 뒤 이 화면에 반영됩니다.';
const CHANNEL_OFF_BAND = '채널이 꺼져 있어 보낼 수 없습니다.';
const CHANNEL_OFF_ROW = '채널이 꺼져 있습니다';
const OVER_LIMIT = '한 주소에 한 시간 50통을 넘겼습니다.';

const MAIL_TITLE = '시험 메일';
const MAIL_TO_ME = '지금 로그인한 계정의 주소로 메일 한 통을 보냅니다.';
const MAIL_TO_SANDBOX = '샌드박스 주소로 메일 한 통을 보냅니다.';
const MAIL_OFF = '메일 보내기가 꺼져 있습니다.';
const MAIL_SENT = '보냈습니다. 메일함을 확인하세요.';
const MAIL_NOT_SENT = '보내지 못했습니다.';

const JOBS_TITLE = '정리 작업';
const NEVER = '아직 없음';

/** Two `failed` rows on channels that are on. A row is found by its error kind. */
const MAIL_ROW = failedDelivery({ id: 1, channel: 'MAIL', lastErrorKind: 'CONNECTION_REFUSED', recipientAccountId: 11 });
const HOOK_ROW = failedDelivery({ id: 2, channel: 'WEBHOOK', lastErrorKind: 'HTTP_500', recipientAccountId: null });

function twoFailed() {
  state.status = status({ mail: channel({ failed: 1 }), webhook: channel({ failed: 1 }) });
  state.failed = [MAIL_ROW, HOOK_ROW];
}

/** The innermost element of `main` that holds `words`, or undefined. */
function holding(words: string): Element | undefined {
  const root = screen.queryByRole('main');
  if (!root) return undefined;
  return Array.from(root.querySelectorAll('*'))
    .filter((el) => text(el).includes(words))
    .at(-1);
}

/** The elements a button's `aria-describedby` names. */
function describers(button: Element): (HTMLElement | null)[] {
  return (button.getAttribute('aria-describedby') ?? '')
    .split(/\s+/)
    .filter(Boolean)
    .map((id) => document.getElementById(id));
}

const mailSection = () => sectionNow(MAIL_TITLE);

/** The one button of the 시험 메일 part, whatever it says now. */
function mailButtonNow(): HTMLElement | null {
  const root = mailSection();
  return root && within(root).queryByRole('button', { name: new RegExp(`^(${TEST_MAIL_NAME}|${SENDING})$`) });
}

function mailButton(): Promise<HTMLElement> {
  return found(() => {
    const root = mailSection();
    return root && within(root).queryByRole('button', { name: TEST_MAIL_NAME });
  }, `버튼 "${TEST_MAIL_NAME}"`);
}

/** The result line of the 시험 메일 part: a `status` or an `alert` inside it that holds `wanted`. */
function mailResultLine(role: 'status' | 'alert', wanted: string): Promise<HTMLElement> {
  return found(() => {
    const root = mailSection();
    return root && within(root).queryAllByRole(role).find((el) => text(el).includes(wanted));
  }, `시험 메일 결과 줄(role="${role}"): ${wanted}`);
}

/** The place that shows what the server answered: an element of the 시험 메일 part whose text is `reply`, character for character. */
function replyPlace(reply: string): Promise<Element> {
  return found(() => Array.from(mailSection()?.querySelectorAll('*') ?? []).find((el) => el.textContent === reply), `서버가 한 답의 글 자리: ${reply}`);
}

describe('C-18 시스템 상태 (4b): 샌드박스 띠', () => {
  test.each([
    { what: '메일 주소 있음', mailToSet: true, says: SANDBOX_TO_ONE, other: SANDBOX_ALL_OFF },
    { what: '메일 주소 없음', mailToSet: false, says: SANDBOX_ALL_OFF, other: SANDBOX_TO_ONE },
  ])('S4B-AC-32 C-18 샌드박스 띠($what): 제목 아래, 채널 표 위에 "$says"', async ({ mailToSet, says, other }) => {
    state.status = sandboxed(mailToSet);
    renderAt(URL_SYSTEM);

    const table = (await channelRow('웹훅')).closest('table') as HTMLElement;
    const band = await found(() => holding(says), `샌드박스 띠: ${says}`);
    expect(before(await heading1(), band), '화면 제목 아래').toBe(true);
    expect(before(band, table), '채널 표 위').toBe(true);
    expect(pageText()).not.toContain(other);
  });

  test('S4B-AC-32 C-18 샌드박스가 아님: 4b의 화면(시험 메일 구역)이 그려지고 샌드박스 띠는 없다', async () => {
    renderAt(URL_SYSTEM);

    await section(MAIL_TITLE);
    await channelRow('웹훅');
    expect(pageText()).not.toContain('샌드박스로 떠 있습니다');
    expect(pageText()).not.toContain(OFF_BY_SANDBOX);
  });

  test.each([
    { what: '주소 없음: 세 채널 모두', mailToSet: false, off: ['메일 보내기', '웹훅', '감사 바깥 사본'] },
    { what: '주소 있음: 웹훅과 감사 바깥 사본', mailToSet: true, off: ['웹훅', '감사 바깥 사본'] },
  ])('S4B-AC-32 C-18 샌드박스가 끈 채널 줄($what): 상태 칸이 "꺼짐(샌드박스)"이고 링크는 "샌드박스 안내" 하나다. 설정 방법 링크는 없다', async ({ mailToSet, off }) => {
    state.status = sandboxed(mailToSet);
    renderAt(URL_SYSTEM);

    for (const name of off) {
      const row = await channelRow(name);
      await eventually(() => {
        expect(under(row, '상태'), `${name}의 상태 칸`).toContain(OFF_BY_SANDBOX);
      });
      expect(under(row, '상태')).not.toContain('설정 없음');
      const links = within(row).queryAllByRole<HTMLAnchorElement>('link');
      expect(links.map((a) => text(a)), `${name} 줄의 링크`).toEqual([SANDBOX_GUIDE]);
      // The operating docs, at the part about the sandbox.
      expect(links[0]?.getAttribute('href') ?? '').toContain('/docs/operate/');
      expect(new URL(links[0]?.href ?? 'http://x/').hash, '운영 문서의 샌드박스 절').not.toBe('');
    }
    if (mailToSet) {
      const mail = await channelRow('메일 보내기');
      expect(under(mail, '상태')).toBe('켜짐');
      expect(within(mail).queryAllByRole('link'), '켜진 채널의 링크').toHaveLength(0);
    }
  });

  test('S4B-AC-32 C-18 샌드박스이고 주소가 있는데 메일 설정이 없음: 메일 줄은 "설정 없음"과 설정 방법 링크 그대로이고, 웹훅 줄은 "꺼짐(샌드박스)"', async () => {
    state.status = sandboxed(true, { mail: channel({ enabled: false, lastSuccessAt: null }) });
    renderAt(URL_SYSTEM);

    const hook = await channelRow('웹훅');
    await eventually(() => {
      expect(under(hook, '상태')).toContain(OFF_BY_SANDBOX);
    });
    const mail = await channelRow('메일 보내기');
    expect(under(mail, '상태')).toContain('설정 없음');
    expect(under(mail, '상태')).not.toContain('샌드박스');
    const links = within(mail).queryAllByRole('link').map((a) => text(a));
    expect(links.length, '메일 설정 방법 링크').toBeGreaterThan(0);
    expect(links).not.toContain(SANDBOX_GUIDE);
  });
});

describe('C-18 시스템 상태 (4b): 다시 보내기', () => {
  test('S4B-AC-33 C-18 `failed` 표의 마지막 열: 열 머리 "동작", 줄마다 버튼 "다시 보내기"가 aria-describedby로 그 줄의 채널과 만든 시각에 이어진다', async () => {
    twoFailed();
    renderAt(URL_SYSTEM);

    const table = await failedTable();
    await resendButton('CONNECTION_REFUSED');
    expect(within(table).getAllByRole('columnheader').map((h) => text(h)).at(-1)).toBe('동작');
    const ids: string[] = [];
    for (const [part, word] of [
      ['CONNECTION_REFUSED', '메일'],
      ['HTTP_500', '웹훅'],
    ] as const) {
      const row = await failedRow(part);
      const buttons = within(row).queryAllByRole('button');
      expect(buttons.map((b) => text(b)), `${part} 줄의 버튼`).toEqual([RESEND_NAME]);
      const button = buttons[0] as HTMLElement;
      expect(row.lastElementChild?.contains(button), '마지막 칸에 있다').toBe(true);
      const described = describers(button);
      expect(described.length, 'aria-describedby').toBeGreaterThan(0);
      expect(described.every((el) => el !== null && row.contains(el)), '그 줄 안의 글에 이어진다').toBe(true);
      expect(described.map((el) => text(el)).join(' '), '그 줄의 채널').toContain(word);
      const time = row.querySelector('time');
      expect(time !== null && described.some((el) => el?.contains(time)), '그 줄의 만든 시각').toBe(true);
      ids.push(button.getAttribute('aria-describedby') ?? '');
    }
    expect(ids[0], '줄마다 다른 글에 이어진다').not.toBe(ids[1]);
  });

  test('S4B-AC-33 C-18 다시 보내기 성공(204): 본문 없이 그 줄의 ID로 보내고, 목록과 시스템 상태를 다시 받으며 토스트로 알린다', async () => {
    twoFailed();
    const { user } = renderAt(URL_SYSTEM);
    const button = await resendButton('CONNECTION_REFUSED');
    await settle();
    const listed = callsTo('GET', FAILED).length;
    const asked = callsTo('GET', STATUS).length;

    await user.click(button);

    const [post] = await sent('POST', resendOf(MAIL_ROW.id));
    expect(await post?.body, '본문 없음').toBeUndefined();
    await said(RESENT);
    await eventually(() => {
      expect(callsTo('GET', FAILED).length, '목록을 다시 받음').toBeGreaterThan(listed);
      expect(callsTo('GET', STATUS).length, '시스템 상태를 다시 받음').toBeGreaterThan(asked);
      expect(failedRowNow('CONNECTION_REFUSED'), '나간 줄').toBeUndefined();
    });
    // The channel table shows what the second answer says: the row waits to be sent.
    const mail = await channelRow('메일 보내기');
    await eventually(() => {
      expect(under(mail, '보낼 차례')).toBe('1');
      expect(under(mail, '실패 수')).toBe('0');
    });
    await failedRow('HTTP_500');
    expect(callsTo('POST', /\/resend$/)).toHaveLength(1);
  });

  test('S4B-AC-33 C-18 다시 보내는 중: 그 줄의 버튼만 막히고 "보내는 중…"이다. 다른 줄은 눌린다', async () => {
    twoFailed();
    const release = gate();
    on('post', RESEND, async ({ params }) => {
      if (params.id === String(MAIL_ROW.id)) await release.opened;
      return noContent();
    });
    const { user } = renderAt(URL_SYSTEM);

    await user.click(await resendButton('CONNECTION_REFUSED'));
    await sent('POST', resendOf(MAIL_ROW.id));
    await eventually(() => {
      const row = failedRowNow('CONNECTION_REFUSED');
      const pending = row ? within(row).queryAllByRole('button') : [];
      expect(pending).toHaveLength(1);
      expect(pending[0] !== undefined && isBlocked(pending[0]), '그 줄의 버튼 막힘').toBe(true);
      expect(text(pending[0])).toBe(SENDING);
    });
    const other = await resendButton('HTTP_500');
    expect(isBlocked(other), '다른 줄의 버튼').toBe(false);
    await user.click(other);

    await sent('POST', resendOf(HOOK_ROW.id));
    expect(callsTo('POST', resendOf(MAIL_ROW.id)), '막힌 버튼은 다시 보내지 않는다').toHaveLength(1);
    release.open();
    await resendButton('CONNECTION_REFUSED');
  });

  test('S4B-AC-33 C-18 다시 보내기 400 CHANNEL_DISABLED: 오류 띠 "채널이 꺼져 있어 보낼 수 없습니다."와 함께 시스템 상태를 다시 받는다', async () => {
    twoFailed();
    on('post', RESEND, () => {
      // The channel went off after the screen was drawn.
      state.status = { ...state.status, webhook: channel({ enabled: false, lastSuccessAt: null, failed: 1 }) };
      return rejected('CHANNEL_DISABLED');
    });
    const { user } = renderAt(URL_SYSTEM);
    const button = await resendButton('HTTP_500');
    await settle();
    const asked = callsTo('GET', STATUS).length;

    await user.click(button);

    await alertWith(CHANNEL_OFF_BAND);
    await eventually(() => {
      expect(callsTo('GET', STATUS).length, '시스템 상태를 다시 받음').toBeGreaterThan(asked);
    });
    const hook = await channelRow('웹훅');
    await eventually(() => {
      expect(under(hook, '상태')).toContain('설정 없음');
    });
    expect(failedRowNow('HTTP_500') ?? null, '줄은 그대로 있다').not.toBeNull();
  });

  test('S4B-AC-33 C-18 다시 보내기 404: 토스트도 오류 띠도 없이 목록을 다시 받는다', async () => {
    twoFailed();
    on('post', RESEND, () => {
      // Sent already, or cleaned up.
      state.failed = [HOOK_ROW];
      return errors.notFound();
    });
    const { user } = renderAt(URL_SYSTEM);
    const button = await resendButton('CONNECTION_REFUSED');
    await settle();
    const listed = callsTo('GET', FAILED).length;
    const quiet = liveText();

    await user.click(button);

    await sent('POST', resendOf(MAIL_ROW.id));
    await eventually(() => {
      expect(callsTo('GET', FAILED).length, '목록을 다시 받음').toBeGreaterThan(listed);
      expect(failedRowNow('CONNECTION_REFUSED'), '없어진 줄').toBeUndefined();
    });
    await failedRow('HTTP_500');
    await settle();
    expect(screen.queryAllByRole('alert')).toHaveLength(0);
    expect(liveText(), '토스트(status)로도 알리지 않는다').toEqual(quiet);
  });

  test.each([
    { what: '403', answer: () => errors.forbidden(), says: NO_RIGHT },
    { what: '5xx', answer: () => errors.internal(), says: SERVER_DOWN },
  ])('S4B-AC-33 C-18 다시 보내기 $what: 공통 오류 띠 "$says". 버튼은 다시 눌린다', async ({ answer, says }) => {
    twoFailed();
    on('post', RESEND, answer);
    const { user } = renderAt(URL_SYSTEM);

    await user.click(await resendButton('CONNECTION_REFUSED'));

    await alertWith(says);
    const again = await resendButton('CONNECTION_REFUSED');
    await eventually(() => {
      expect(isBlocked(again), '"다시 보내기"를 다시 누를 수 있다').toBe(false);
    });
    await user.click(again);
    await sent('POST', resendOf(MAIL_ROW.id), 2);
  });

  test('S4B-AC-33 C-18 꺼진 채널의 줄: 버튼 대신 "채널이 꺼져 있습니다". 켜진 채널의 줄에는 버튼이 있다', async () => {
    state.status = status({ mail: channel({ failed: 1 }), webhook: channel({ enabled: false, lastSuccessAt: null, failed: 1 }) });
    state.failed = [MAIL_ROW, HOOK_ROW];
    renderAt(URL_SYSTEM);

    await resendButton('CONNECTION_REFUSED');
    const off = await failedRow('HTTP_500');
    expect(text(off)).toContain(CHANNEL_OFF_ROW);
    expect(within(off).queryAllByRole('button'), '꺼진 채널 줄의 버튼').toHaveLength(0);
    expect(text(await failedRow('CONNECTION_REFUSED'))).not.toContain(CHANNEL_OFF_ROW);
  });

  test('S4B-AC-33 C-18 RATE_LIMITED 줄: 오류 종류는 code 그대로이고 그 아래 "한 주소에 한 시간 50통을 넘겼습니다."', async () => {
    state.status = status({ mail: channel({ failed: 2 }) });
    state.failed = [failedDelivery({ id: 3, lastErrorKind: 'RATE_LIMITED', recipientAccountId: 12 }), MAIL_ROW];
    renderAt(URL_SYSTEM);

    const limited = await failedRow('RATE_LIMITED');
    await eventually(() => {
      expect(text(limited)).toContain(OVER_LIMIT);
    });
    const kind = limited.querySelector('code');
    expect(text(kind)).toBe('RATE_LIMITED');
    expect(kind?.closest('td')?.textContent ?? '', '오류 종류 칸 안에 덧붙인 줄').toContain(OVER_LIMIT);
    expect(text(await failedRow('CONNECTION_REFUSED'))).not.toContain(OVER_LIMIT);
  });
});

describe('C-18 시스템 상태 (4b): 시험 메일', () => {
  test.each([
    { what: '보통', given: () => status(), says: MAIL_TO_ME, other: MAIL_TO_SANDBOX },
    { what: '샌드박스(주소 있음)', given: () => sandboxed(true), says: MAIL_TO_SANDBOX, other: MAIL_TO_ME },
  ])('S4B-AC-34 C-18 시험 메일 구역($what): 채널 표 아래 h2 "시험 메일", 설명 "$says", 버튼 "시험 메일 보내기"', async ({ given, says, other }) => {
    state.status = given();
    renderAt(URL_SYSTEM);

    const part = await section(MAIL_TITLE);
    const table = (await channelRow('메일 보내기')).closest('table') as HTMLElement;
    expect(before(table, part), '채널 표 아래').toBe(true);
    expect(text(part)).toContain(says);
    expect(text(part)).not.toContain(other);
    expect(isBlocked(await mailButton())).toBe(false);
    expect(within(part).queryAllByRole('button'), '구역의 버튼은 하나').toHaveLength(1);
    expect(within(part).queryAllByRole('status').concat(within(part).queryAllByRole('alert')).filter((el) => text(el) !== ''), '처음에는 결과가 없다').toHaveLength(0);
  });

  test('S4B-AC-34 C-18 시험 메일 보내는 중: 본문 없이 보내고, 버튼이 막히며 "보내는 중…"과 30초까지 걸릴 수 있다는 안내가 보인다', async () => {
    const release = gate();
    on('post', TEST_MAIL, async () => {
      await release.opened;
      return HttpResponse.json(mailResult());
    });
    const { user } = renderAt(URL_SYSTEM);

    await user.click(await mailButton());

    const [post] = await sent('POST', TEST_MAIL);
    expect(await post?.body, '본문 없음').toBeUndefined();
    await eventually(() => {
      const pending = mailButtonNow();
      expect(pending !== null && isBlocked(pending), '보내는 중 버튼 막힘').toBe(true);
      expect(text(pending)).toBe(SENDING);
      expect(text(mailSection())).toMatch(/30초/);
    });
    expect(text(mailSection())).not.toContain(MAIL_SENT);
    release.open();

    await mailResultLine('status', MAIL_SENT);
    await mailButton();
    expect(callsTo('POST', TEST_MAIL)).toHaveLength(1);
  });

  test('S4B-AC-34 C-18 시험 메일 됐다(서버의 답 있음): 구역 안 role="status"에 "보냈습니다. 메일함을 확인하세요."와 서버가 한 답의 글', async () => {
    s4b.testMail = mailResult({ serverReply: '250 2.0.0 Ok: queued as 4B7XQ1' });
    const { user } = renderAt(URL_SYSTEM);

    await user.click(await mailButton());

    await mailResultLine('status', MAIL_SENT);
    await replyPlace('250 2.0.0 Ok: queued as 4B7XQ1');
    expect(within(mailSection() as HTMLElement).queryAllByRole('alert'), '실패의 결과 줄').toHaveLength(0);
  });

  test('S4B-AC-34 C-18 시험 메일 됐다(serverReply가 null): 결과 줄은 그 문장뿐이고 답의 글 자리를 그리지 않는다', async () => {
    const { user } = renderAt(URL_SYSTEM);

    await user.click(await mailButton());

    const line = await mailResultLine('status', MAIL_SENT);
    expect(text(line)).toBe(MAIL_SENT);
    expect(mailSection()?.querySelector('pre') ?? null, '답의 글 자리').toBeNull();
  });

  test.each([
    { what: '서버가 거절', kind: 'SMTP_AUTHENTICATION_FAILED', reply: '535 5.7.8 Authentication credentials invalid' },
    { what: '서버에 닿지 못함(serverReply가 null)', kind: 'CONNECTION_FAILED', reply: null },
  ])('S4B-AC-34 C-18 시험 메일 실패($what): 구역 안 role="alert"에 "보내지 못했습니다."와 오류의 종류(code), 서버가 한 답은 있을 때만', async ({ kind, reply }) => {
    s4b.testMail = mailResult({ ok: false, errorKind: kind, serverReply: reply });
    const { user } = renderAt(URL_SYSTEM);

    await user.click(await mailButton());

    const line = await mailResultLine('alert', MAIL_NOT_SENT);
    const part = mailSection() as HTMLElement;
    expect(Array.from(part.querySelectorAll('code')).map((c) => text(c)), '오류의 종류').toContain(kind);
    expect(text(part)).not.toContain(MAIL_SENT);
    if (reply === null) {
      expect(part.querySelector('pre') ?? null, '답의 글 자리').toBeNull();
      expect(text(line)).toContain(kind);
    } else {
      await replyPlace(reply);
    }
    // The button can be pressed again.
    expect(isBlocked(await mailButton())).toBe(false);
  });

  test('S4B-AC-34 C-18 서버가 한 답은 글자 그대로: <b>와 http://… 가 든 여러 줄의 답이 굵은 글씨나 링크가 되지 않는다', async () => {
    const reply = '550-5.7.1 <b>relay denied</b>\n550 5.7.1 see http://help.invalid/5.7.1 [link](http://help.invalid)';
    s4b.testMail = mailResult({ ok: false, errorKind: 'SMTP_REFUSED', serverReply: reply });
    const { user } = renderAt(URL_SYSTEM);

    await user.click(await mailButton());

    await mailResultLine('alert', MAIL_NOT_SENT);
    const place = await replyPlace(reply);
    const part = mailSection() as HTMLElement;
    expect(place.children, '답의 글 자리 안의 요소').toHaveLength(0);
    expect(part.querySelectorAll('b, strong'), '굵은 글씨').toHaveLength(0);
    expect(part.querySelectorAll('a'), '링크').toHaveLength(0);
    expect(within(part).queryAllByRole('link')).toHaveLength(0);
  });

  test.each([
    { what: 'SMTP 설정 없음', given: () => status({ mail: channel({ enabled: false, lastSuccessAt: null }) }) },
    { what: '주소 없는 샌드박스', given: () => sandboxed(false) },
  ])('S4B-AC-34 C-18 메일이 꺼져 있음($what): 구역은 있고 버튼 대신 "메일 보내기가 꺼져 있습니다."', async ({ given }) => {
    state.status = given();
    renderAt(URL_SYSTEM);

    const part = await section(MAIL_TITLE);
    await eventually(() => {
      expect(text(part)).toContain(MAIL_OFF);
    });
    expect(within(part).queryAllByRole('button'), '버튼').toHaveLength(0);
    expect(within(part).queryAllByRole('alert'), '오류 띠가 아니라 보통의 글').toHaveLength(0);
  });

  test('S4B-AC-34 C-18 시험 메일 400 CHANNEL_DISABLED: 오류 띠 "메일 보내기가 꺼져 있습니다."와 함께 시스템 상태를 다시 받는다', async () => {
    on('post', TEST_MAIL, () => {
      // The mail channel went off after the screen was drawn.
      setSmtp(false);
      return rejected('CHANNEL_DISABLED');
    });
    const { user } = renderAt(URL_SYSTEM);
    const button = await mailButton();
    await settle();
    const asked = callsTo('GET', STATUS).length;

    await user.click(button);

    await alertWith(MAIL_OFF);
    await eventually(() => {
      expect(callsTo('GET', STATUS).length, '시스템 상태를 다시 받음').toBeGreaterThan(asked);
    });
    const mail = await channelRow('메일 보내기');
    await eventually(() => {
      expect(under(mail, '상태')).toContain('설정 없음');
      expect(mailButtonNow(), '꺼진 뒤에는 버튼이 없다').toBeNull();
    });
  });

  test.each([
    { what: '429', answer: () => errors.rateLimited(30), says: /요청이 많습니다\. \d+초 뒤 다시 하세요\./ },
    { what: '5xx', answer: () => errors.internal(), says: SERVER_DOWN },
    { what: '연결 끊김', answer: () => HttpResponse.error(), says: SERVER_DOWN },
  ])('S4B-AC-34 C-18 시험 메일 $what: 공통 오류 띠가 보이고 결과 줄은 없다', async ({ answer, says }) => {
    on('post', TEST_MAIL, answer);
    const { user } = renderAt(URL_SYSTEM);

    await user.click(await mailButton());

    await alertWith(says);
    expect(text(mailSection())).not.toContain(MAIL_SENT);
    expect(text(mailSection())).not.toContain(MAIL_NOT_SENT);
    await eventually(() => {
      expect(text(mailButtonNow())).toBe(TEST_MAIL_NAME);
    });
  });

  test('S4B-AC-34 C-18 시험 메일 다시 누름: 앞 결과를 지우고 새로 보낸다', async () => {
    const refused = '554 5.7.1 Message rejected by policy';
    const release = gate();
    let asked = 0;
    on('post', TEST_MAIL, async () => {
      asked += 1;
      if (asked === 1) return HttpResponse.json(mailResult({ ok: false, errorKind: 'SMTP_REFUSED', serverReply: refused }));
      await release.opened;
      return HttpResponse.json(mailResult());
    });
    const { user } = renderAt(URL_SYSTEM);
    await user.click(await mailButton());
    await mailResultLine('alert', MAIL_NOT_SENT);
    await replyPlace(refused);

    await user.click(await mailButton());

    await sent('POST', TEST_MAIL, 2);
    await eventually(() => {
      expect(text(mailSection()), '앞 결과').not.toContain(MAIL_NOT_SENT);
      expect(text(mailSection())).not.toContain(refused);
      expect(text(mailSection())).not.toContain('SMTP_REFUSED');
    });
    release.open();
    const line = await mailResultLine('status', MAIL_SENT);
    expect(text(line)).toBe(MAIL_SENT);
    expect(text(mailSection())).not.toContain(refused);
  });
});

describe('C-18 시스템 상태 (4b): 정리 작업', () => {
  test('S4B-AC-35 C-18 정리 작업 표: `failed` 표 아래 h2 "정리 작업", 열 머리 "작업", "마지막으로 돈 시각", "지운 수", 줄은 받은 순서(이름순) 그대로', async () => {
    state.status = status({
      mail: channel({ failed: 1 }),
      cleanupJobs: [
        cleanupJob({ name: 'attachment-cleanup', lastRunAt: '2026-10-08T01:00:00Z', deleted: 37 }),
        cleanupJob({ name: 'notification-cleanup', lastRunAt: '2026-10-07T18:30:00Z', deleted: 0 }),
        cleanupJob({ name: 'session-cleanup', lastRunAt: '2026-10-08T03:00:00Z', deleted: 5 }),
      ],
    });
    state.failed = [MAIL_ROW];
    renderAt(URL_SYSTEM);

    const part = await section(JOBS_TITLE);
    const table = await found(() => within(part).queryByRole('table'), '정리 작업 표');
    expect(before(await failedTable(), part), '`failed` 표 아래').toBe(true);
    expect(within(table).getAllByRole('columnheader').map((h) => text(h))).toEqual(['작업', '마지막으로 돈 시각', '지운 수']);
    const rows = within(table).queryAllByRole('row').filter((r) => within(r).queryAllByRole('cell').length > 0);
    expect(rows.map((r) => under(r, '작업'))).toEqual(['attachment-cleanup', 'notification-cleanup', 'session-cleanup']);
    expect(rows.map((r) => text(r.querySelector('code'))), '작업 이름은 code').toEqual(['attachment-cleanup', 'notification-cleanup', 'session-cleanup']);
    expect(rows.map((r) => new Date(r.querySelector('time')?.getAttribute('datetime') ?? '').getTime())).toEqual(
      state.status.cleanupJobs.map((j) => new Date(j.lastRunAt ?? '').getTime()),
    );
    expect(rows.map((r) => under(r, '지운 수'))).toEqual(['37', '0', '5']);
    expect(text(part)).not.toContain(NEVER);
  });

  test('S4B-AC-35 C-18 한 번도 돌지 않은 정리 작업: 시각 칸과 지운 수 칸이 "아직 없음"', async () => {
    state.status = status({
      cleanupJobs: [cleanupJob({ name: 'outbox-cleanup', lastRunAt: '2026-10-08T01:00:00Z', deleted: 0 }), cleanupJob({ name: 'session-cleanup', lastRunAt: null, deleted: null })],
    });
    renderAt(URL_SYSTEM);

    const part = await section(JOBS_TITLE);
    const never = await found(() => within(part).queryAllByRole('row').find((r) => text(r).includes('session-cleanup')), '줄 session-cleanup');
    expect(under(never, '마지막으로 돈 시각')).toBe(NEVER);
    expect(under(never, '지운 수')).toBe(NEVER);
    expect(never.querySelector('time'), '돌지 않은 작업의 시각').toBeNull();
    const ran = within(part).getAllByRole('row').find((r) => text(r).includes('outbox-cleanup')) as HTMLElement;
    expect(under(ran, '지운 수'), '돌았고 지운 것이 없는 작업').toBe('0');
    expect(text(ran)).not.toContain(NEVER);
  });

  test('S4B-AC-35 C-18 정리 작업이 하나도 없음: 4b의 화면(시험 메일 구역)은 그려지고 "정리 작업" 구역은 없다', async () => {
    state.status = status({ cleanupJobs: [] });
    renderAt(URL_SYSTEM);

    await section(MAIL_TITLE);
    await channelRow('메일 보내기');
    await settle();
    expect(sectionNow(JOBS_TITLE)).toBeNull();
    expect(pageText()).not.toContain(JOBS_TITLE);
  });
});

describe('C-18 시스템 상태 (4b): 떠난 뒤 온 답과 접근성', () => {
  test.each([
    { what: '다시 보내기의 204', path: RESEND, press: () => resendButton('CONNECTION_REFUSED'), answer: () => noContent() },
    { what: '다시 보내기의 5xx', path: RESEND, press: () => resendButton('CONNECTION_REFUSED'), answer: () => errors.internal() },
    {
      what: '시험 메일의 실패 결과',
      path: TEST_MAIL,
      press: mailButton,
      answer: () => HttpResponse.json(mailResult({ ok: false, errorKind: 'SMTP_REFUSED', serverReply: '554 5.7.1 Message rejected by policy' })),
    },
    { what: '시험 메일의 5xx', path: TEST_MAIL, press: mailButton, answer: () => errors.internal() },
  ])('S4B-AC-36 C-18 떠난 뒤 온 답($what): 다른 화면으로 간 뒤에 온 답은 토스트도 오류 띠도 띄우지 않는다', async ({ path, press, answer }) => {
    twoFailed();
    const release = gate();
    let answered = false;
    on('post', path, async () => {
      await release.opened;
      answered = true;
      return answer();
    });
    const { user } = renderAt(URL_SYSTEM);
    await user.click(await press());
    await eventually(() => {
      expect(callsTo('POST', /\/(resend|test-mail)$/), '보낸 요청').toHaveLength(1);
    });

    await user.click(await found(() => screen.queryAllByRole('link').find((a) => hrefPath(a) === '/issues'), '문의 목록으로 가는 링크'));
    await atPath('/issues');
    await eventually(() => {
      expect(text(screen.queryByRole('heading', { level: 1 })), '다른 화면의 제목').not.toMatch(/^(|시스템 상태)$/);
    });
    await settle();
    const quiet = liveText();

    release.open();
    await eventually(() => {
      expect(answered).toBe(true);
    });
    await settle();
    expect(screen.queryAllByRole('alert')).toHaveLength(0);
    expect(liveText(), '토스트(status)로도 알리지 않는다').toEqual(quiet);
    expect(window.location.pathname).toBe('/issues');
  });

  test('C-18 axe (4b): 샌드박스 띠, 버튼이 있는 `failed` 표, 시험 메일 결과, 정리 작업 표가 다 있는 화면에 접근성 위반이 없다', async () => {
    state.status = sandboxed(true, {
      mail: channel({ failed: 2 }),
      cleanupJobs: [cleanupJob({ name: 'attachment-cleanup', deleted: 37 }), cleanupJob({ name: 'session-cleanup', lastRunAt: null, deleted: null })],
    });
    state.failed = [failedDelivery({ id: 3, lastErrorKind: 'RATE_LIMITED', recipientAccountId: 12 }), MAIL_ROW, HOOK_ROW];
    s4b.testMail = mailResult({ serverReply: '250 2.0.0 Ok: queued as 4B7XQ1' });
    const { user } = renderAt(URL_SYSTEM);
    await found(() => holding(SANDBOX_TO_ONE), '샌드박스 띠');
    await resendButton('RATE_LIMITED');
    expect(text(await failedRow('HTTP_500'))).toContain(CHANNEL_OFF_ROW);
    await section(JOBS_TITLE);
    await user.click(await mailButton());
    await mailResultLine('status', MAIL_SENT);
    await replyPlace('250 2.0.0 Ok: queued as 4B7XQ1');

    await expectNoAxeViolations();
  });
});
