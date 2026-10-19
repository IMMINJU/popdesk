// 11 묶음 screens.md "C-18 시스템 상태": 구역 "OIDC 로그인"(S11-AC-34). 꺼짐은 "꺼짐", 켜짐은 켜진 앱 문장 셋, 마지막 성공
// (시각 또는 "앱이 뜬 뒤 없음"), 제공자 실패 수, 마지막 실패의 종류 문장 셋과 시각, 아래 설명. 자리는 채널 구역 뒤, 정리 작업 앞.
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, renderAt, state, text } from '../__tests__/console';
import { before, cleanupJob, section } from '../__tests__/console4b';
import {
  APPS_WORDS,
  COUNT_NOTE,
  FAILURE_WORDS,
  NO_SUCCESS,
  OFF,
  OIDC_TITLE,
  failuresText,
  oidcOn,
  withOidc,
  type OidcFailureKind,
} from '../__tests__/console11';

const URL_SYSTEM = '/system';
const LAST_SUCCESS = '2026-10-08T02:40:00Z';
const LAST_FAILURE = '2026-10-08T03:05:00Z';

/** The innermost element of `part` that holds `words` and a `<time>`, or null. */
function timeAfter(part: HTMLElement, words: string): HTMLTimeElement | null {
  const holder = Array.from(part.querySelectorAll('*'))
    .filter((el) => text(el).includes(words) && el.querySelector('time') !== null)
    .at(-1);
  return holder?.querySelector('time') ?? null;
}

const timeOf = (el: HTMLTimeElement | null) => new Date(el?.getAttribute('datetime') ?? '').getTime();

describe('C-18 시스템 상태의 OIDC 로그인 구역 (11 묶음)', () => {
  test('S11-AC-34 C-18 꺼짐: 구역 "OIDC 로그인"에 "꺼짐"이 있고 켜짐·성공·실패 줄은 없다', async () => {
    renderAt(URL_SYSTEM);

    const part = await section(OIDC_TITLE);
    const words = text(part);
    expect(words).toContain(OFF);
    for (const absent of ['켜짐', '마지막 성공', '제공자 실패', '마지막 실패']) expect(words, absent).not.toContain(absent);
  });

  test.each([
    ['콘솔', ['CONSOLE'] as const, APPS_WORDS.CONSOLE],
    ['포털', ['PORTAL'] as const, APPS_WORDS.PORTAL],
    ['둘 다', ['CONSOLE', 'PORTAL'] as const, APPS_WORDS.BOTH],
  ])('S11-AC-34 C-18 켜짐(%s): 켜진 앱 문장, "꺼짐"이 아니다, 아래 설명', async (_, apps, says) => {
    state.status = withOidc(oidcOn({ apps: [...apps] }));
    renderAt(URL_SYSTEM);

    const part = await section(OIDC_TITLE);
    expect(text(part)).toContain(says);
    expect(text(part)).not.toContain(OFF);
    expect(text(part)).toContain(COUNT_NOTE);
  });

  test('S11-AC-34 C-18 마지막 성공이 있음: "마지막 성공:" 뒤의 <time>이 그 시각이다', async () => {
    state.status = withOidc(oidcOn({ lastSuccessAt: LAST_SUCCESS }));
    renderAt(URL_SYSTEM);

    const part = await section(OIDC_TITLE);
    expect(text(part)).toContain('마지막 성공:');
    expect(text(part)).not.toContain(NO_SUCCESS);
    expect(timeOf(timeAfter(part, '마지막 성공')), '마지막 성공 <time>').toBe(new Date(LAST_SUCCESS).getTime());
  });

  test('S11-AC-34 C-18 마지막 성공이 없음: "마지막 성공: 앱이 뜬 뒤 없음"', async () => {
    state.status = withOidc(oidcOn({ lastSuccessAt: null }));
    renderAt(URL_SYSTEM);

    const part = await section(OIDC_TITLE);
    expect(text(part)).toContain(`마지막 성공: ${NO_SUCCESS}`);
  });

  test.each([0, 3])('S11-AC-34 C-18 제공자 실패 수 %i: "제공자 실패: {수}건"', async (count) => {
    state.status = withOidc(oidcOn({ providerFailures: count }));
    renderAt(URL_SYSTEM);

    const part = await section(OIDC_TITLE);
    expect(text(part)).toContain(failuresText(count));
  });

  test.each(Object.entries(FAILURE_WORDS) as [OidcFailureKind, string][])(
    'S11-AC-34 C-18 마지막 실패 %s: "마지막 실패: {종류 문장}, {시각}"이고 <time>이 그 시각이다',
    async (kind, words) => {
      state.status = withOidc(oidcOn({ providerFailures: 2, lastFailure: { kind, at: LAST_FAILURE } }));
      renderAt(URL_SYSTEM);

      const part = await section(OIDC_TITLE);
      expect(text(part)).toContain(`마지막 실패: ${words},`);
      expect(text(part), '종류 이름 그대로').not.toContain(kind);
      expect(timeOf(timeAfter(part, '마지막 실패')), '마지막 실패 <time>').toBe(new Date(LAST_FAILURE).getTime());
    },
  );

  test('S11-AC-34 C-18 마지막 실패가 없음: "마지막 실패" 줄이 없다', async () => {
    state.status = withOidc(oidcOn({ lastSuccessAt: LAST_SUCCESS, providerFailures: 0, lastFailure: null }));
    renderAt(URL_SYSTEM);

    const part = await section(OIDC_TITLE);
    expect(text(part)).toContain(failuresText(0));
    expect(text(part)).not.toContain('마지막 실패');
  });

  test('S11-AC-34 C-18 자리: 구역 "OIDC 로그인"은 "보내기 채널" 뒤, "정리 작업" 앞이다', async () => {
    state.status = withOidc(oidcOn());
    state.status = { ...state.status, cleanupJobs: [cleanupJob()] };
    renderAt(URL_SYSTEM);

    const part = await section(OIDC_TITLE);
    const channels = await section('보내기 채널');
    const jobs = await section('정리 작업');
    expect(before(channels, part), '채널 구역 뒤').toBe(true);
    expect(before(part, jobs), '정리 작업 앞').toBe(true);
    expect(channels.contains(part) || part.contains(jobs), '다른 구역 안이 아니다').toBe(false);
  });

  test('S11-AC-34 C-18 axe: 켜짐, 마지막 성공과 실패가 다 있는 OIDC 구역에 접근성 위반이 없다', async () => {
    state.status = withOidc(
      oidcOn({ apps: ['CONSOLE', 'PORTAL'], lastSuccessAt: LAST_SUCCESS, providerFailures: 4, lastFailure: { kind: 'TOKEN', at: LAST_FAILURE } }),
    );
    renderAt(URL_SYSTEM);
    const part = await section(OIDC_TITLE);
    expect(text(part)).toContain(COUNT_NOTE);

    await expectNoAxeViolations();
  });
});
