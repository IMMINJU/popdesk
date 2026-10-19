// Test support for slice b5 (design/archive/b5-english): opening the console in English.
// `console.ts` stays as it is and its tests stay in Korean (acceptance.md 4절 5). This file adds
// English fixtures, so the Hangul check never trips on data, and the check itself.
//
// - Signed in, the screen language is the account's `Me.locale` (B5-AC-01): `openInEnglish`.
// - Signed out, it is the browser's first language (B5-AC-02): `signedOutInEnglish`.
// - `expectNoHangul` looks at the text and at `aria-label`, `title`, `placeholder` and `alt` of
//   every element, and names what it found in the failure message (B5-AC-05).
import { expect } from 'vitest';
import {
  T0,
  account,
  attachment,
  comment,
  company,
  invitation,
  issue,
  member,
  state,
  summary,
  type Account,
  type Attachment,
  type Comment,
  type Company,
  type CompanyMember,
  type Issue,
  type IssueSummary,
  type Me,
  type PendingInvitation,
  type Person,
} from './console';

// ---------------------------------------------------------------------------
// English fixtures

export const ADMIN_EN: Me = { id: 1, email: 'admin@popdesk.test', name: 'Morgan Admin', role: 'ADMIN', locale: 'en', photoUpdatedAt: null, companies: [] };
export const AGENT_EN: Me = {
  id: 2,
  email: 'lee@popdesk.test',
  name: 'Lee Agent',
  role: 'AGENT',
  locale: 'en',
  photoUpdatedAt: null,
  companies: [{ code: 'ACME', name: 'Acme' }],
};
export const ADMIN_PERSON_EN: Person = { accountId: 1, name: 'Morgan Admin', role: 'ADMIN', photoUpdatedAt: null };
export const AGENT_PERSON_EN: Person = { accountId: 2, name: 'Lee Agent', role: 'AGENT', photoUpdatedAt: null };
export const OTHER_AGENT_PERSON_EN: Person = { accountId: 3, name: 'Jordan Agent', role: 'AGENT', photoUpdatedAt: null };
export const CUSTOMER_PERSON_EN: Person = { accountId: 11, name: 'Kim Customer', role: 'CUSTOMER', photoUpdatedAt: null };

export const ACME_EN: Company = company({ code: 'ACME', name: 'Acme', legalName: 'Acme Inc.' });
export const BETA_EN: Company = company({ code: 'BETA', name: 'Beta Trading', legalName: 'Beta Trading LLC' });

export const ISSUE_TITLE_EN = 'Cannot sign in since yesterday';

export function issueEn(over: Partial<Issue> = {}): Issue {
  return issue({
    title: ISSUE_TITLE_EN,
    content: 'Since yesterday **sign-in** fails with a blank page.',
    reporter: CUSTOMER_PERSON_EN,
    companyCode: ACME_EN.code,
    companyName: ACME_EN.name,
    ...over,
  });
}

export function summaryEn(over: Partial<IssueSummary> = {}): IssueSummary {
  return summary({ title: ISSUE_TITLE_EN, reporter: CUSTOMER_PERSON_EN, companyCode: ACME_EN.code, companyName: ACME_EN.name, ...over });
}

export function commentEn(over: Partial<Comment> = {}): Comment {
  return comment({ author: CUSTOMER_PERSON_EN, content: 'Please take a look.', ...over });
}

export function attachmentEn(over: Partial<Attachment> = {}): Attachment {
  return attachment({ uploadedBy: CUSTOMER_PERSON_EN, fileName: 'log.txt', ...over });
}

export function accountEn(over: Partial<Account> = {}): Account {
  return account({ id: ADMIN_EN.id, email: ADMIN_EN.email, name: ADMIN_EN.name, ...over });
}

export function memberEn(over: Partial<CompanyMember> = {}): CompanyMember {
  return member({ accountId: 11, email: 'kim@acme.test', name: 'Kim Customer', ...over });
}

export function invitationEn(over: Partial<PendingInvitation> = {}): PendingInvitation {
  return invitation({ name: 'Park Invitee', companies: [{ code: ACME_EN.code, name: ACME_EN.name }], ...over });
}

export { T0 };

// ---------------------------------------------------------------------------
// Opening in English

/**
 * Signs in as `me` (an account whose language is English) with English data in the fake server.
 * Call it in the test or its `beforeEach`, after `console.ts` has reset the state.
 */
export function openInEnglish(me: Me = ADMIN_EN) {
  state.signedIn = true;
  state.me = me;
  state.companies = [ACME_EN, BETA_EN];
  state.issues = [summaryEn()];
  state.issue = issueEn();
  state.comments = [];
  state.attachments = [];
  state.accounts = [accountEn(), accountEn({ id: 2, email: AGENT_EN.email, name: AGENT_EN.name, role: 'AGENT' })];
  state.invitations = [];
  state.customers = [memberEn()];
  state.agents = [memberEn({ accountId: 2, email: AGENT_EN.email, name: AGENT_EN.name })];
}

/** The browser's language list, as `navigator.languages` and `navigator.language` give it. */
export function browserLanguages(languages: readonly string[], language = languages[0] ?? '') {
  Object.defineProperty(window.navigator, 'languages', { value: languages, configurable: true });
  Object.defineProperty(window.navigator, 'language', { value: language, configurable: true });
}

/** No session, and a browser whose first language is `languages[0]` (English by default). */
export function signedOutInEnglish(languages: readonly string[] = ['en-US']) {
  state.signedIn = false;
  browserLanguages(languages);
}

// ---------------------------------------------------------------------------
// The Hangul check (B5-AC-05)

const HANGUL = /[가-힣ㄱ-ㅎㅏ-ㅣ]/;
const ATTRIBUTES = ['aria-label', 'title', 'placeholder', 'alt'] as const;

/** Every piece of Korean under `root`: text nodes, and the attributes a screen reader or a pointer shows. */
export function hangulIn(root: Element = document.body): string[] {
  const found: string[] = [];
  const walk = (node: Node) => {
    if (node.nodeType === Node.TEXT_NODE) {
      const said = (node.textContent ?? '').replace(/\s+/g, ' ').trim();
      if (HANGUL.test(said)) found.push(`글 "${said}" (${where(node.parentElement)})`);
      return;
    }
    if (node instanceof Element) {
      if (node.tagName === 'SCRIPT' || node.tagName === 'STYLE') return;
      for (const name of ATTRIBUTES) {
        const value = node.getAttribute(name);
        if (value !== null && HANGUL.test(value)) found.push(`${name}="${value}" (${where(node)})`);
      }
    }
    node.childNodes.forEach(walk);
  };
  walk(root);
  return found;
}

function where(el: Element | null): string {
  if (!el) return '?';
  const role = el.getAttribute('role');
  return `<${el.tagName.toLowerCase()}${role ? ` role="${role}"` : ''}>`;
}

/** Fails, listing what it found, when `root` shows any Hangul. `what` names the screen and state. */
export function expectNoHangul(what: string, root: Element = document.body) {
  expect(hangulIn(root), `${what}: 영어 화면에 남은 한글`).toEqual([]);
}

/** `document.title` and `<html lang>` of an English screen (B5-AC-04). */
export function expectEnglishPage(what: string) {
  expect(document.documentElement.lang, `${what}: <html lang>`).toBe('en');
  expect(document.title, `${what}: document.title`).not.toBe('');
  expect(HANGUL.test(document.title) ? document.title : '', `${what}: document.title의 한글`).toBe('');
}
