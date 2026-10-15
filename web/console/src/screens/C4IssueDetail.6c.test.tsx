// 6c 묶음 screens.md 1절 C-4 문의 상세의 연결 구역과 "문의 잇기" 대화상자, 2절 이력의 LINK 문장, 그리고 늦은 답
// (S6C-AC-55). 같은 글자(키, 제목)가 화면 두 곳에 나올 수 있어 찾기는 연결 구역이나 대화상자 안으로 좁힌다.
import { waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import {
  AGENT_PERSON,
  callsTo,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  hrefPath,
  isBlocked,
  openDialog,
  renderAt,
  screen,
  sent,
  state,
  summary,
  text,
  within,
} from '../__tests__/console';
import { answered, closed, route, settle } from '../__tests__/console1a';
import { STATUS_WORDS, issueIn, opener, s3 } from '../__tests__/console3';
import { bodyOf, metaValueNow, rejectedWith } from '../__tests__/console6a';
import {
  ACME_2,
  BETA_3,
  BETA_9,
  LINKS_7,
  dialogButton,
  focusOnLinkHeading,
  goToIssue,
  historyLink,
  issuePath,
  issueResponse,
  keyBox,
  kindGroup,
  link,
  linkDialogNow,
  linkEntry,
  linkLine,
  linkLineNow,
  linkOpenerNow,
  linkPath,
  linkRegion,
  linkRegionNow,
  linkResponse,
  linkTo,
  linksOf,
  linksResponse,
  openLinkDialog,
  search,
  seedLinks,
  unlinkButton,
  unlinkButtonNow,
  type IssueHistoryLink,
  type IssueLinkType,
} from '../__tests__/console6c';

const URL_7 = '/issues/ACME-7';
const HISTORY_7 = '/api/console/issues/ACME-7/history';
const ISSUE_7 = issuePath('ACME-7');
const EMPTY = '연결된 문의가 없습니다.';
const UNLINK_FAILED = '연결을 바꾸지 못했습니다. 다시 해 보세요.';
const NOT_FOUND = '찾을 수 없는 문의입니다.';
const SELF = '이 문의와는 이을 수 없습니다.';
const LINK_FAILED = '연결하지 못했습니다. 다시 해 보세요.';
/** rateLimitedText(30) of the console (screens.md 5절). */
const RATE_LIMITED = '요청이 많습니다. 30초 뒤 다시 하세요.';

const KIND_WORDS: Readonly<Record<IssueLinkType, string>> = {
  DUPLICATE_OF: '이 문의의 원본',
  DUPLICATED_BY: '이 문의의 중복',
  RELATED: '관련',
};

const RADIOS: readonly { label: string; type: IssueLinkType }[] = [
  { label: '관련', type: 'RELATED' },
  { label: '이 문의가 찾은 문의의 중복', type: 'DUPLICATE_OF' },
  { label: '찾은 문의가 이 문의의 중복', type: 'DUPLICATED_BY' },
];

type User = ReturnType<typeof renderAt>['user'];

/** ACME-7 linked to three issues, one of each kind, in the order they were made. */
const THREE = [linkTo(BETA_3, 'DUPLICATE_OF'), linkTo(ACME_2, 'DUPLICATED_BY'), linkTo(BETA_9, 'RELATED')];

/** Waits generously for C-4 and its 연결 구역: the first wait of a test may meet a busy machine. */
async function opened(): Promise<HTMLElement> {
  await waitFor(
    () => {
      expect(metaValueNow('담당자'), 'C-4 머리의 "담당자"').not.toBeNull();
    },
    { timeout: 5000 },
  );
  return linkRegion();
}

/** The name of the 연결 구역 as a screen reader reads it (its heading). */
function regionName(region: HTMLElement): string {
  const by = region.getAttribute('aria-labelledby');
  if (by !== null) return by.split(/\s+/).map((id) => text(document.getElementById(id))).join(' ');
  return region.getAttribute('aria-label') ?? '';
}

/** Opens the history (a folded part of C-4) and waits for its first answer. */
async function openHistory(user: User): Promise<void> {
  await user.click(await opener(/^이력/));
  await sent('GET', HISTORY_7);
}

/** Waits until the dialog shows the issue it found. */
function shownIn(box: HTMLElement, title: string): Promise<HTMLElement> {
  return found(() => (text(box).includes(title) ? box : null), `찾은 문의 "${title}"`);
}

/** Opens "문의 잇기" and finds BETA-3 in it. */
async function foundBeta(user: User): Promise<HTMLElement> {
  await opened();
  const box = await openLinkDialog(user);
  await search(user, box, 'BETA-3');
  return shownIn(box, BETA_3.title);
}

function keysShown(region: HTMLElement, keys: readonly string[]): string[] {
  const all = text(region);
  return keys.filter((k) => all.includes(k)).sort((a, b) => all.indexOf(a) - all.indexOf(b));
}

describe('C-4 연결 구역 (6c 묶음)', () => {
  test('S6C-AC-50 C-4 구역: 작업 다음, 댓글 앞에 "연결된 문의 3"이고 줄은 이은 차례다', async () => {
    seedLinks(THREE);
    state.issue = issueIn('IN_PROGRESS');
    renderAt(URL_7);
    const region = await opened();

    await eventually(() => {
      expect(regionName(region)).toBe('연결된 문의 3');
    });
    expect(keysShown(region, ['BETA-3', 'ACME-2', 'BETA-9'])).toEqual(['BETA-3', 'ACME-2', 'BETA-9']);
    const tasks = await found(() => screen.queryByRole('region', { name: /^작업( \d+\/\d+)?$/ }), '구역 "작업"');
    expect(region.compareDocumentPosition(tasks) & Node.DOCUMENT_POSITION_PRECEDING, '작업 구역이 연결 구역 앞').toBeTruthy();
    const comments = await found(() => screen.queryByRole('heading', { name: /^댓글/ }), '제목 "댓글 n개"');
    expect(region.compareDocumentPosition(comments) & Node.DOCUMENT_POSITION_FOLLOWING, '댓글이 연결 구역 뒤').toBeTruthy();
    await expectNoAxeViolations();
  });

  test.each([
    { type: 'DUPLICATE_OF' as const, other: BETA_3 },
    { type: 'DUPLICATED_BY' as const, other: ACME_2 },
    { type: 'RELATED' as const, other: BETA_9 },
  ])('S6C-AC-50 C-4 줄($type): 종류 낱말, 키 링크(/issues/{키}), 제목, 상태, 고객사 코드와 이름이 있다', async ({ type, other }) => {
    seedLinks(THREE);
    state.issue = issueIn('IN_PROGRESS');
    renderAt(URL_7);
    await opened();

    const line = await linkLine(other.key);
    expect(text(line)).toContain(KIND_WORDS[type]);
    const key = await found(() => within(line).queryByRole<HTMLAnchorElement>('link', { name: new RegExp(other.key) }), `${other.key} 링크`);
    expect(hrefPath(key)).toBe(`/issues/${other.key}`);
    expect(text(line)).toContain(other.title);
    expect(text(line)).toContain(STATUS_WORDS[other.status]);
    expect(text(line)).toContain(other.companyCode);
    expect(text(line)).toContain(other.companyName);
  });

  test('S6C-AC-50 C-4 키 링크를 누르면 그 문의로 간다', async () => {
    seedLinks(THREE);
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await opened();

    const line = await linkLine('BETA-3');
    await user.click(within(line).getByRole('link', { name: /BETA-3/ }));

    await eventually(() => {
      expect(window.location.pathname).toBe('/issues/BETA-3');
    });
    await sent('GET', issuePath('BETA-3'));
  });

  test('S6C-AC-50 C-4 비었을 때: 구역 이름은 "연결된 문의"이고 "연결된 문의가 없습니다."를 보인다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    renderAt(URL_7);
    const region = await opened();

    await sent('GET', LINKS_7);
    await eventually(() => {
      expect(text(region)).toContain(EMPTY);
    });
    expect(regionName(region)).toBe('연결된 문의');
    expect(linkOpenerNow(), '"문의 잇기"').not.toBeNull();
  });

  test('S6C-AC-52 C-4 끊기: "BETA-3 연결 끊기"는 묻지 않고 DELETE를 보내며, 줄이 빠지고 이력을 다시 읽는다', async () => {
    seedLinks(THREE);
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    const region = await opened();
    await openHistory(user);
    const historyBefore = callsTo('GET', HISTORY_7).length;

    await user.click(await unlinkButton('BETA-3'));

    expect(openDialog(), '묻는 대화상자').toBeNull();
    await sent('DELETE', linkPath('BETA-3'));
    await eventually(() => {
      expect(linkLineNow('BETA-3'), '끊은 줄').toBeNull();
      expect(regionName(region)).toBe('연결된 문의 2');
    });
    await eventually(() => {
      expect(callsTo('GET', HISTORY_7).length, '이력 다시 읽기').toBeGreaterThan(historyBefore);
    });
    expect(linkLineNow('ACME-2'), '남은 줄').not.toBeNull();
    expect(linkOpenerNow(), '다시 이을 수 있다: "문의 잇기"').not.toBeNull();
    await focusOnLinkHeading();
  });

  test('S6C-AC-52 C-4 CLOSED: "문의 잇기"와 "끊기"가 없고 목록은 그대로 보인다', async () => {
    seedLinks(THREE);
    state.issue = issueIn('CLOSED');
    renderAt(URL_7);
    const region = await opened();

    await linkLine('BETA-3');
    await linkLine('BETA-9');
    await sent('GET', LINKS_7);
    await settle();
    expect(linkOpenerNow(), '"문의 잇기"').toBeNull();
    expect(within(region).queryAllByRole('button', { name: /연결 끊기$/ }), '"끊기"').toHaveLength(0);
    expect(regionName(region)).toBe('연결된 문의 3');
  });

  test.each([
    { what: '500', answer: () => errors.internal(), says: UNLINK_FAILED },
    { what: '429', answer: () => errors.rateLimited(30), says: RATE_LIMITED },
  ])('S6C-AC-52 C-4 끊기 실패($what): 줄 아래 role="alert" "$says"이고 줄은 그대로다', async ({ answer, says }) => {
    seedLinks(THREE);
    state.issue = issueIn('IN_PROGRESS');
    route('delete', linkPath('BETA-3'), answer);
    const { user } = renderAt(URL_7);
    const region = await opened();

    await user.click(await unlinkButton('BETA-3'));

    const alert = await found(() => within(region).queryAllByRole('alert').find((a) => text(a).includes(says)), `role="alert" "${says}"`);
    const line = await linkLine('BETA-3');
    expect(line.compareDocumentPosition(alert) & (Node.DOCUMENT_POSITION_FOLLOWING | Node.DOCUMENT_POSITION_CONTAINED_BY), '오류가 줄 아래').toBeTruthy();
    expect(unlinkButtonNow('BETA-3'), '줄의 "끊기"').not.toBeNull();
  });

  test('S6C-AC-52 C-4 끊기 404: 누군가 먼저 끊었으면 목록을 다시 읽어 그 줄이 빠지고 오류 문구는 없다', async () => {
    seedLinks(THREE);
    state.issue = issueIn('IN_PROGRESS');
    route('delete', linkPath('BETA-3'), () => {
      seedLinks(linksOf().filter((l) => l.issue.key !== 'BETA-3'));
      return errors.notFound();
    });
    const { user } = renderAt(URL_7);
    await opened();
    await linkLine('BETA-3');
    const before = callsTo('GET', LINKS_7).length;

    await user.click(await unlinkButton('BETA-3'));

    await sent('DELETE', linkPath('BETA-3'));
    await eventually(() => {
      expect(callsTo('GET', LINKS_7).length, '목록 다시 읽기').toBeGreaterThan(before);
      expect(linkLineNow('BETA-3'), '먼저 끊긴 줄').toBeNull();
    });
    expect(linkLineNow('ACME-2')).not.toBeNull();
    await settle();
    expect(text(document.body), '끊기 404의 오류 문구').not.toContain(UNLINK_FAILED);
  });

  test('S6C-AC-52 C-4 끊기 답 ISSUE_CLOSED: 문의를 다시 읽어 "문의 잇기"와 "끊기"가 사라지고 초점은 구역 제목으로 간다', async () => {
    seedLinks(THREE);
    state.issue = issueIn('IN_PROGRESS');
    route('delete', linkPath('BETA-3'), () => {
      state.issue = issueIn('CLOSED');
      return rejectedWith('deleteIssueLink', 'ISSUE_CLOSED');
    });
    const { user } = renderAt(URL_7);
    await opened();
    const before = callsTo('GET', ISSUE_7).length;

    await user.click(await unlinkButton('BETA-3'));

    await sent('DELETE', linkPath('BETA-3'));
    await eventually(() => {
      expect(callsTo('GET', ISSUE_7).length, '문의 다시 읽기').toBeGreaterThan(before);
      expect(linkOpenerNow(), '닫힌 문의의 "문의 잇기"').toBeNull();
      expect(unlinkButtonNow('BETA-3'), '닫힌 문의의 "끊기"').toBeNull();
    });
    expect(linkLineNow('BETA-3'), '목록은 그대로').not.toBeNull();
    await focusOnLinkHeading();
  });

  test('S6C-AC-50 C-4 연결 목록 읽기 실패: 구역 안에 role="alert" 띠와 "다시 불러오기"이고, 누르면 목록이 나온다', async () => {
    seedLinks(THREE);
    state.issue = issueIn('IN_PROGRESS');
    let fail = true;
    route('get', LINKS_7, () => (fail ? errors.internal() : linksResponse(linksOf())));
    const { user } = renderAt(URL_7);
    const region = await opened();

    const alert = await found(() => within(region).queryAllByRole('alert')[0] ?? null, '구역 안 role="alert"');
    const retry = await found(() => within(region).queryByRole('button', { name: '다시 불러오기' }), '구역 안 "다시 불러오기"');
    expect(alert.contains(retry) || alert.compareDocumentPosition(retry) & Node.DOCUMENT_POSITION_FOLLOWING, '띠의 "다시 불러오기"').toBeTruthy();
    expect(text(region), '읽지 못했을 때의 빈 목록 문구').not.toContain(EMPTY);
    const before = callsTo('GET', LINKS_7).length;
    fail = false;

    await user.click(retry);

    await eventually(() => {
      expect(callsTo('GET', LINKS_7).length, '목록 다시 읽기').toBeGreaterThan(before);
    });
    await linkLine('BETA-3');
    await eventually(() => {
      expect(within(linkRegionNow() ?? region).queryAllByRole('alert'), '다시 읽은 뒤의 띠').toHaveLength(0);
    });
  });
});

describe('C-4 "문의 잇기" 대화상자 (6c 묶음)', () => {
  test('S6C-AC-51 C-4 열기: 제목 "문의 잇기", 칸 "문의 키"(자리 글 ACME-12), 종류 라디오 셋과 기본 "관련", 찾기 전에는 "잇기"가 막혀 있다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await opened();

    const box = await openLinkDialog(user);
    const input = await keyBox(box);
    expect(input.getAttribute('placeholder') ?? '').toContain('ACME-12');
    expect(input.value).toBe('');
    await dialogButton(box, '찾기');
    const group = await kindGroup(box);
    const radios = within(group).queryAllByRole<HTMLInputElement>('radio');
    expect(radios, '라디오 셋, 이 차례').toEqual(RADIOS.map((r) => within(group).getByRole('radio', { name: r.label })));
    expect(within(group).getByRole('radio', { name: '관련' })).toBeChecked();
    expect(isBlocked(await dialogButton(box, '잇기')), '찾은 문의가 없을 때의 "잇기"').toBe(true);
  });

  test('S6C-AC-51 C-4 그만두기: 보내지 않고 닫는다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await opened();
    const box = await openLinkDialog(user);

    await user.click(await dialogButton(box, '그만두기'));

    await closed();
    expect(callsTo('POST', LINKS_7)).toHaveLength(0);
  });

  test.each([
    { how: '찾기 단추, 공백과 소문자', typed: '  beta-3 ', enter: false },
    { how: 'Enter', typed: 'BETA-3', enter: true },
  ])('S6C-AC-51 C-4 찾기($how): 자르고 대문자로 바꿔 GET /issues/BETA-3을 보내고 키, 제목, 상태, 고객사를 보인다', async ({ typed, enter }) => {
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await opened();
    const box = await openLinkDialog(user);
    const input = await keyBox(box);

    await user.type(input, typed);
    if (enter) await user.keyboard('{Enter}');
    else await user.click(await dialogButton(box, '찾기'));

    await sent('GET', issuePath('BETA-3'));
    await shownIn(box, BETA_3.title);
    expect(text(box)).toContain('BETA-3');
    expect(text(box)).toContain(STATUS_WORDS[BETA_3.status]);
    expect(text(box)).toContain('베타상사');
    expect(linkDialogNow(), 'Enter가 대화상자를 닫지 않는다').not.toBeNull();
    expect(callsTo('POST', LINKS_7), 'Enter는 찾기다').toHaveLength(0);
    expect(isBlocked(await dialogButton(box, '잇기')), '찾은 뒤의 "잇기"').toBe(false);
  });

  test('S6C-AC-51 C-4 키를 바꾸면 찾은 문의가 지워지고 "잇기"가 다시 막힌다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    const box = await foundBeta(user);

    await user.type(await keyBox(box), '0');

    await eventually(() => {
      expect(text(box), '지운 찾은 문의').not.toContain(BETA_3.title);
    });
    expect(isBlocked(await dialogButton(box, '잇기'))).toBe(true);
  });

  test.each([
    { what: '없는 키', typed: 'BETA-404' },
    { what: '꼴이 틀린 키', typed: '로그인' },
  ])('S6C-AC-51 C-4 못 찾음($what): "찾을 수 없는 문의입니다."이고 "잇기"는 막혀 있다', async ({ typed }) => {
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await opened();
    const box = await openLinkDialog(user);

    const input = await search(user, box, typed);

    await eventually(() => {
      expect(text(box)).toContain(NOT_FOUND);
    });
    expect(isBlocked(await dialogButton(box, '잇기'))).toBe(true);
    expect(input.value.trim().toUpperCase(), '입력은 그대로').toBe(typed.toUpperCase());
  });

  test('S6C-AC-51 C-4 자기 자신: 이 문의를 찾으면 "이 문의와는 이을 수 없습니다."이고 "잇기"는 막혀 있다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await opened();
    const box = await openLinkDialog(user);

    await search(user, box, 'acme-7');

    await eventually(() => {
      expect(text(box)).toContain(SELF);
    });
    expect(isBlocked(await dialogButton(box, '잇기'))).toBe(true);
  });

  test.each(RADIOS)('S6C-AC-51 C-4 종류 "$label": POST 몸통은 issueKey와 type($type)뿐이다', async ({ label, type }) => {
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    const box = await foundBeta(user);

    await user.click(within(await kindGroup(box)).getByRole('radio', { name: label }));
    await user.click(await dialogButton(box, '잇기'));

    await sent('POST', LINKS_7);
    expect(await bodyOf('POST', LINKS_7)).toEqual({ issueKey: 'BETA-3', type });
  });

  test('S6C-AC-51 C-4 공백과 소문자로 찾은 뒤 잇기: POST 몸통의 issueKey는 다듬은 키(BETA-3)다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await opened();
    const box = await openLinkDialog(user);
    await search(user, box, '  beta-3 ');
    await shownIn(box, BETA_3.title);

    await user.click(await dialogButton(box, '잇기'));

    await sent('POST', LINKS_7);
    expect(await bodyOf('POST', LINKS_7)).toEqual({ issueKey: 'BETA-3', type: 'RELATED' });
  });

  test('S6C-AC-51 C-4 보내는 동안 "잇기"가 막힌다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const release = gate();
    route('post', LINKS_7, async () => {
      await release.opened;
      return linkResponse(link());
    });
    const { user } = renderAt(URL_7);
    const box = await foundBeta(user);

    // The button may read "보내는 중…" while sending (screens.md 5절): hold the one pressed, not its name.
    const pressed = await dialogButton(box, '잇기');
    await user.click(pressed);

    await sent('POST', LINKS_7);
    await eventually(() => {
      expect(isBlocked(pressed), '보내는 동안의 "잇기"').toBe(true);
    });
    release.open();
    await closed();
  });

  test.each([
    { code: 'LINK_TO_SELF', status: 400, says: SELF },
    { code: 'LINK_EXISTS', status: 409, says: '이미 이어진 문의입니다.' },
    {
      code: 'LINK_DUPLICATE_CHAIN',
      status: 400,
      says: '중복은 한 단계만 이을 수 있습니다. 원본이 이미 다른 문의의 중복이거나, 중복이 될 문의가 이미 원본이거나 다른 원본이 있습니다.',
    },
    { code: 'LINK_LIMIT_EXCEEDED', status: 400, says: '연결은 문의마다 50개까지입니다.' },
    { code: 'NOT_FOUND', status: 404, says: NOT_FOUND },
    { code: 'INTERNAL_ERROR', status: 500, says: LINK_FAILED },
    { code: 'RATE_LIMITED', status: 429, says: RATE_LIMITED },
  ])('S6C-AC-51 C-4 서버 답 $code($status): 대화상자 안 role="alert" "$says"이고 입력은 그대로다', async ({ code, status, says }) => {
    state.issue = issueIn('IN_PROGRESS');
    route('post', LINKS_7, () =>
      status === 500 ? errors.internal() : status === 404 ? errors.notFound() : status === 429 ? errors.rateLimited(30) : rejectedWith('createIssueLink', code, status),
    );
    const { user } = renderAt(URL_7);
    const box = await foundBeta(user);
    await user.click(within(await kindGroup(box)).getByRole('radio', { name: '이 문의가 찾은 문의의 중복' }));

    await user.click(await dialogButton(box, '잇기'));

    await found(() => within(box).queryAllByRole('alert').find((a) => text(a).includes(says)), `대화상자 안 role="alert" "${says}"`);
    expect(linkDialogNow(), '열린 대화상자').not.toBeNull();
    expect((await keyBox(box)).value.trim().toUpperCase()).toBe('BETA-3');
    expect(within(await kindGroup(box)).getByRole('radio', { name: '이 문의가 찾은 문의의 중복' })).toBeChecked();
  });

  test('S6C-AC-51 C-4 서버 답 ISSUE_CLOSED: 대화상자를 닫고 문의를 다시 읽으며 초점은 구역 제목으로 간다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    route('post', LINKS_7, () => {
      state.issue = issueIn('CLOSED');
      return rejectedWith('createIssueLink', 'ISSUE_CLOSED');
    });
    const { user } = renderAt(URL_7);
    const box = await foundBeta(user);
    const before = callsTo('GET', ISSUE_7).length;

    await user.click(await dialogButton(box, '잇기'));

    await closed();
    await eventually(() => {
      expect(callsTo('GET', ISSUE_7).length, '문의 다시 읽기').toBeGreaterThan(before);
      expect(linkOpenerNow(), '닫힌 문의의 "문의 잇기"').toBeNull();
    });
    await focusOnLinkHeading();
  });

  test('S6C-AC-51 C-4 성공: 대화상자를 닫고 목록과 이력을 다시 읽으며 초점은 "문의 잇기"로 돌아간다', async () => {
    seedLinks([linkTo(ACME_2, 'RELATED')]);
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    const region = await opened();
    await openHistory(user);
    const historyBefore = callsTo('GET', HISTORY_7).length;
    const linksBefore = callsTo('GET', LINKS_7).length;
    const box = await openLinkDialog(user);
    await search(user, box, 'BETA-3');
    await shownIn(box, BETA_3.title);

    await user.click(await dialogButton(box, '잇기'));

    await sent('POST', LINKS_7);
    await closed();
    await eventually(() => {
      expect(callsTo('GET', LINKS_7).length, '목록 다시 읽기').toBeGreaterThan(linksBefore);
      expect(callsTo('GET', HISTORY_7).length, '이력 다시 읽기').toBeGreaterThan(historyBefore);
    });
    const line = await linkLine('BETA-3');
    expect(text(line)).toContain('관련');
    await eventually(() => {
      expect(regionName(region)).toBe('연결된 문의 2');
      expect(linkOpenerNow()).toHaveFocus();
    });
  });
});

describe('C-4 이력의 LINK 문장 (6c 묶음)', () => {
  const MERGED = { type: 'MERGED', issue: { key: 'BETA-3', title: BETA_3.title } } as unknown as IssueHistoryLink;

  test.each([
    { what: '이음 DUPLICATE_OF', oldLink: null, newLink: historyLink('DUPLICATE_OF'), says: '이상담 님이 이 문의를 BETA-3의 중복으로 이었습니다', titled: true },
    { what: '이음 DUPLICATED_BY', oldLink: null, newLink: historyLink('DUPLICATED_BY'), says: '이상담 님이 BETA-3를 이 문의의 중복으로 이었습니다', titled: true },
    { what: '이음 RELATED', oldLink: null, newLink: historyLink('RELATED'), says: '이상담 님이 BETA-3와 관련 문의로 이었습니다', titled: true },
    { what: '끊음 DUPLICATE_OF', oldLink: historyLink('DUPLICATE_OF'), newLink: null, says: '이상담 님이 BETA-3와의 연결을 끊었습니다', titled: true },
    { what: '끊음 RELATED', oldLink: historyLink('RELATED'), newLink: null, says: '이상담 님이 BETA-3와의 연결을 끊었습니다', titled: true },
    { what: '둘 다 null', oldLink: null, newLink: null, says: '이상담 님이 연결을 바꿨습니다', titled: false },
    { what: '모르는 종류', oldLink: null, newLink: MERGED, says: '이상담 님이 연결을 바꿨습니다', titled: false },
  ])('S6C-AC-53 C-4 이력($what): "$says"', async ({ oldLink, newLink, says, titled }) => {
    state.issue = issueIn('IN_PROGRESS');
    s3.history = [linkEntry({ id: 5, actor: AGENT_PERSON, oldLink, newLink })];
    const { user } = renderAt(URL_7);
    await user.click(await opener(/^이력/));

    const line = await found(
      () => screen.queryAllByRole('listitem').find((li) => /이상담 님이 .*(이었습니다|끊었습니다|바꿨습니다)/.test(text(li))),
      '이력의 연결 줄',
    );
    expect(text(line)).toContain(says);
    expect(text(line), '알지 못하는 칸의 문장').not.toContain('문의를 바꿨습니다');
    if (titled) {
      expect(text(line), '키 아래의 제목').toContain(BETA_3.title);
      const key = await found(() => within(line).queryByRole('link', { name: /BETA-3/ }), '키 링크 BETA-3');
      expect(hrefPath(key)).toBe('/issues/BETA-3');
    }
  });
});

describe('C-4 연결의 늦은 답 (6c 묶음)', () => {
  test('S6C-AC-55 C-4 대화상자를 닫은 뒤 온 잇기 성공은 목록과 이력을 다시 그린다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const release = gate();
    route('post', LINKS_7, async () => {
      await release.opened;
      const made = link({ type: 'RELATED' });
      seedLinks([made]);
      s3.history = [...s3.history, linkEntry({ id: 77, newLink: historyLink('RELATED') })];
      return linkResponse(made);
    });
    const { user } = renderAt(URL_7);
    await opened();
    await openHistory(user);
    const historyBefore = callsTo('GET', HISTORY_7).length;
    const box = await openLinkDialog(user);
    await search(user, box, 'BETA-3');
    await shownIn(box, BETA_3.title);
    await user.click(await dialogButton(box, '잇기'));
    await sent('POST', LINKS_7);

    await user.keyboard('{Escape}');
    await closed();
    const done = answered('POST', LINKS_7);
    release.open();
    await done;
    await settle();

    await linkLine('BETA-3');
    await eventually(() => {
      expect(callsTo('GET', HISTORY_7).length, '닫힌 뒤 온 성공의 이력 다시 읽기').toBeGreaterThan(historyBefore);
    });
    expect(openDialog(), '늦은 답이 대화상자를 다시 열지 않는다').toBeNull();
  });

  test('S6C-AC-55 C-4 대화상자를 닫은 뒤 온 잇기 실패는 버린다: 오류가 없고 다시 열면 처음부터다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const release = gate();
    route('post', LINKS_7, async () => {
      await release.opened;
      return rejectedWith('createIssueLink', 'LINK_EXISTS', 409);
    });
    const { user } = renderAt(URL_7);
    const region = await opened();
    const box = await foundBeta(user);
    await user.click(await dialogButton(box, '잇기'));
    await sent('POST', LINKS_7);

    await user.keyboard('{Escape}');
    await closed();
    const done = answered('POST', LINKS_7);
    release.open();
    await done;
    await settle();

    expect(text(document.body), '닫힌 뒤 온 실패').not.toContain('이미 이어진 문의입니다.');
    expect(text(region)).toContain(EMPTY);
    const again = await openLinkDialog(user);
    expect(within(again).queryAllByRole('alert'), '앞 요청의 오류').toHaveLength(0);
    expect(text(again), '앞에 찾은 문의').not.toContain(BETA_3.title);
  });

  test('S6C-AC-55 C-4 키를 바꾼 뒤 온 찾기 답은 버린다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const release = gate();
    route('get', issuePath('BETA-3'), async () => {
      await release.opened;
      return issueResponse(BETA_3);
    });
    const { user } = renderAt(URL_7);
    await opened();
    const box = await openLinkDialog(user);
    const input = await search(user, box, 'BETA-3');
    await sent('GET', issuePath('BETA-3'));

    await user.clear(input);
    await user.type(input, 'BETA-9');
    const done = answered('GET', issuePath('BETA-3'));
    release.open();
    await done;
    await settle();

    expect(text(box), '키를 바꾼 뒤 온 찾기 답').not.toContain(BETA_3.title);
    expect(isBlocked(await dialogButton(box, '잇기'))).toBe(true);
  });

  test('S6C-AC-55 C-4 대화상자를 닫은 뒤 온 찾기 답은 버린다: 다시 열면 처음부터다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const release = gate();
    route('get', issuePath('BETA-3'), async () => {
      await release.opened;
      return issueResponse(BETA_3);
    });
    const { user } = renderAt(URL_7);
    await opened();
    const box = await openLinkDialog(user);
    await search(user, box, 'BETA-3');
    await sent('GET', issuePath('BETA-3'));

    await user.keyboard('{Escape}');
    await closed();
    const done = answered('GET', issuePath('BETA-3'));
    release.open();
    await done;
    await settle();

    expect(openDialog(), '늦은 답이 대화상자를 다시 열지 않는다').toBeNull();
    const again = await openLinkDialog(user);
    expect((await keyBox(again)).value).toBe('');
    expect(text(again), '닫힌 뒤 온 찾기 답').not.toContain(BETA_3.title);
  });

  test('S6C-AC-55 C-4 다른 문의로 옮긴 뒤 온 연결 목록은 버린다: 새 문의의 연결만 있다', async () => {
    seedLinks(THREE);
    seedLinks([linkTo(BETA_9, 'RELATED')], 'ACME-8');
    state.issue = issueIn('IN_PROGRESS');
    state.issues = [summary({ key: 'ACME-8', title: '다른 문의입니다' })];
    const release = gate();
    route('get', LINKS_7, async () => {
      await release.opened;
      return linksResponse(linksOf('ACME-7'));
    });
    const { user } = renderAt(URL_7);
    await waitFor(
      () => {
        expect(metaValueNow('담당자')).not.toBeNull();
      },
      { timeout: 5000 },
    );
    await sent('GET', LINKS_7);

    state.issue = issueIn('IN_PROGRESS', { key: 'ACME-8', title: '다른 문의입니다' });
    await goToIssue(user, /다른 문의입니다/, 'ACME-8');
    await linkLine('BETA-9');
    const done = answered('GET', LINKS_7);
    release.open();
    await done;
    await settle();

    const region = await linkRegion();
    expect(text(region), 'ACME-7의 연결').not.toContain('BETA-3');
    expect(text(region), 'ACME-7의 연결').not.toContain('ACME-2');
    expect(regionName(region)).toBe('연결된 문의 1');
  });

  test.each([
    { what: '잇기 성공', method: 'post' as const, path: LINKS_7, start: 'link', answer: () => linkResponse(link()) },
    { what: '잇기 실패', method: 'post' as const, path: LINKS_7, start: 'link', answer: () => errors.internal() },
    { what: '끊기 성공', method: 'delete' as const, path: linkPath('BETA-3'), start: 'unlink', answer: () => new Response(null, { status: 204 }) },
    { what: '끊기 실패', method: 'delete' as const, path: linkPath('BETA-3'), start: 'unlink', answer: () => errors.internal() },
  ])('S6C-AC-55 C-4 다른 문의로 옮긴 뒤 온 $what 답은 버린다: 새 문의의 연결 구역이 그대로다', async ({ method, path, start, answer }) => {
    if (start === 'unlink') seedLinks([linkTo(BETA_3, 'RELATED')]);
    seedLinks([linkTo(BETA_9, 'RELATED')], 'ACME-8');
    state.issue = issueIn('IN_PROGRESS');
    state.issues = [summary({ key: 'ACME-8', title: '다른 문의입니다' })];
    const release = gate();
    route(method, path, async () => {
      await release.opened;
      return answer();
    });
    const { user } = renderAt(URL_7);
    await opened();
    if (start === 'link') {
      const box = await openLinkDialog(user);
      await search(user, box, 'BETA-3');
      await shownIn(box, BETA_3.title);
      await user.click(await dialogButton(box, '잇기'));
      await sent('POST', path);
      await user.keyboard('{Escape}');
      await closed();
    } else {
      await user.click(await unlinkButton('BETA-3'));
      await sent('DELETE', path);
    }

    state.issue = issueIn('IN_PROGRESS', { key: 'ACME-8', title: '다른 문의입니다' });
    await goToIssue(user, /다른 문의입니다/, 'ACME-8');
    await linkLine('BETA-9');
    const done = answered(method.toUpperCase(), path);
    release.open();
    await done;
    await settle();

    const region = await linkRegion();
    expect(linkLineNow('BETA-3'), 'ACME-7의 답').toBeNull();
    expect(text(document.body), '옮긴 뒤 온 실패').not.toContain(UNLINK_FAILED);
    expect(text(document.body), '옮긴 뒤 온 실패').not.toContain(LINK_FAILED);
    expect(regionName(region)).toBe('연결된 문의 1');
    expect(unlinkButtonNow('BETA-9'), 'ACME-8 연결의 "끊기"').not.toBeNull();
  });
});
