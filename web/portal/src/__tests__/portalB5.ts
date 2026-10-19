// Test support for slice b5 (design/slices/b5-english): opening the portal in English.
// `portal.ts` stays as it is and its tests stay in Korean (acceptance.md 4절 5). This file adds
// English fixtures, so the Hangul check never trips on data, and the check itself.
//
// - Signed in, the screen language is the account's `Me.locale` (B5-AC-01): `openInEnglish`.
//   The `me` answer comes from portal1a.ts (`session.me`).
// - Signed out, it is the browser's first language (B5-AC-02): `signedOutInEnglish`.
// - `expectNoHangul` looks at the text and at `aria-label`, `title`, `placeholder` and `alt` of
//   every element, and names what it found in the failure message (B5-AC-05).
import { expect } from 'vitest';
import {
  ME,
  T0,
  attachment,
  comment,
  issue,
  state,
  summary,
  type Attachment,
  type Comment,
  type Company,
  type Issue,
  type IssueSummary,
  type Me,
  type Person,
} from './portal';
import { session } from './portal1a';

// ---------------------------------------------------------------------------
// English fixtures

export const ACME_EN: Company = { code: 'ACME', name: 'Acme', status: 'ACTIVE', manager: false };
export const BETA_EN: Company = { code: 'BETA', name: 'Beta Trading', status: 'ACTIVE', manager: false };

export const ME_EN: Me = { ...ME, name: 'Kim Customer', locale: 'en', companies: [ACME_EN] };
export const ME_PERSON_EN: Person = { accountId: 11, kind: 'CUSTOMER', name: 'Kim Customer', photoUpdatedAt: null };
export const COLLEAGUE_EN: Person = { accountId: 12, kind: 'CUSTOMER', name: 'Park Colleague', photoUpdatedAt: null };

export const ISSUE_TITLE_EN = 'Cannot sign in since yesterday';

export function issueEn(over: Partial<Issue> = {}): Issue {
  return issue({
    title: ISSUE_TITLE_EN,
    content: 'Since yesterday **sign-in** fails with a blank page.',
    reporter: ME_PERSON_EN,
    companyCode: ACME_EN.code,
    companyName: ACME_EN.name,
    ...over,
  });
}

export function summaryEn(over: Partial<IssueSummary> = {}): IssueSummary {
  return summary({ title: ISSUE_TITLE_EN, reporter: ME_PERSON_EN, companyCode: ACME_EN.code, companyName: ACME_EN.name, ...over });
}

export function commentEn(over: Partial<Comment> = {}): Comment {
  return comment({ author: ME_PERSON_EN, content: 'Please take a look.', ...over });
}

export function attachmentEn(over: Partial<Attachment> = {}): Attachment {
  return attachment({ uploadedBy: ME_PERSON_EN, fileName: 'log.txt', ...over });
}

export { T0 };

// ---------------------------------------------------------------------------
// Opening in English

/**
 * Signs in as `me` (an account whose language is English) with English data in the fake server.
 * Call it in the test or its `beforeEach`, after `portal.ts` and `portal1a.ts` have reset the state.
 */
export function openInEnglish(me: Me = ME_EN) {
  state.signedIn = true;
  session.me = me;
  state.companies = me.companies;
  state.issues = [summaryEn()];
  state.issue = issueEn();
  state.comments = [];
  state.attachments = [];
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
