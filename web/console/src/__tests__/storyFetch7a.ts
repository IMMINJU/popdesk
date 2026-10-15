// The trash answers for the slice 7a screen stories (7a 묶음 screens.md 4절, S7A-AC-54). storyFetch.ts
// answers the rest of the console API; this file wraps the `fetch` in place and answers the trash
// list (`listTrash`), restoring (`restoreIssue`) and purging (`purgeIssue`) from a scenario the
// story sets.
//
// Import this file before any module that reaches `src/api.ts`: openapi-fetch keeps the `fetch`
// it finds when the client is made.
import type { Schemas } from '../api';
import { issue } from './storyFetch';

type TrashedIssue = Schemas['TrashedIssue'];
type Person = Schemas['Person'];

export const CHOI: Person = { accountId: 1, name: '최관리', role: 'ADMIN', photoUpdatedAt: null };

export function trashed(over: Partial<TrashedIssue> = {}): TrashedIssue {
  const deletedAt = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
  const purgeAt = new Date(Date.now() + 29 * 24 * 60 * 60 * 1000).toISOString();
  return { key: 'ACME-12', title: '결제 내역이 두 번 보입니다', companyCode: 'ACME', companyName: '에이크미', status: 'IN_PROGRESS', deletedAt, deletedBy: CHOI, purgeAt, ...over };
}

export interface TrashScenario {
  /** The issues in the trash, in the order the list gives them. */
  trash: TrashedIssue[];
  /** The other keys a restore refuses with 409 `RESTORE_LINK_CONFLICT`, or null for 200. */
  conflict: string[] | null;
}

let current: TrashScenario = { trash: [], conflict: null };

/** The trash the next requests are answered from. */
export function setTrashScenario(next: TrashScenario): void {
  current = next;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

const before = globalThis.fetch;

globalThis.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const request = input instanceof Request ? input : new Request(input, init);
  const url = new URL(request.url, window.location.href);
  if (url.pathname === '/api/console/trash' && request.method === 'GET') {
    const items = current.trash;
    return Promise.resolve(json({ items, page: 1, size: 20, totalElements: items.length, totalPages: items.length === 0 ? 0 : 1 }));
  }
  const restore = /^\/api\/console\/trash\/([^/]+)\/restore$/.exec(url.pathname);
  if (restore && request.method === 'POST') {
    const key = decodeURIComponent(restore[1] ?? '');
    const one = current.trash.find((t) => t.key === key);
    if (!one) return Promise.resolve(json({ code: 'NOT_FOUND', message: 'Not found.' }, 404));
    if (current.conflict) return Promise.resolve(json({ code: 'RESTORE_LINK_CONFLICT', message: 'RESTORE_LINK_CONFLICT (server text).', issueKeys: current.conflict }, 409));
    return Promise.resolve(json(issue({ key: one.key, title: one.title, status: one.status, companyCode: one.companyCode, companyName: one.companyName })));
  }
  if (/^\/api\/console\/trash\/[^/]+$/.test(url.pathname) && request.method === 'DELETE') return Promise.resolve(new Response(null, { status: 204 }));
  return before(input, init);
};
