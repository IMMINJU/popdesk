// The words of links between issues (6c-links screens.md 1절, 2절, 3절): the kind of a link in the
// 연결 구역, the radios of "문의 잇기", the history sentence of a `LINK` entry, and the inbox sentence
// of a linked issue that was resolved or closed. Pure: the same input gives the same words (the
// inbox sentence also reads the console's language).
import type { Schemas } from '../api';
import { tf } from './locale';

export type LinkType = Schemas['IssueLink']['type'];
type HistoryLink = Schemas['IssueHistoryLink'];
type NotificationIssue = Schemas['NotificationIssue'];

/** How the 연결 구역 names the kind of a link, seen from the issue shown. */
export const LINK_KIND_WORDS: Readonly<Record<LinkType, string>> = {
  DUPLICATE_OF: '이 문의의 원본',
  DUPLICATED_BY: '이 문의의 중복',
  RELATED: '관련',
};

/** The radios of "문의 잇기", in the order they are drawn; the first is the default. */
export const LINK_TYPE_CHOICES: readonly { value: LinkType; label: string }[] = [
  { value: 'RELATED', label: '관련' },
  { value: 'DUPLICATE_OF', label: '이 문의가 찾은 문의의 중복' },
  { value: 'DUPLICATED_BY', label: '찾은 문의가 이 문의의 중복' },
];

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
  const plain = { text: `${who} 연결을 바꿨습니다` };
  if (newLink !== null && oldLink === null && known(newLink.type)) {
    const { key, title } = newLink.issue;
    switch (newLink.type) {
      case 'DUPLICATE_OF':
        return { before: `${who} 이 문의를 `, key, after: '의 중복으로 이었습니다', title };
      case 'DUPLICATED_BY':
        return { before: `${who} `, key, after: '를 이 문의의 중복으로 이었습니다', title };
      case 'RELATED':
        return { before: `${who} `, key, after: '와 관련 문의로 이었습니다', title };
    }
  }
  if (oldLink !== null && newLink === null && known(oldLink.type)) {
    return { before: `${who} `, key: oldLink.issue.key, after: '와의 연결을 끊었습니다', title: oldLink.issue.title };
  }
  return plain;
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
