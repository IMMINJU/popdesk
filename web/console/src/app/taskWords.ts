// The sentence of a task's system entry (6b-tasks screens.md 3절). Pure: the 작업 구역 of C-4
// draws what this returns.
import type { Schemas } from '../api';
import { longDate } from './dueDates';
import { screenLocale, tf } from './locale';
import { toward } from './particles';

type TaskNote = Schemas['TaskNote'];

/** The parts of a `CHANGE` entry the sentence is made of. */
export type TaskChange = Pick<TaskNote, 'author' | 'field' | 'oldValue' | 'newValue' | 'oldPerson' | 'newPerson'>;

/** What one entry says: a sentence, and for a title the title before and after it. */
export interface TaskChangeText {
  text: string;
  titles?: { before: string; after: string };
}

/**
 * The sentence of one `CHANGE` entry of a task.
 *
 * Takes `field` as the server gives it: `STATUS` (`newValue` `DONE` or `OPEN`), `ASSIGNEE`
 * (`oldPerson`, `newPerson`, either null), `DUE_DATE` (`oldValue`, `newValue` as `YYYY-MM-DD` or
 * null), `TITLE` (`oldValue`, `newValue` the titles), and any other string or null, which a later
 * release may send.
 *
 * Returns "{누가} 님이 …" for each of them: 작업을 완료했습니다 / 다시 열었습니다, 담당자를 … 정했습니다
 * / 바꿨습니다 / 비웠습니다, 마감일을 … 정했습니다 / 바꿨습니다 / 지웠습니다 (dates as 2026년 10월 20일),
 * 제목을 고쳤습니다 with `titles`, and 작업을 바꿨습니다 for a field or status it does not know.
 */
export function taskChangeText(entry: TaskChange): TaskChangeText {
  const who = tf('inbox.who', { name: entry.author.name });
  // Korean puts its particle after the new value; English says the value as it is.
  const to = (word: string) => (screenLocale() === 'ko' ? toward(word) : word);
  const person = (name: string) => tf('history.person', { name });
  switch (entry.field) {
    case 'STATUS':
      if (entry.newValue === 'DONE') return { text: tf('issueTasks.changeDone', { who }) };
      if (entry.newValue === 'OPEN') return { text: tf('issueTasks.changeReopened', { who }) };
      break;
    case 'ASSIGNEE':
      if (entry.newPerson === null) return { text: tf('history.assigneeCleared', { who }) };
      if (entry.oldPerson === null) return { text: tf('history.assigneeSet', { who, to: to(person(entry.newPerson.name)) }) };
      return { text: tf('history.assigneeChanged', { who, from: person(entry.oldPerson.name), to: to(person(entry.newPerson.name)) }) };
    case 'DUE_DATE':
      if (entry.newValue === null) return { text: tf('history.dueCleared', { who }) };
      if (entry.oldValue === null) return { text: tf('history.dueSet', { who, to: to(longDate(entry.newValue)) }) };
      return { text: tf('history.dueChanged', { who, from: longDate(entry.oldValue), to: to(longDate(entry.newValue)) }) };
    case 'TITLE':
      return { text: tf('history.titleEdited', { who }), titles: { before: entry.oldValue ?? '', after: entry.newValue ?? '' } };
    default:
      break;
  }
  return { text: tf('issueTasks.changeOther', { who }) };
}
