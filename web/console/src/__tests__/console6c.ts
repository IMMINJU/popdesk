// More test support for slice 6c (design/slices/6c-links/screens.md): links between issues. The
// earlier support files stay as they are; this file adds the link endpoints to the fake server, in
// the shape of docs/integrate/openapi-console.json, and the helpers the slice 6c screen tests share.
//
// Every answer made here goes through `contract` of console6a.ts: a fixture that does not fit the
// contract file fails the test that used it.
import type { ConsoleComponents } from '@popdesk/api/console';
import { HttpResponse, http } from 'msw/http';
import { beforeEach, expect } from 'vitest';
import { AGENT_PERSON, T0, errors, eventually, found, issue, screen, server, state, text, within, type Issue, type Person } from './console';
// The slice 3 handlers too: the history of the issue (`s3.history`).
import { history, s3, type IssueHistoryEntry } from './console3';
import { contract, rejectedWith } from './console6a';

type Schemas = ConsoleComponents['schemas'];
export type IssueLink = Schemas['IssueLink'];
export type LinkedIssue = Schemas['LinkedIssue'];
export type IssueLinkType = IssueLink['type'];
export type IssueHistoryLink = Schemas['IssueHistoryLink'];

export const LINKS_7 = '/api/console/issues/ACME-7/links';

/** The path of the links of an issue. */
export function linksPath(key = 'ACME-7'): string {
  return `/api/console/issues/${key}/links`;
}

/** The path that removes the link between `key` and `otherKey`. */
export function linkPath(otherKey: string, key = 'ACME-7'): string {
  return `${linksPath(key)}/${otherKey}`;
}

/** The path of one issue: what "찾기" of "문의 잇기" asks for (6c acceptance.md 5절 12). */
export function issuePath(key: string): string {
  return `/api/console/issues/${key}`;
}

/** When the fake server makes a link. */
export const LINKED_AT = '2026-10-08T05:00:00Z';

// ---------------------------------------------------------------------------
// Fixtures

/** An issue of the other company: what most links of these tests point to. */
export const BETA_3: Issue = issue({
  key: 'BETA-3',
  title: '결제가 두 번 됩니다',
  status: 'IN_PROGRESS',
  companyCode: 'BETA',
  companyName: '베타상사',
  nextStatuses: ['RESOLVED'],
});

/** An issue of the same company, closed: a link may point to a closed issue (S6C-AC-07). */
export const ACME_2: Issue = issue({ key: 'ACME-2', title: '비밀번호 재설정 메일이 안 옵니다', status: 'CLOSED', closedAt: T0 });

/** A third issue, of the other company. */
export const BETA_9: Issue = issue({ key: 'BETA-9', title: '영수증이 안 나옵니다', status: 'RECEIVED', companyCode: 'BETA', companyName: '베타상사' });

/** The other issue of a link, as the contract gives it. */
export function linkedOf(i: Issue): LinkedIssue {
  return { key: i.key, title: i.title, status: i.status, companyCode: i.companyCode, companyName: i.companyName };
}

/** A link of ACME-7 to BETA-3, related, made by 이상담. */
export function link(over: Partial<IssueLink> = {}): IssueLink {
  return { type: 'RELATED', issue: linkedOf(BETA_3), createdBy: AGENT_PERSON, createdAt: T0, ...over };
}

/** A link to `other` of `type`. */
export function linkTo(other: Issue, type: IssueLinkType = 'RELATED'): IssueLink {
  return link({ type, issue: linkedOf(other) });
}

/** The link part of a history entry. */
export function historyLink(type: IssueLinkType, other: Issue = BETA_3): IssueHistoryLink {
  return { type, issue: { key: other.key, title: other.title } };
}

/**
 * A `LINK` entry of the history as the contract gives it: the people part in `oldLink`·`newLink`,
 * `oldValue`·`newValue` null (6c acceptance.md 5절 6). Made: `newLink`; removed: `oldLink`.
 */
export function linkEntry(over: Partial<IssueHistoryEntry> = {}): IssueHistoryEntry {
  return history({ field: 'LINK', oldValue: null, newValue: null, oldLink: null, newLink: null, ...over });
}

// ---------------------------------------------------------------------------
// Fake server

/** State the slice 6c handlers read. Tests change it before rendering. */
export const s6c: {
  /** The links of each issue, by key, in the order they were made. */
  links: Record<string, IssueLink[]>;
  /** Issues other than `state.issue` the fake server knows: what "찾기" finds. */
  others: Issue[];
  nextHistoryId: number;
} = { links: {}, others: [], nextHistoryId: 900 };

function resetS6c() {
  s6c.links = {};
  s6c.others = [BETA_3, ACME_2, BETA_9];
  s6c.nextHistoryId = 900;
}

type PathValue = string | readonly string[] | undefined;

function keyOf(key: PathValue): string {
  return String(key);
}

function requireSession(): Response | undefined {
  return state.signedIn ? undefined : errors.unauthenticated();
}

function me(): Person {
  return { accountId: state.me.id, name: state.me.name, role: state.me.role, photoUpdatedAt: state.me.photoUpdatedAt };
}

/** The issue `key` as the fake server knows it: `state.issue` or one of `s6c.others`. */
function issueOf(key: PathValue): Issue | undefined {
  const k = keyOf(key);
  return k === state.issue.key ? state.issue : s6c.others.find((i) => i.key === k);
}

/** 404 for an issue the fake server does not have: neither the one of `state` nor one with links set. */
function unknownIssue(key: PathValue): Response | undefined {
  const k = keyOf(key);
  return requireSession() ?? (k === state.issue.key || Object.hasOwn(s6c.links, k) ? undefined : errors.notFound());
}

export function linksOf(key = 'ACME-7'): IssueLink[] {
  return s6c.links[key] ?? [];
}

/** Sets the links of an issue. */
export function seedLinks(links: IssueLink[], key = 'ACME-7') {
  s6c.links = { ...s6c.links, [key]: links };
}

function closed(key: PathValue): boolean {
  return issueOf(key)?.status === 'CLOSED';
}

/** The history entry of this issue for a link made or removed by the caller. */
function noted(over: Partial<IssueHistoryEntry>) {
  s3.history = [...s3.history, linkEntry({ id: s6c.nextHistoryId++, actor: me(), createdAt: LINKED_AT, ...over })];
}

async function jsonOf(request: Request): Promise<Record<string, unknown>> {
  const raw = await request.text();
  return raw === '' ? {} : (JSON.parse(raw) as Record<string, unknown>);
}

const TYPES: readonly string[] = ['DUPLICATE_OF', 'DUPLICATED_BY', 'RELATED'];

/** What the slice 6c server does when nothing goes wrong. Tests replace one with `route` of console1a. */
const handlers6c = [
  // 문의 읽기 again: this one also knows the issues of `s6c.others` ("찾기" of "문의 잇기").
  http.get('*/api/console/issues/:key', ({ params }) => {
    const denied = requireSession();
    if (denied) return denied;
    const found = issueOf(params.key);
    return found ? HttpResponse.json(contract('getIssue', 200, found)) : errors.notFound();
  }),
  http.get('*/api/console/issues/:key/links', ({ params }) => {
    const denied = unknownIssue(params.key);
    return denied ?? HttpResponse.json(contract('listIssueLinks', 200, { items: linksOf(keyOf(params.key)) }));
  }),
  http.post('*/api/console/issues/:key/links', async ({ request, params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const body = await jsonOf(request);
    const otherKey = typeof body['issueKey'] === 'string' ? body['issueKey'].trim() : '';
    if (otherKey === '') return rejectedWith('createIssueLink', 'INVALID_INPUT', 400, [{ field: 'issueKey', code: 'REQUIRED' }]);
    const other = issueOf(otherKey);
    if (!other) return errors.notFound();
    const type = body['type'];
    if (typeof type !== 'string' || !TYPES.includes(type)) return rejectedWith('createIssueLink', 'INVALID_INPUT', 400, [{ field: 'type', code: 'UNKNOWN_VALUE' }]);
    const k = keyOf(params.key);
    if (other.key === k) return rejectedWith('createIssueLink', 'LINK_TO_SELF');
    if (closed(params.key)) return rejectedWith('createIssueLink', 'ISSUE_CLOSED');
    if (linksOf(k).some((l) => l.issue.key === other.key)) return rejectedWith('createIssueLink', 'LINK_EXISTS', 409);
    const created = link({ type: type as IssueLinkType, issue: linkedOf(other), createdBy: me(), createdAt: LINKED_AT });
    seedLinks([...linksOf(k), created], k);
    noted({ newLink: historyLink(created.type, other) });
    return HttpResponse.json(contract('createIssueLink', 201, created), { status: 201 });
  }),
  http.delete('*/api/console/issues/:key/links/:otherKey', ({ params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const k = keyOf(params.key);
    const was = linksOf(k).find((l) => l.issue.key === params.otherKey);
    if (!was) return errors.notFound();
    if (closed(params.key)) return rejectedWith('deleteIssueLink', 'ISSUE_CLOSED');
    seedLinks(
      linksOf(k).filter((l) => l !== was),
      k,
    );
    noted({ oldLink: { type: was.type, issue: { key: was.issue.key, title: was.issue.title } } });
    return new HttpResponse(null, { status: 204 });
  }),
];

beforeEach(() => {
  resetS6c();
  server.use(...handlers6c);
});

/** The answer of `listIssueLinks` for `links`, through the contract check. */
export function linksResponse(links: readonly IssueLink[]): Response {
  return HttpResponse.json(contract('listIssueLinks', 200, { items: [...links] }));
}

/** The answer of `createIssueLink`, through the contract check. */
export function linkResponse(l: IssueLink): Response {
  return HttpResponse.json(contract('createIssueLink', 201, l), { status: 201 });
}

/** The answer of `getIssue` for `i`, through the contract check. */
export function issueResponse(i: Issue): Response {
  return HttpResponse.json(contract('getIssue', 200, i));
}

// ---------------------------------------------------------------------------
// Finding things in the 연결 구역 of C-4 (screens.md 1절)

/** The 연결 구역: a region named "연결된 문의" or "연결된 문의 n", or null now. */
export function linkRegionNow(): HTMLElement | null {
  return screen.queryByRole('region', { name: /^연결된 문의( \d+)?$/ });
}

export function linkRegion(): Promise<HTMLElement> {
  return found(linkRegionNow, '구역 "연결된 문의"');
}

const LINE = 'li, tr, article, [role="listitem"], [role="row"], [role="article"]';

/** The outermost line inside `root` whose text holds `part`, or null now. */
export function lineIn(root: HTMLElement, part: string): HTMLElement | null {
  const all = Array.from(root.querySelectorAll<HTMLElement>(LINE)).filter((el) => text(el).includes(part));
  return all.find((el) => !all.some((other) => other !== el && other.contains(el))) ?? null;
}

/** The line of the link to `key` in the 연결 구역, or null now. */
export function linkLineNow(key: string): HTMLElement | null {
  const region = linkRegionNow();
  return region ? lineIn(region, key) : null;
}

export function linkLine(key: string): Promise<HTMLElement> {
  return found(() => linkLineNow(key), `연결 줄 "${key}"`);
}

/** The "끊기" of the link to `key`: its accessible name is "{키} 연결 끊기" (screens.md 1절), or null now. */
export function unlinkButtonNow(key: string): HTMLElement | null {
  const region = linkRegionNow();
  return region ? within(region).queryByRole('button', { name: `${key} 연결 끊기` }) : null;
}

export function unlinkButton(key: string): Promise<HTMLElement> {
  return found(() => unlinkButtonNow(key), `"${key} 연결 끊기"`);
}

/** The "문의 잇기" button of the 연결 구역, or null now. */
export function linkOpenerNow(): HTMLElement | null {
  const region = linkRegionNow();
  return region ? within(region).queryByRole('button', { name: '문의 잇기' }) : null;
}

export function linkOpener(): Promise<HTMLElement> {
  return found(linkOpenerNow, '"문의 잇기" 단추');
}

/** The dialog "문의 잇기", or null now. */
export function linkDialogNow(): HTMLElement | null {
  return screen.queryByRole('dialog', { name: '문의 잇기' });
}

export function linkDialog(): Promise<HTMLElement> {
  return found(linkDialogNow, '대화상자 "문의 잇기"');
}

/** The key input "문의 키" of the dialog. */
export function keyBox(box: HTMLElement): Promise<HTMLInputElement> {
  return found(() => within(box).queryByRole<HTMLInputElement>('textbox', { name: '문의 키' }), '칸 "문의 키"');
}

/** A button of the dialog by its exact name ("찾기", "잇기", "그만두기"). */
export function dialogButton(box: HTMLElement, name: '찾기' | '잇기' | '그만두기'): Promise<HTMLElement> {
  return found(() => within(box).queryByRole('button', { name }), `대화상자의 "${name}"`);
}

/** The kind radios of the dialog: the group named "종류". */
export function kindGroup(box: HTMLElement): Promise<HTMLElement> {
  return found(() => within(box).queryByRole('radiogroup', { name: '종류' }) ?? within(box).queryByRole('group', { name: '종류' }), '묶음 "종류"');
}

type User = { click: (el: Element) => Promise<void>; type: (el: Element, text: string) => Promise<void> };

/** Opens "문의 잇기" and returns the dialog. */
export async function openLinkDialog(user: User): Promise<HTMLElement> {
  await user.click(await linkOpener());
  return linkDialog();
}

/** Goes to another issue as a person would: the menu "문의", then its link in the list. */
export async function goToIssue(user: User, title: RegExp, key: string): Promise<void> {
  await user.click(await found(() => screen.queryAllByRole('link', { name: '문의' }).find((a) => a.closest('nav') !== null), '메뉴 "문의"'));
  await user.click(await found(() => screen.queryByRole('link', { name: title }), `${key} 링크`));
  await eventually(() => {
    expect(window.location.pathname).toBe(`/issues/${key}`);
  });
}

/** Types `key` into "문의 키" and presses "찾기". */
export async function search(user: User, box: HTMLElement, key: string): Promise<HTMLInputElement> {
  const input = await keyBox(box);
  await user.type(input, key);
  await user.click(await dialogButton(box, '찾기'));
  return input;
}
