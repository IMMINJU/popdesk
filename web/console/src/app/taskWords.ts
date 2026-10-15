// The sentence of a task's system entry (6b-tasks screens.md 3절). Pure: the 작업 구역 of C-4
// draws what this returns.
import type { Schemas } from '../api';
import { longDate } from './dueDates';
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
  const who = `${entry.author.name} 님이`;
  switch (entry.field) {
    case 'STATUS':
      if (entry.newValue === 'DONE') return { text: `${who} 작업을 완료했습니다` };
      if (entry.newValue === 'OPEN') return { text: `${who} 작업을 다시 열었습니다` };
      break;
    case 'ASSIGNEE':
      if (entry.newPerson === null) return { text: `${who} 담당자를 비웠습니다` };
      if (entry.oldPerson === null) return { text: `${who} 담당자를 ${toward(`${entry.newPerson.name} 님`)} 정했습니다` };
      return { text: `${who} 담당자를 ${entry.oldPerson.name} 님에서 ${toward(`${entry.newPerson.name} 님`)} 바꿨습니다` };
    case 'DUE_DATE':
      if (entry.newValue === null) return { text: `${who} 마감일을 지웠습니다` };
      if (entry.oldValue === null) return { text: `${who} 마감일을 ${toward(longDate(entry.newValue))} 정했습니다` };
      return { text: `${who} 마감일을 ${longDate(entry.oldValue)}에서 ${toward(longDate(entry.newValue))} 바꿨습니다` };
    case 'TITLE':
      return { text: `${who} 제목을 고쳤습니다`, titles: { before: entry.oldValue ?? '', after: entry.newValue ?? '' } };
    default:
      break;
  }
  return { text: `${who} 작업을 바꿨습니다` };
}
