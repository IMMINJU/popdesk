// 12 묶음 screens.md "C-18 시스템 상태: 메일 받기": 구역 "메일 받기"(S12-AC-38). 꺼짐은 까닭마다("설정 없음"과 설정 방법 링크,
// "꺼짐(샌드박스)"), 켜짐은 "켜짐", 마지막 성공(시각 또는 "아직 없음"), 실패한 회차 수, 마지막 실패의 종류 문장과 시각, 다시 시도할
// 메일 수와 격리한 메일 수(0이면 줄 없음), 아래 설명(켜짐일 때만). 수는 toLocaleString('ko'). 자리는 채널 구역 바로 뒤, OIDC 구역 앞.
import { waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, renderAt, screen, state, text, within } from '../__tests__/console';
import { before, sandboxed, section, sectionNow } from '../__tests__/console4b';
import {
  COUNT_NOTE,
  FAILURE_WORDS,
  HOW_TO,
  MAIL_IN_TITLE,
  NOT_SET,
  NO_SUCCESS,
  ON,
  QUARANTINE_NOTE,
  SANDBOX_OFF,
  mailInOn,
  mailInSandbox,
  quarantinedText,
  retryingText,
  roundFailuresText,
  timeAfter,
  timeOf,
  withMailIn,
  type MailInFailureKind,
} from '../__tests__/console12';

const URL_SYSTEM = '/system';
const LAST_SUCCESS = '2026-10-08T02:40:00Z';
const LAST_FAILURE = '2026-10-08T03:05:00Z';
const OIDC_TITLE = 'OIDC 로그인';
const CHANNELS = '보내기 채널';

/** The words of a part that is on; none of them shows while it is off. */
const ON_WORDS = [ON, '마지막 성공', NO_SUCCESS, '실패한 회차', '마지막 실패', '다시 시도할 메일', '격리한 메일', COUNT_NOTE];

/** The part "메일 받기", waited for long enough for the first screen of the file. */
async function mailInPart(): Promise<HTMLElement> {
  let part: HTMLElement | null = null;
  await waitFor(
    () => {
      part = sectionNow(MAIL_IN_TITLE);
      expect(part, `구역 "${MAIL_IN_TITLE}"(h2)`).not.toBeNull();
    },
    { timeout: 8000 },
  );
  return part as unknown as HTMLElement;
}

const h2Names = () => screen.queryAllByRole('heading', { level: 2 }).map(text);

describe('C-18 시스템 상태의 메일 받기 구역 (12 묶음)', () => {
  test('S12-AC-38 C-18 꺼짐(NOT_SET): 구역 "메일 받기"에 "설정 없음"과 링크 "메일 받기 설정 방법"만 있고, 켜짐·회차·메일 수 줄도 아래 설명도 없다', async () => {
    renderAt(URL_SYSTEM);

    const part = await mailInPart();
    const words = text(part);
    expect(words).toContain(NOT_SET);
    const links = within(part).queryAllByRole<HTMLAnchorElement>('link', { name: HOW_TO });
    expect(links, `링크 "${HOW_TO}"`).toHaveLength(1);
    expect(links[0]?.getAttribute('href') ?? '', '링크 주소').not.toBe('');
    expect(words).not.toContain(SANDBOX_OFF);
    for (const absent of ON_WORDS) expect(words, absent).not.toContain(absent);
    expect(part.querySelectorAll('time'), '시각').toHaveLength(0);
  });

  test('S12-AC-38 C-18 꺼짐(SANDBOX): 구역 "메일 받기"에 "꺼짐(샌드박스)"이 있고 "설정 없음"도, 켜짐·회차·메일 수 줄도, 아래 설명도 없다', async () => {
    state.status = withMailIn(mailInSandbox(), sandboxed(false));
    renderAt(URL_SYSTEM);

    const part = await mailInPart();
    const words = text(part);
    expect(words).toContain(SANDBOX_OFF);
    expect(words).not.toContain(NOT_SET);
    for (const absent of ON_WORDS) expect(words, absent).not.toContain(absent);
    expect(part.querySelectorAll('time'), '시각').toHaveLength(0);
  });

  test('S12-AC-38 C-18 켜짐, 마지막 성공이 있음: "켜짐", "마지막 성공:" 뒤의 <time>이 그 시각, "실패한 회차: 0회", 아래 설명', async () => {
    state.status = withMailIn(mailInOn({ lastSuccessAt: LAST_SUCCESS }));
    renderAt(URL_SYSTEM);

    const part = await mailInPart();
    const words = text(part);
    expect(words).toContain(ON);
    expect(words).toContain('마지막 성공:');
    expect(words).not.toContain(NO_SUCCESS);
    expect(timeOf(timeAfter(part, '마지막 성공')), '마지막 성공 <time>').toBe(new Date(LAST_SUCCESS).getTime());
    expect(words).toContain(roundFailuresText(0));
    expect(words).toContain(COUNT_NOTE);
    for (const absent of [NOT_SET, SANDBOX_OFF, HOW_TO]) expect(words, absent).not.toContain(absent);
  });

  test('S12-AC-38 C-18 켜짐, 마지막 성공이 없음: "마지막 성공: 아직 없음"이고 시각이 없다', async () => {
    state.status = withMailIn(mailInOn({ lastSuccessAt: null }));
    renderAt(URL_SYSTEM);

    const part = await mailInPart();
    expect(text(part)).toContain(`마지막 성공: ${NO_SUCCESS}`);
    expect(part.querySelectorAll('time'), '시각').toHaveLength(0);
  });

  test.each([
    [0, '0회', null],
    [12_345, '12,345회', { kind: 'CONNECT', at: LAST_FAILURE }],
  ] as const)('S12-AC-38 C-18 실패한 회차 %i: "실패한 회차: %s"(toLocaleString)', async (count, shown, lastFailure) => {
    // A count above 0 comes with its last failure (contract: "The last of them; null without one.").
    state.status = withMailIn(mailInOn({ lastSuccessAt: LAST_SUCCESS, roundFailures: count, lastFailure: lastFailure && { ...lastFailure } }));
    renderAt(URL_SYSTEM);

    const part = await mailInPart();
    expect(text(part)).toContain(`실패한 회차: ${shown}`);
  });

  test.each(Object.entries(FAILURE_WORDS) as [MailInFailureKind, string][])(
    'S12-AC-38 C-18 마지막 실패 %s: "마지막 실패: {종류 문장}, {시각}"이고 <time>이 그 시각이다',
    async (kind, words) => {
      state.status = withMailIn(mailInOn({ lastSuccessAt: LAST_SUCCESS, roundFailures: 2, lastFailure: { kind, at: LAST_FAILURE } }));
      renderAt(URL_SYSTEM);

      const part = await mailInPart();
      expect(text(part)).toContain(`마지막 실패: ${words},`);
      expect(text(part), '종류 이름 그대로').not.toContain(kind);
      expect(timeOf(timeAfter(part, '마지막 실패')), '마지막 실패 <time>').toBe(new Date(LAST_FAILURE).getTime());
      expect(timeOf(timeAfter(part, '마지막 성공')), '마지막 성공 <time>').toBe(new Date(LAST_SUCCESS).getTime());
    },
  );

  test('S12-AC-38 C-18 마지막 실패 UNSUPPORTED: "마지막 실패:" 줄과 그 시각이 있고 종류 이름이 그대로 나오지 않는다', async () => {
    // screens.md gives no sentence for this kind; the test holds only what it says of every failure (see the report).
    state.status = withMailIn(mailInOn({ lastSuccessAt: LAST_SUCCESS, roundFailures: 1, lastFailure: { kind: 'UNSUPPORTED', at: LAST_FAILURE } }));
    renderAt(URL_SYSTEM);

    const part = await mailInPart();
    expect(text(part)).toContain('마지막 실패: ');
    expect(text(part), '종류 이름 그대로').not.toContain('UNSUPPORTED');
    expect(timeOf(timeAfter(part, '마지막 실패')), '마지막 실패 <time>').toBe(new Date(LAST_FAILURE).getTime());
  });

  test('S12-AC-38 C-18 마지막 실패가 없음: "마지막 실패" 줄이 없다', async () => {
    state.status = withMailIn(mailInOn({ lastSuccessAt: LAST_SUCCESS, roundFailures: 0, lastFailure: null }));
    renderAt(URL_SYSTEM);

    const part = await mailInPart();
    expect(text(part)).toContain(roundFailuresText(0));
    expect(text(part)).not.toContain('마지막 실패');
  });

  test.each([
    [0, false],
    [1_234, true],
  ] as const)('S12-AC-38 C-18 다시 시도할 메일 %i: 0이면 줄이 없고, 있으면 "다시 시도할 메일: {수}통"', async (count, shown) => {
    state.status = withMailIn(mailInOn({ lastSuccessAt: LAST_SUCCESS, retrying: count }));
    renderAt(URL_SYSTEM);

    const part = await mailInPart();
    expect(text(part)).toContain(COUNT_NOTE);
    if (shown) expect(text(part)).toContain(retryingText(count));
    else expect(text(part)).not.toContain('다시 시도할 메일');
  });

  test.each([
    [0, false],
    [5_678, true],
  ] as const)('S12-AC-38 C-18 격리한 메일 %i: 0이면 줄도 설명도 없고, 있으면 "격리한 메일(90일): {수}통"과 격리 폴더 설명', async (count, shown) => {
    state.status = withMailIn(mailInOn({ lastSuccessAt: LAST_SUCCESS, quarantined90d: count }));
    renderAt(URL_SYSTEM);

    const part = await mailInPart();
    expect(text(part)).toContain(COUNT_NOTE);
    if (shown) {
      expect(text(part)).toContain(quarantinedText(count));
      expect(text(part)).toContain(QUARANTINE_NOTE);
    } else {
      expect(text(part)).not.toContain('격리한 메일');
      expect(text(part)).not.toContain(QUARANTINE_NOTE);
    }
  });

  test('S12-AC-38 C-18 폴더 이름과 메일 서버 주소가 없다: 모든 줄이 있는 구역에 기본 폴더 이름, 호스트, 포트, 메일 주소가 없다', async () => {
    state.status = withMailIn(
      mailInOn({ lastSuccessAt: LAST_SUCCESS, roundFailures: 3, lastFailure: { kind: 'AUTH', at: LAST_FAILURE }, retrying: 2, quarantined90d: 1 }),
    );
    renderAt(URL_SYSTEM);

    const part = await mailInPart();
    const words = text(part);
    expect(words).toContain(quarantinedText(1));
    expect(words, '받는 폴더의 기본 이름').not.toContain('INBOX');
    expect(words, '메일 주소').not.toContain('@');
    expect(words, '호스트').not.toMatch(/imaps?\.|imaps?:\/\//i);
    expect(words, '포트').not.toMatch(/:(993|143)\b/);
  });

  test.each([
    ['켜짐', () => mailInOn({ lastSuccessAt: LAST_SUCCESS })],
    ['꺼짐', () => state.status.mailIn],
  ])('S12-AC-38 C-18 자리(%s): 구역 "메일 받기"는 "보내기 채널" 바로 뒤, "OIDC 로그인" 앞이다', async (_, mailIn) => {
    state.status = withMailIn(mailIn());
    renderAt(URL_SYSTEM);

    const part = await mailInPart();
    const channels = await section(CHANNELS);
    const oidc = await section(OIDC_TITLE);
    expect(before(channels, part), '채널 구역 뒤').toBe(true);
    expect(before(part, oidc), 'OIDC 구역 앞').toBe(true);
    expect(channels.contains(part) || part.contains(oidc) || oidc.contains(part), '다른 구역 안이 아니다').toBe(false);
    const names = h2Names();
    expect(names[names.indexOf(CHANNELS) + 1], `"${CHANNELS}" 다음 구역`).toBe(MAIL_IN_TITLE);
    expect(names[names.indexOf(MAIL_IN_TITLE) + 1], `"${MAIL_IN_TITLE}" 다음 구역`).toBe(OIDC_TITLE);
  });

  test('S12-AC-38 C-18 axe: 켜짐, 마지막 성공과 실패, 다시 시도할 메일과 격리한 메일이 다 있는 메일 받기 구역에 접근성 위반이 없다', async () => {
    state.status = withMailIn(
      mailInOn({ lastSuccessAt: LAST_SUCCESS, roundFailures: 4, lastFailure: { kind: 'FOLDER', at: LAST_FAILURE }, retrying: 7, quarantined90d: 3 }),
    );
    renderAt(URL_SYSTEM);
    const part = await mailInPart();
    expect(text(part)).toContain(QUARANTINE_NOTE);

    await expectNoAxeViolations();
  });
});
