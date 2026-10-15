// C-4 이력 (3-issues screens.md 2절, S3-AC-80): folded, read when it opens, oldest
// first, 20 lines at a time. A change of status or assignee reads it again.
// Slice 6a (6a-labels screens.md 3절) adds the lines of labels and due dates, slice 6c those of links.
import { Button, ISSUE_STATUS_WORDS, PRIORITY_WORDS, RelativeTime, type IssueStatus, type Priority } from '@popdesk/ui';
import { useEffect, useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { api, type Schemas } from '../api';
import { Disclosure } from './Disclosure';
import { longDate } from './dueDates';
import { LoadFailed } from './layout';
import { linkChangeText } from './linkWords';
import { toward } from './particles';
import { call } from './result';
import { useToLogin } from './sessionContext';

type Entry = Schemas['IssueHistoryEntry'];
type Person = Schemas['Person'];

const PAGE = 20;

function statusWord(value: string | null): string {
  return value !== null && value in ISSUE_STATUS_WORDS ? ISSUE_STATUS_WORDS[value as IssueStatus].agent : (value ?? '');
}

function priorityWord(value: string | null): string {
  return value !== null && value in PRIORITY_WORDS ? PRIORITY_WORDS[value as Priority] : (value ?? '');
}

/** "김고객 (고객) 님": a customer is marked, staff is not. */
function called(person: { name: string | null; role: Person['role'] | null } | null): string {
  if (!person || person.name === null) return '다른 사람';
  return `${person.name}${person.role === 'CUSTOMER' ? ' (고객)' : ''} 님`;
}

/** What one line says, and what it shows under itself. */
function lineOf(entry: Entry, onBehalf: boolean, fileName: (id: string | null) => string): { text: ReactNode; detail?: ReactNode } {
  // popdesk itself (account 0) has no name.
  const who = entry.actor.name === null ? '시스템이' : `${called(entry.actor)}이`;
  if (entry.kind === 'CREATED') return { text: onBehalf ? `${who} 대리 등록했습니다` : `${who} 문의를 올렸습니다` };
  switch (entry.field) {
    case 'STATUS':
      return { text: `${who} 상태를 ${statusWord(entry.oldValue)}에서 ${toward(statusWord(entry.newValue))} 바꿨습니다` };
    case 'PRIORITY':
      return { text: `${who} 우선순위를 ${priorityWord(entry.oldValue)}에서 ${toward(priorityWord(entry.newValue))} 바꿨습니다` };
    case 'ASSIGNEE':
      // The contract leaves both values null for this field: the people say what changed.
      if (entry.newPerson === null) return { text: `${who} 담당자를 비웠습니다` };
      if (entry.oldPerson === null) return { text: `${who} 담당자를 ${called(entry.newPerson)}으로 정했습니다` };
      return { text: `${who} 담당자를 ${called(entry.oldPerson)}에서 ${called(entry.newPerson)}으로 바꿨습니다` };
    case 'TITLE':
      return {
        text: `${who} 제목을 고쳤습니다`,
        detail: (
          <p className="pd-history-titles">
            <span className="pd-sr-only">전 제목</span>
            <span className="pd-muted">{entry.oldValue}</span>
            <span aria-hidden="true">→</span>
            <span className="pd-sr-only">새 제목</span>
            <span>{entry.newValue}</span>
          </p>
        ),
      };
    case 'CONTENT':
      return {
        text: `${who} 내용을 고쳤습니다`,
        detail: (
          <Disclosure label="전 내용 보기">
            {/* As written, not as markdown: this is what was replaced. */}
            <pre className="pd-plain-text">{entry.oldValue}</pre>
          </Disclosure>
        ),
      };
    case 'ATTACHMENT':
      // The entry holds the ID alone (S5-AC-43): attached in `newValue`, deleted in `oldValue`.
      return entry.newValue !== null
        ? { text: `${who} 첨부를 붙임: ${fileName(entry.newValue)}` }
        : { text: `${who} 첨부를 지움: ${fileName(entry.oldValue)}` };
    case 'LABEL':
      // The name the label had then (S6A-AC-05): put on in `newValue`, taken off in `oldValue`.
      return entry.newValue !== null ? { text: `${who} 라벨을 붙임: ${entry.newValue}` } : { text: `${who} 라벨을 뗌: ${entry.oldValue ?? ''}` };
    case 'DUE_DATE':
      if (entry.newValue === null) return { text: `${who} 마감일을 지웠습니다` };
      if (entry.oldValue === null) return { text: `${who} 마감일을 ${toward(longDate(entry.newValue))} 정했습니다` };
      return { text: `${who} 마감일을 ${longDate(entry.oldValue)}에서 ${toward(longDate(entry.newValue))} 바꿨습니다` };
    case 'LINK': {
      // Slice 6c (6c-links screens.md 2절): the other issue's key leads to it, its title is under the line.
      const said = linkChangeText(who, entry.oldLink, entry.newLink);
      if ('text' in said) return said;
      return {
        text: (
          <>
            {said.before}
            <Link to={`/issues/${encodeURIComponent(said.key)}`}>{said.key}</Link>
            {said.after}
          </>
        ),
        detail: <p className="pd-muted">{said.title}</p>,
      };
    }
    default:
      // A field of a later release.
      return { text: `${who} 문의를 바꿨습니다` };
  }
}

export interface IssueHistoryProps {
  issueKey: string;
  /** A person filed the issue for the customer: the first line says so. */
  onBehalf: boolean;
  /** A new number reads the open history again. */
  version: number;
  /** The issue's attachments as listed now: an attachment line takes its file name from here. */
  attachments: readonly { id: string; fileName: string | null }[];
}

export function IssueHistory(props: IssueHistoryProps) {
  const toLogin = useToLogin();
  const { issueKey, version } = props;
  const [open, setOpen] = useState(false);
  const [pages, setPages] = useState(1);
  const [retries, setRetries] = useState(0);
  const [answer, setAnswer] = useState<{ items: Entry[]; totalPages: number; failed: boolean } | null>(null);

  useEffect(() => {
    if (!open) return undefined;
    // An object, since the loop below reads it after each wait.
    const now = { current: true };
    void (async () => {
      const items: Entry[] = [];
      let totalPages = 1;
      // Every page shown so far, so the lines under 더 보기 stay.
      for (let page = 1; page <= pages; page += 1) {
        const result = await call(api.GET('/api/console/issues/{key}/history', { params: { path: { key: issueKey }, query: { page, size: PAGE } } }));
        if (!now.current) return;
        if (!result.ok) {
          if (result.failure.kind === 'unauthenticated') toLogin();
          else setAnswer((before) => ({ items: before?.items ?? [], totalPages: before?.totalPages ?? 1, failed: true }));
          return;
        }
        items.push(...result.data.items);
        totalPages = result.data.totalPages;
      }
      setAnswer({ items, totalPages, failed: false });
    })();
    return () => {
      now.current = false;
    };
    // toLogin changes with the address; the history does not depend on it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, issueKey, pages, version, retries]);

  // A deleted attachment has no name any more, and one that is not listed has none to show.
  const fileName = (id: string | null) => props.attachments.find((a) => a.id === id)?.fileName ?? '지워진 파일';

  return (
    <Disclosure label="이력" kind="section" onToggle={setOpen}>
      {answer === null && <p className="pd-muted">불러오는 중…</p>}
      {answer?.failed && (
        <LoadFailed
          onRetry={() => {
            setRetries((n) => n + 1);
          }}
        />
      )}
      {answer && answer.items.length > 0 && (
        <ol className="pd-history">
          {answer.items.map((entry) => {
            const line = lineOf(entry, props.onBehalf, fileName);
            return (
              <li key={entry.id}>
                <p>
                  <span>{line.text}</span> <RelativeTime dateTime={entry.createdAt} />
                </p>
                {line.detail}
              </li>
            );
          })}
        </ol>
      )}
      {answer && !answer.failed && pages < answer.totalPages && (
        <div>
          <Button
            variant="secondary"
            size="sm"
            onClick={() => {
              setPages((n) => n + 1);
            }}
          >
            더 보기
          </Button>
        </div>
      )}
    </Disclosure>
  );
}
