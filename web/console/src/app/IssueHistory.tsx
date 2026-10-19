// C-4 이력 (3-issues screens.md 2절, S3-AC-80): folded, read when it opens, oldest
// first, 20 lines at a time. A change of status or assignee reads it again.
// Slice 6a (6a-labels screens.md 3절) adds the lines of labels and due dates, slice 6c those of links,
// slice 7a those of the trash.
import { Button, ISSUE_STATUS_WORDS, PRIORITY_WORDS, RelativeTime, type IssueStatus, type Priority } from '@popdesk/ui';
import { useEffect, useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { api, type Schemas } from '../api';
import { Disclosure } from './Disclosure';
import { longDate } from './dueDates';
import { LoadFailed } from './layout';
import { linkChangeText } from './linkWords';
import { screenLocale, t, tf } from './locale';
import { toward } from './particles';
import { call } from './result';
import { useToLogin } from './sessionContext';
import { trashHistoryText } from './trashWords';

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
  if (!person || person.name === null) return t('history.someone');
  return tf(person.role === 'CUSTOMER' ? 'history.customer' : 'history.person', { name: person.name });
}

/** The new value as the sentence takes it: Korean puts its particle after it, English says it as it is. */
function to(word: string): string {
  return screenLocale() === 'ko' ? toward(word) : word;
}

/** What one line says, and what it shows under itself. */
function lineOf(entry: Entry, onBehalf: boolean, fileName: (id: string | null) => string): { text: ReactNode; detail?: ReactNode } {
  // popdesk itself (account 0) has no name.
  const who = entry.actor.name === null ? t('inbox.system') : tf('history.subject', { person: called(entry.actor) });
  if (entry.kind === 'CREATED') return { text: tf(onBehalf ? 'history.createdOnBehalf' : 'history.created', { who }) };
  switch (entry.field) {
    case 'STATUS':
      return { text: tf('history.status', { who, from: statusWord(entry.oldValue), to: to(statusWord(entry.newValue)) }) };
    case 'PRIORITY':
      return { text: tf('history.priority', { who, from: priorityWord(entry.oldValue), to: to(priorityWord(entry.newValue)) }) };
    case 'ASSIGNEE':
      // The contract leaves both values null for this field: the people say what changed.
      if (entry.newPerson === null) return { text: tf('history.assigneeCleared', { who }) };
      if (entry.oldPerson === null) return { text: tf('history.assigneeSet', { who, to: to(called(entry.newPerson)) }) };
      return { text: tf('history.assigneeChanged', { who, from: called(entry.oldPerson), to: to(called(entry.newPerson)) }) };
    case 'TITLE':
      return {
        text: tf('history.titleEdited', { who }),
        detail: (
          <p className="pd-history-titles">
            <span className="pd-sr-only">{t('history.oldTitle')}</span>
            <span className="pd-muted">{entry.oldValue}</span>
            <span aria-hidden="true">→</span>
            <span className="pd-sr-only">{t('history.newTitle')}</span>
            <span>{entry.newValue}</span>
          </p>
        ),
      };
    case 'CONTENT':
      return {
        text: tf('history.contentEdited', { who }),
        detail: (
          <Disclosure label={t('history.oldContent')}>
            {/* As written, not as markdown: this is what was replaced. */}
            <pre className="pd-plain-text">{entry.oldValue}</pre>
          </Disclosure>
        ),
      };
    case 'ATTACHMENT':
      // The entry holds the ID alone (S5-AC-43): attached in `newValue`, deleted in `oldValue`.
      return entry.newValue !== null
        ? { text: tf('history.attached', { who, file: fileName(entry.newValue) }) }
        : { text: tf('history.attachmentDeleted', { who, file: fileName(entry.oldValue) }) };
    case 'LABEL':
      // The name the label had then (S6A-AC-05): put on in `newValue`, taken off in `oldValue`.
      return entry.newValue !== null ? { text: tf('history.labelAdded', { who, label: entry.newValue }) } : { text: tf('history.labelRemoved', { who, label: entry.oldValue ?? '' }) };
    case 'DUE_DATE':
      if (entry.newValue === null) return { text: tf('history.dueCleared', { who }) };
      if (entry.oldValue === null) return { text: tf('history.dueSet', { who, to: to(longDate(entry.newValue)) }) };
      return { text: tf('history.dueChanged', { who, from: longDate(entry.oldValue), to: to(longDate(entry.newValue)) }) };
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
    case 'TRASH':
      return { text: trashHistoryText(who, entry.oldValue, entry.newValue) };
    default:
      // A field of a later release.
      return { text: tf('history.changed', { who }) };
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
  const fileName = (id: string | null) => props.attachments.find((a) => a.id === id)?.fileName ?? t('history.deletedFile');

  return (
    <Disclosure label={t('history.heading')} kind="section" onToggle={setOpen}>
      {answer === null && <p className="pd-muted">{t('history.loading')}</p>}
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
            {t('history.more')}
          </Button>
        </div>
      )}
    </Disclosure>
  );
}
