// The words of links between issues (6c-links screens.md 1절, 2절, 3절): the kind of a link in the
// 연결 구역, the radios of "문의 잇기", the history sentence of a `LINK` entry, and the inbox sentence
// of a linked issue that was resolved or closed. Pure: the same input gives the same words (the
// inbox sentence also reads the console's language).
import type { Schemas } from '../api';
import { t, texts, tf } from './locale';

export type LinkType = Schemas['IssueLink']['type'];
type HistoryLink = Schemas['IssueHistoryLink'];
type NotificationIssue = Schemas['NotificationIssue'];

/** How the 연결 구역 names the kind of a link, seen from the issue shown. */
export const LINK_KIND_WORDS: Readonly<Record<LinkType, string>> = texts({
  DUPLICATE_OF: 'issueLinks.kindDuplicateOf',
  DUPLICATED_BY: 'issueLinks.kindDuplicatedBy',
  RELATED: 'issueLinks.related',
});

const CHOICE_WORDS = texts({
  RELATED: 'issueLinks.related',
  DUPLICATE_OF: 'issueLinks.choiceDuplicateOf',
  DUPLICATED_BY: 'issueLinks.choiceDuplicatedBy',
});

/** The radios of "문의 잇기", in the order they are drawn; the first is the default. */
export const LINK_TYPE_CHOICES: readonly { value: LinkType; readonly label: string }[] = (['RELATED', 'DUPLICATE_OF', 'DUPLICATED_BY'] as const).map((value) => ({
  value,
  get label() {
    return CHOICE_WORDS[value];
  },
}));

/**
 * The history sentence of a `LINK` entry: either plain text, or the text around the other
 * issue's key (`before` key `after`) with that issue's title to show under it.
 */
export type LinkChangeText = { text: string } | { before: string; key: string; after: string; title: string };

function known(type: string): type is LinkType {
  return Object.hasOwn(LINK_KIND_WORDS, type);
}

/**
 * The sentence of a `LINK` history entry (screens.md 2절).
 *
 * `who` is the subject as the history says it: "{이름} 님이" or "시스템이". `oldLink` is the link
 * removed and `newLink` the link made; the contract sets one of them. Any `type` string is taken,
 * also one this build does not know.
 *
 * Returns the sentence around the key when exactly one is set and its type is known: made
 * `DUPLICATE_OF` "{who} 이 문의를 {키}의 중복으로 이었습니다", made `DUPLICATED_BY` "{who} {키}를 이
 * 문의의 중복으로 이었습니다", made `RELATED` "{who} {키}와 관련 문의로 이었습니다", removed (any
 * kind) "{who} {키}와의 연결을 끊었습니다". Otherwise the plain "{who} 연결을 바꿨습니다". The
 * particles after the key are fixed ("를", "와"): how a key ending in a number is read varies.
 */
export function linkChangeText(who: string, oldLink: HistoryLink | null, newLink: HistoryLink | null): LinkChangeText {
  /** The sentence of `key` split at the other issue's key, `who` filled in. */
  const around = (key: Parameters<typeof t>[0], issue: { key: string; title: string }): LinkChangeText => {
    const [before = '', after = ''] = t(key).split('{key}');
    const fill = (part: string) => part.replace('{who}', () => who);
    return { before: fill(before), key: issue.key, after: fill(after), title: issue.title };
  };
  if (newLink !== null && oldLink === null && known(newLink.type)) {
    switch (newLink.type) {
      case 'DUPLICATE_OF':
        return around('issueLinks.madeDuplicateOf', newLink.issue);
      case 'DUPLICATED_BY':
        return around('issueLinks.madeDuplicatedBy', newLink.issue);
      case 'RELATED':
        return around('issueLinks.madeRelated', newLink.issue);
    }
  }
  if (oldLink !== null && newLink === null && known(oldLink.type)) {
    return around('issueLinks.removed', oldLink.issue);
  }
  return { text: tf('issueLinks.changed', { who }) };
}

/**
 * The inbox sentence of `LINKED_ISSUE_RESOLVED` and `LINKED_ISSUE_CLOSED` (screens.md 3절).
 *
 * `who` is `inbox.who` with the name of who did it, or `inbox.system`. `linked` is the issue that
 * was resolved or closed, by its key and title now; null (the contract sets it for these kinds)
 * leaves the key and title empty.
 *
 * Returns, in Korean, "{who} 연결된 문의를 해결했습니다: {키} {제목}" for `RESOLVED` and the same
 * with "종료했습니다" for `CLOSED`, in the language of the console.
 */
export function linkedIssueText(kind: 'LINKED_ISSUE_RESOLVED' | 'LINKED_ISSUE_CLOSED', who: string, linked: NotificationIssue | null): string {
  const values = { who, key: linked?.key ?? '', title: linked?.title ?? '' };
  return kind === 'LINKED_ISSUE_RESOLVED' ? tf('inbox.linkedIssueResolved', values) : tf('inbox.linkedIssueClosed', values);
}
