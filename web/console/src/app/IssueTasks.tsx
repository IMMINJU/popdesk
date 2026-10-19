// C-4 작업 구역 (6b-tasks screens.md 1절): the tasks of an issue in the order they were made, their
// notes and system entries, and what staff do with them. The list is read once with the issue and
// again after a delete or an answer that says it changed meanwhile; any other answer is the task
// as the server has it now and replaces its line.
//
// Late answers (S6B-AC-89): the screen is mounted anew for every issue, so an answer for the issue
// left behind finds nothing to change. An answer that comes after the assignee dialog or the due
// date picker closed redraws its task when it succeeded, and is dropped when it failed.
import { Alert, Badge, Button, CheckIcon, codePoints, ConfirmDialog, DueDatePicker, IconButton, RelativeTime, RowMenu, TextField, type RowMenuItem } from '@popdesk/ui';
import { useId, useLayoutEffect, useRef, useState, type SubmitEvent } from 'react';
import { useLocation } from 'react-router';
import { api, type Schemas } from '../api';
import { longDate, todayInBrowser } from './dueDates';
import { useLive } from './hooks';
import { AssigneeDialog } from './IssueAssigneeDialog';
import type { IssueTasksState } from './taskList';
import { LoadFailed } from './layout';
import { t, texts, tf } from './locale';
import { failureText, rateLimitedText, TEXT } from './messages';
import { call, type Failure, type Result } from './result';
import { useSession, useToLogin } from './sessionContext';
import { taskChangeText } from './taskWords';

type Issue = Schemas['Issue'];
type Task = Schemas['Task'];
type TaskNote = Schemas['TaskNote'];
type Person = Schemas['Person'];

const TASK_TEXT = texts({
  failed: 'issueTasks.failed',
  noteFailed: 'issueTasks.noteFailed',
  taskLimit: 'issueTasks.taskLimit',
  noteLimit: 'issueTasks.noteLimit',
  past: 'issueTasks.pastDate',
});
/** Tasks of an issue that are not deleted, and all of them with the deleted ones (S6B-AC-08). */
const LIVE_TASK_LIMIT = 30;
const ALL_TASK_LIMIT = 100;
const NOTE_MAX = 4000;

/** The notes people wrote and did not delete: "기록 n" and the question before deleting a task. */
function noteCount(task: Task): number {
  return task.notes.filter((n) => n.kind === 'NOTE' && !n.deleted).length;
}

function taskFailureText(failure: Failure): string {
  if (failure.kind === 'rejected' && failure.code === 'TASK_LIMIT_EXCEEDED') return TASK_TEXT.taskLimit;
  if (failure.kind === 'rateLimited') return rateLimitedText(failure.seconds);
  return TASK_TEXT.failed;
}

function noteFailureText(failure: Failure): string {
  if (failure.kind === 'rejected' && failure.code === 'TASK_NOTE_LIMIT_EXCEEDED') return TASK_TEXT.noteLimit;
  if (failure.kind === 'rateLimited') return rateLimitedText(failure.seconds);
  return TASK_TEXT.noteFailed;
}

function dueFailureText(failure: Failure): string {
  if (failure.kind === 'invalid' && failure.fields.some((f) => f.field === 'dueDate')) return TASK_TEXT.past;
  return taskFailureText(failure);
}

export interface IssueTasksProps {
  issue: Issue;
  tasks: IssueTasksState;
  /** A change was refused because the issue changed meanwhile (it was closed). */
  onStale: () => void;
}

export function IssueTasks(props: IssueTasksProps) {
  const { issue, tasks } = props;
  const { me } = useSession();
  const live = useLive();
  const toLogin = useToLogin();
  const headingId = useId();
  const closed = issue.status === 'CLOSED';
  const isAdmin = me?.role === 'ADMIN';
  const items = tasks.list?.items ?? [];

  // Opened and closed by hand; a task the address names (#task-ID) starts opened.
  const hash = useLocation().hash;
  const arrivedId = Number(/^#task-(\d+)$/.exec(hash)?.[1] ?? NaN);
  const [toggled, setToggled] = useState<Record<number, boolean>>({});
  const isOpen = (t: Task) => !t.deleted && (toggled[t.id] ?? t.id === arrivedId);
  const scrolledFor = useRef<string | null>(null);
  useLayoutEffect(() => {
    if (scrolledFor.current === hash || tasks.list === null) return;
    scrolledFor.current = hash;
    const el = Number.isNaN(arrivedId) ? null : document.getElementById(`task-${String(arrivedId)}`);
    // jsdom has none.
    if (el && typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'center' });
  });

  // One title field at a time: the new task's, or the one of a task being renamed.
  const [adding, setAdding] = useState(false);
  const [renaming, setRenaming] = useState<number | null>(null);
  const [assigning, setAssigning] = useState<{ task: Task; opening: number } | null>(null);
  const openings = useRef(0);
  const [removing, setRemoving] = useState<Task | null>(null);
  const [removingNote, setRemovingNote] = useState<{ task: Task; note: TaskNote } | null>(null);
  const [removePending, setRemovePending] = useState(false);
  const [removeError, setRemoveError] = useState<string | undefined>(undefined);
  const removeAsk = useRef(0);

  /** What every task request does with a failure besides showing it. */
  const onFailure = (failure: Failure) => {
    if (failure.kind === 'unauthenticated') toLogin();
    else if (failure.kind === 'rejected' && (failure.code === 'TASK_DELETED' || failure.code === 'TASK_NOTE_DELETED')) void tasks.read();
    else if (failure.kind === 'rejected' && failure.code === 'ISSUE_CLOSED') {
      props.onStale();
      void tasks.read();
    }
  };

  /** Sends a change of `task`; a success replaces its line, wherever the request came from. */
  const update = async (task: Task, body: Schemas['UpdateTaskRequest']): Promise<Result<Task>> => {
    const result = await call(api.PATCH('/api/console/issues/{key}/tasks/{id}', { params: { path: { key: issue.key, id: task.id } }, body }));
    if (result.ok) tasks.put(result.data);
    else onFailure(result.failure);
    return result;
  };

  const closeRemove = () => {
    removeAsk.current += 1;
    setRemoving(null);
    setRemovingNote(null);
    setRemovePending(false);
    setRemoveError(undefined);
  };

  const confirmRemove = async () => {
    if (removePending || (removing === null && removingNote === null)) return;
    const mine = removeAsk.current;
    setRemovePending(true);
    setRemoveError(undefined);
    const result: Result<unknown> = removingNote
      ? await call(api.DELETE('/api/console/issues/{key}/tasks/{id}/notes/{noteId}', { params: { path: { key: issue.key, id: removingNote.task.id, noteId: removingNote.note.id } } }))
      : await call(api.DELETE('/api/console/issues/{key}/tasks/{id}', { params: { path: { key: issue.key, id: removing?.id ?? 0 } } }));
    if (!live() || mine !== removeAsk.current) return;
    if (result.ok || result.failure.kind === 'notFound') {
      closeRemove();
      // The line or note that had focus is a trace now: focus goes to the section.
      document.getElementById(headingId)?.focus();
      void tasks.read();
    } else if (result.failure.kind === 'unauthenticated') {
      toLogin();
    } else {
      setRemovePending(false);
      setRemoveError(failureText(result.failure));
    }
  };

  const live30 = items.filter((t) => !t.deleted).length;
  const full = live30 >= LIVE_TASK_LIMIT || items.length >= ALL_TASK_LIMIT;
  const openCount = tasks.list?.openCount ?? 0;

  return (
    <>
      <section className="pd-detail-tasks" aria-labelledby={headingId}>
        <h2 id={headingId} className="pd-section-title" tabIndex={-1}>
          {openCount > 0 ? tf('issueTasks.headingCount', { open: openCount, all: live30 }) : t('issueTasks.title')}
        </h2>
        {tasks.list === null && tasks.failed && (
          <LoadFailed
            onRetry={() => {
              void tasks.read();
            }}
          />
        )}
        {tasks.list !== null &&
          (items.length === 0 ? (
            <p className="pd-muted">{t('issueTasks.empty')}</p>
          ) : (
            <ul className="pd-task-list">
              {items.map((t) => (
                <TaskLine
                  key={t.id}
                  task={t}
                  issue={issue}
                  canWrite={!closed}
                  canRemove={isAdmin || t.createdBy.accountId === me?.id}
                  canRemoveNote={(n) => isAdmin || n.author.accountId === me?.id}
                  myId={me?.id ?? null}
                  open={isOpen(t)}
                  onToggle={() => {
                    setToggled((now) => ({ ...now, [t.id]: !isOpen(t) }));
                  }}
                  renaming={renaming === t.id}
                  onRename={(on) => {
                    if (on) setAdding(false);
                    setRenaming(on ? t.id : null);
                  }}
                  update={update}
                  onFailure={onFailure}
                  put={tasks.put}
                  onAssign={() => {
                    openings.current += 1;
                    setAssigning({ task: t, opening: openings.current });
                  }}
                  onRemove={() => {
                    setRemoving(t);
                  }}
                  onRemoveNote={(n) => {
                    setRemovingNote({ task: t, note: n });
                  }}
                />
              ))}
            </ul>
          ))}
        {tasks.list !== null && !closed && (
          <TaskAdd
            issueKey={issue.key}
            full={full}
            adding={adding}
            onAdding={(on) => {
              if (on) setRenaming(null);
              setAdding(on);
            }}
            onFailure={onFailure}
            put={tasks.put}
          />
        )}
      </section>
      {assigning !== null && (
        <AssigneeDialog
          key={assigning.opening}
          title={t('issueTasks.changeAssignee')}
          companyCode={issue.companyCode}
          assignee={assigning.task.assignee}
          save={(person: Person | null) => update(assigning.task, { assigneeId: person?.accountId ?? null })}
          onClose={() => {
            setAssigning(null);
          }}
          onChanged={() => {
            setAssigning(null);
          }}
        />
      )}
      <ConfirmDialog
        open={removing !== null}
        tone="danger"
        title={t('issueTasks.deleteTitle')}
        message={tf('issueTasks.deleteMessage', { count: removing ? noteCount(removing) : 0 })}
        confirmLabel={t('issueTasks.delete')}
        cancelLabel={t('comment.keep')}
        pending={removePending}
        pendingLabel={t('comment.deleting')}
        {...(removeError === undefined ? {} : { error: removeError })}
        onConfirm={() => {
          void confirmRemove();
        }}
        onCancel={closeRemove}
      />
      <ConfirmDialog
        open={removingNote !== null}
        tone="danger"
        title={t('issueTasks.deleteNoteTitle')}
        message={t('issueTasks.deleteNoteMessage')}
        confirmLabel={t('issueTasks.delete')}
        cancelLabel={t('comment.keep')}
        pending={removePending}
        pendingLabel={t('comment.deleting')}
        {...(removeError === undefined ? {} : { error: removeError })}
        onConfirm={() => {
          void confirmRemove();
        }}
        onCancel={closeRemove}
      />
    </>
  );
}

/** "작업 더하기", and once pressed the title field with "더하기" and "취소". */
function TaskAdd(props: {
  issueKey: string;
  full: boolean;
  adding: boolean;
  onAdding: (on: boolean) => void;
  onFailure: (failure: Failure) => void;
  put: (task: Task) => void;
}) {
  const live = useLive();
  const [draft, setDraft] = useState('');
  const input = useRef<HTMLInputElement>(null);
  // Pressing "작업 더하기" puts the cursor in the title field.
  useLayoutEffect(() => {
    if (props.adding) input.current?.focus();
  }, [props.adding]);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const send = async () => {
    const title = draft.trim();
    if (pending || title === '') return;
    setPending(true);
    setError(null);
    const result = await call(api.POST('/api/console/issues/{key}/tasks', { params: { path: { key: props.issueKey } }, body: { title } }));
    if (result.ok) props.put(result.data);
    else props.onFailure(result.failure);
    if (!live()) return;
    setPending(false);
    if (result.ok) setDraft('');
    else setError(taskFailureText(result.failure));
  };

  if (props.adding && !props.full) {
    return (
      <form
        className="pd-task-add"
        aria-label={t('issueTasks.add')}
        noValidate
        onSubmit={(e: SubmitEvent) => {
          e.preventDefault();
          void send();
        }}
      >
        <TextField ref={input} label={t('issueTasks.taskTitle')} value={draft} onChange={setDraft} maxLength={100} />
        {error !== null && <Alert tone="error">{error}</Alert>}
        <div className="pd-form-actions">
          <Button type="submit" size="sm" pending={pending}>
            {t('issueTasks.addSubmit')}
          </Button>
          <Button
            variant="secondary"
            size="sm"
            onClick={() => {
              setDraft('');
              setError(null);
              props.onAdding(false);
            }}
          >
            {TEXT.cancel}
          </Button>
        </div>
      </form>
    );
  }
  return (
    <div className="pd-task-add">
      {props.full && <p className="pd-muted">{TASK_TEXT.taskLimit}</p>}
      <div>
        <Button
          variant="secondary"
          size="sm"
          blocked={props.full}
          onClick={() => {
            if (!props.full) props.onAdding(true);
          }}
        >
          {t('issueTasks.add')}
        </Button>
      </div>
    </div>
  );
}

interface TaskLineProps {
  task: Task;
  issue: Issue;
  /** Not `CLOSED`: the task can be changed and get notes. */
  canWrite: boolean;
  /** The creator or an admin: "지우기" of the task. */
  canRemove: boolean;
  canRemoveNote: (note: TaskNote) => boolean;
  myId: number | null;
  open: boolean;
  onToggle: () => void;
  renaming: boolean;
  onRename: (on: boolean) => void;
  update: (task: Task, body: Schemas['UpdateTaskRequest']) => Promise<Result<Task>>;
  onFailure: (failure: Failure) => void;
  put: (task: Task) => void;
  onAssign: () => void;
  onRemove: () => void;
  onRemoveNote: (note: TaskNote) => void;
}

function TaskLine(props: TaskLineProps) {
  const { task } = props;
  const live = useLive();
  const panelId = useId();
  const [statusPending, setStatusPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (task.deleted) {
    return (
      <li className="pd-task" data-deleted="true">
        <p className="pd-task-trace">
          <span>{t('issueTasks.deletedTask')}</span>
          {task.deletedBy && <span>{tf('issueTasks.deletedBy', { name: task.deletedBy.name })}</span>}
          {task.deletedAt && <RelativeTime dateTime={task.deletedAt} />}
        </p>
      </li>
    );
  }

  const done = task.status === 'DONE';
  const count = noteCount(task);

  const toggleStatus = async () => {
    if (statusPending) return;
    setStatusPending(true);
    setError(null);
    const result = await props.update(task, { status: done ? 'OPEN' : 'DONE' });
    if (!live()) return;
    setStatusPending(false);
    if (!result.ok) setError(taskFailureText(result.failure));
  };

  const menu: RowMenuItem[] = [
    ...(props.canWrite
      ? [
          {
            label: t('issueTasks.edit'),
            onSelect: () => {
              setError(null);
              props.onRename(true);
            },
          },
        ]
      : []),
    ...(props.canRemove ? [{ label: t('issueTasks.delete'), tone: 'danger' as const, onSelect: props.onRemove }] : []),
  ];

  return (
    <li id={`task-${String(task.id)}`} className="pd-task" data-done={done ? 'true' : undefined}>
      <div className="pd-task-row">
        {/* A box, checked when done; "완료" beside the title says it in words too. */}
        <span className="pd-task-status" data-done={done ? 'true' : undefined}>
          {props.canWrite ? (
            <IconButton
              label={tf(done ? 'issueTasks.reopenOf' : 'issueTasks.doneOf', { title: task.title })}
              icon={<CheckIcon size={14} />}
              size="sm"
              pending={statusPending}
              onClick={() => {
                void toggleStatus();
              }}
            />
          ) : (
            <span className="pd-task-check" aria-hidden="true">
              <CheckIcon size={14} />
            </span>
          )}
        </span>
        <div className="pd-task-main">
          {props.renaming ? (
            <TaskRename
              task={task}
              update={props.update}
              onDone={() => {
                props.onRename(false);
              }}
            />
          ) : (
            <span className="pd-task-title">{task.title}</span>
          )}
          <div className="pd-task-meta">
            {done && (
              <span className="pd-inline">
                <Badge tone="neutral">{t('issueTasks.done')}</Badge>
                {task.doneBy && <span>{task.doneBy.name}</span>}
                {task.doneAt && <RelativeTime dateTime={task.doneAt} />}
              </span>
            )}
            {task.assignee ? <span>{task.assignee.name}</span> : <span className="pd-muted">{t('issueTasks.unassigned')}</span>}
            {task.dueDate !== null && (
              <span className="pd-inline">
                <time dateTime={task.dueDate}>{longDate(task.dueDate)}</time>
                {task.overdue && <Badge tone="warning">{t('issueTasks.overdue')}</Badge>}
              </span>
            )}
            <button
              type="button"
              className="pd-button"
              data-variant="quiet"
              data-size="sm"
              aria-expanded={props.open}
              aria-controls={props.open ? panelId : undefined}
              onClick={props.onToggle}
            >
              <span className="pd-button-text">{tf('issueTasks.notes', { count })}</span>
            </button>
          </div>
        </div>
        {menu.length > 0 && <RowMenu label={tf('issueTasks.actions', { title: task.title })} items={menu} />}
      </div>
      {error !== null && <Alert tone="error">{error}</Alert>}
      {props.open && (
        <div id={panelId} className="pd-task-panel">
          {props.canWrite && <TaskTools task={task} update={props.update} onAssign={props.onAssign} />}
          {task.notes.length > 0 && (
            <ol className="pd-task-notes">
              {task.notes.map((n) => (
                <NoteLine
                  key={n.id}
                  task={task}
                  note={n}
                  issueKey={props.issue.key}
                  canEdit={props.canWrite && n.author.accountId === props.myId}
                  canRemove={props.canRemoveNote(n)}
                  onRemove={() => {
                    props.onRemoveNote(n);
                  }}
                  onFailure={props.onFailure}
                  put={props.put}
                />
              ))}
            </ol>
          )}
          {props.canWrite && <NoteForm task={task} issueKey={props.issue.key} onFailure={props.onFailure} put={props.put} />}
        </div>
      )}
    </li>
  );
}

/** The title field of a task being renamed: Enter or "저장" sends it, "취소" leaves it as it was. */
function TaskRename(props: { task: Task; update: TaskLineProps['update']; onDone: () => void }) {
  const live = useLive();
  const [draft, setDraft] = useState(props.task.title);
  const input = useRef<HTMLInputElement>(null);
  useLayoutEffect(() => {
    input.current?.focus();
  }, []);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const send = async () => {
    const title = draft.trim();
    if (pending || title === '') return;
    if (title === props.task.title) {
      props.onDone();
      return;
    }
    setPending(true);
    setError(null);
    const result = await props.update(props.task, { title });
    if (!live()) return;
    setPending(false);
    if (result.ok) props.onDone();
    else setError(taskFailureText(result.failure));
  };

  return (
    <form
      className="pd-task-title-form"
      noValidate
      onSubmit={(e: SubmitEvent) => {
        e.preventDefault();
        void send();
      }}
    >
      <TextField ref={input} label={t('issueTasks.taskTitle')} value={draft} onChange={setDraft} maxLength={100} />
      {error !== null && <Alert tone="error">{error}</Alert>}
      <div className="pd-form-actions">
        <Button type="submit" size="sm" pending={pending}>
          {t('issueTasks.save')}
        </Button>
        <Button variant="secondary" size="sm" onClick={props.onDone}>
          {TEXT.cancel}
        </Button>
      </div>
    </form>
  );
}

/** 담당자 and 마감일 of an opened task: the dialog and the date picker. */
function TaskTools(props: { task: Task; update: TaskLineProps['update']; onAssign: () => void }) {
  const { task } = props;
  const live = useLive();
  const toggleId = useId();
  const [open, setOpenState] = useState(false);
  const opening = useRef(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  const setOpen = (next: boolean) => {
    opening.current += 1;
    setOpenState(next);
    setBusy(false);
    setError(undefined);
  };

  const change = async (dueDate: string | null) => {
    const mine = opening.current;
    setBusy(true);
    setError(undefined);
    const result = await props.update(task, { dueDate });
    // A failure of an earlier opening is dropped: the picker it belonged to is gone.
    if (!live() || mine !== opening.current) return;
    if (result.ok) {
      setOpen(false);
      document.getElementById(toggleId)?.focus();
    } else {
      setBusy(false);
      setError(dueFailureText(result.failure));
    }
  };

  return (
    <div className="pd-task-tools">
      <span className="pd-inline">
        <span className="pd-label">{t('issueTasks.assignee')}</span>
        {/* Named apart from the issue's own "바꾸기" (S6B-AC-85). */}
        <button type="button" className="pd-button" data-variant="secondary" data-size="sm" aria-label={t('issueTasks.changeAssignee')} onClick={props.onAssign}>
          <span className="pd-button-text">{t('issueTasks.change')}</span>
        </button>
      </span>
      <span className="pd-inline">
        <span className="pd-label">{t('issueTasks.dueDate')}</span>
        <DueDatePicker
          id={toggleId}
          label={t('issueTasks.dueDate')}
          toggleLabel={t('issueTasks.change')}
          toggleName={t('issueTasks.changeDue')}
          value={task.dueDate}
          min={todayInBrowser()}
          onSubmit={(date) => {
            void change(date);
          }}
          onClear={() => {
            void change(null);
          }}
          busy={busy}
          {...(error === undefined ? {} : { error })}
          submitLabel={t('issueTasks.setDue')}
          clearLabel={t('issueTasks.delete')}
          open={open}
          onOpenChange={setOpen}
        />
      </span>
    </div>
  );
}

/** A plain text box with its label and the 4,000 count. */
function NoteBox(props: { label: string; value: string; onChange: (value: string) => void }) {
  const id = useId();
  return (
    <div className="pd-field">
      <div className="pd-field-head">
        <label className="pd-label" htmlFor={id}>
          {props.label}
        </label>
        <span className="pd-field-count">
          {codePoints(props.value)} / {NOTE_MAX.toLocaleString('en-US')}
        </span>
      </div>
      <textarea
        id={id}
        className="pd-editor-area pd-task-note-area"
        rows={3}
        maxLength={NOTE_MAX}
        value={props.value}
        onChange={(e) => {
          props.onChange(e.target.value);
        }}
      />
    </div>
  );
}

/** "기록 남기기" under the notes of an opened task. */
function NoteForm(props: { task: Task; issueKey: string; onFailure: (failure: Failure) => void; put: (task: Task) => void }) {
  const live = useLive();
  const [draft, setDraft] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const send = async () => {
    if (pending || draft.trim() === '') return;
    setPending(true);
    setError(null);
    const path = { key: props.issueKey, id: props.task.id };
    const result = await call(api.POST('/api/console/issues/{key}/tasks/{id}/notes', { params: { path }, body: { content: draft } }));
    if (result.ok) props.put({ ...props.task, notes: [...props.task.notes, result.data] });
    else props.onFailure(result.failure);
    if (!live()) return;
    setPending(false);
    if (result.ok) setDraft('');
    else setError(noteFailureText(result.failure));
  };

  return (
    <form
      className="pd-task-note-form"
      noValidate
      onSubmit={(e: SubmitEvent) => {
        e.preventDefault();
        void send();
      }}
    >
      <NoteBox label={t('issueTasks.addNote')} value={draft} onChange={setDraft} />
      {error !== null && <Alert tone="error">{error}</Alert>}
      <div className="pd-form-actions">
        <Button type="submit" variant="secondary" size="sm" pending={pending}>
          {t('issueTasks.addNoteSubmit')}
        </Button>
      </div>
    </form>
  );
}

/** One note, system entry, or trace of a deleted note. */
function NoteLine(props: {
  task: Task;
  note: TaskNote;
  issueKey: string;
  canEdit: boolean;
  canRemove: boolean;
  onRemove: () => void;
  onFailure: (failure: Failure) => void;
  put: (task: Task) => void;
}) {
  const { note } = props;
  const live = useLive();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(note.content);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (note.kind === 'CHANGE') {
    const said = taskChangeText(note);
    return (
      <li className="pd-task-note" data-kind="change">
        <p className="pd-task-change">
          <span>{said.text}</span>
          <RelativeTime dateTime={note.createdAt} />
        </p>
        {said.titles && <p className="pd-task-titles">{`${said.titles.before} → ${said.titles.after}`}</p>}
      </li>
    );
  }

  if (note.deleted) {
    return (
      <li className="pd-task-note" data-deleted="true">
        <p className="pd-task-trace">
          <span>{t('issueTasks.deletedNote')}</span>
          {note.deletedBy && <span>{tf('issueTasks.deletedBy', { name: note.deletedBy.name })}</span>}
        </p>
      </li>
    );
  }

  const save = async () => {
    if (pending || draft.trim() === '') return;
    if (draft === note.content) {
      setEditing(false);
      return;
    }
    setPending(true);
    setError(null);
    const path = { key: props.issueKey, id: props.task.id, noteId: note.id };
    const result = await call(api.PATCH('/api/console/issues/{key}/tasks/{id}/notes/{noteId}', { params: { path }, body: { content: draft } }));
    if (result.ok) props.put({ ...props.task, notes: props.task.notes.map((n) => (n.id === note.id ? result.data : n)) });
    else props.onFailure(result.failure);
    if (!live()) return;
    setPending(false);
    if (result.ok || (result.failure.kind === 'rejected' && result.failure.code === 'TASK_NOTE_DELETED')) setEditing(false);
    else setError(noteFailureText(result.failure));
  };

  return (
    <li className="pd-task-note">
      <p className="pd-task-note-head">
        <span className="pd-task-note-author">{note.author.name}</span>
        <RelativeTime dateTime={note.createdAt} />
        {note.editedAt !== null && <span className="pd-muted">{t('issueTasks.edited')}</span>}
      </p>
      {editing ? (
        <form
          className="pd-task-note-form"
          noValidate
          onSubmit={(e: SubmitEvent) => {
            e.preventDefault();
            void save();
          }}
        >
          <NoteBox label={t('issueTasks.editNote')} value={draft} onChange={setDraft} />
          {error !== null && <Alert tone="error">{error}</Alert>}
          <div className="pd-form-actions">
            <Button type="submit" size="sm" pending={pending}>
              {t('issueTasks.save')}
            </Button>
            <Button
              variant="secondary"
              size="sm"
              onClick={() => {
                setEditing(false);
                setError(null);
              }}
            >
              {TEXT.cancel}
            </Button>
          </div>
        </form>
      ) : (
        <p className="pd-task-note-text">{note.content}</p>
      )}
      {!editing && (props.canEdit || props.canRemove) && (
        <div className="pd-task-note-actions">
          {props.canEdit && (
            <Button
              variant="quiet"
              size="sm"
              onClick={() => {
                setDraft(note.content);
                setEditing(true);
              }}
            >
              {t('issueTasks.edit')}
            </Button>
          )}
          {props.canRemove && (
            <Button variant="quiet" size="sm" onClick={props.onRemove}>
              {t('issueTasks.delete')}
            </Button>
          )}
        </div>
      )}
    </li>
  );
}
