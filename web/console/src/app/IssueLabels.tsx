// C-4 "라벨" and "마감일" lines (6a-labels screens.md 1절): the chips and the due date, and the two
// pickers behind "바꾸기". A change is one request; its answer is the issue as the server has it now,
// handed to the screen to draw. An answer that comes after the picker closed draws the line when
// it succeeded and is dropped when it failed. An answer after a move to another issue finds the
// screen gone (S6A-AC-86).
import { Badge, Button, DueDatePicker, LabelChips, LabelPicker, type LabelItem } from '@popdesk/ui';
import { useId, useRef, useState } from 'react';
import { Link } from 'react-router';
import { api, type Schemas } from '../api';
import { longDate, todayInBrowser } from './dueDates';
import { byName, labelItem, moreLabelsText } from './labelItems';
import { useLive } from './hooks';
import { t } from './locale';
import { failureText, TEXT } from './messages';
import { call, type Failure, type Result } from './result';
import { useToLogin } from './sessionContext';

type Issue = Schemas['Issue'];

/** An issue takes at most 20 labels (S6A-AC-24). */
const ISSUE_LABEL_LIMIT = 20;

export interface IssueLineProps {
  issue: Issue;
  /** Shows "바꾸기"; not for a `CLOSED` issue. */
  canChange: boolean;
  /** The server's issue after a change, also one that came after the picker closed. */
  onAnswer: (issue: Issue) => void;
  /** A change was refused because the issue changed meanwhile (e.g. it was closed). */
  onStale: () => void;
}

/**
 * Counts the openings of a picker: an answer to a request of an earlier opening was made while
 * that opening was shown, and the picker is closed or opened anew by now.
 */
function useOpening() {
  const [open, setOpenState] = useState(false);
  const opening = useRef(0);
  const setOpen = (next: boolean) => {
    opening.current += 1;
    setOpenState(next);
  };
  return { open, setOpen, opening };
}

export function IssueLabelsLine(props: IssueLineProps & { isAdmin: boolean }) {
  const { issue } = props;
  const live = useLive();
  const toLogin = useToLogin();
  const { open, setOpen, opening } = useOpening();
  const [labels, setLabels] = useState<{ items: LabelItem[] } | 'loading' | 'failed'>('loading');
  const [busy, setBusy] = useState<Record<string, boolean>>({});
  const [error, setError] = useState<string | undefined>(undefined);
  const toggleId = useId();

  const loadLabels = async () => {
    const mine = opening.current;
    setLabels('loading');
    const result = await call(api.GET('/api/console/companies/{code}/labels', { params: { path: { code: issue.companyCode } } }));
    if (!live() || mine !== opening.current) return;
    if (!result.ok && result.failure.kind === 'unauthenticated') {
      toLogin();
      return;
    }
    setLabels(result.ok ? { items: byName(result.data.items).map(labelItem) } : 'failed');
  };

  const onOpenChange = (next: boolean) => {
    setOpen(next);
    setBusy({});
    setError(undefined);
    if (next) void loadLabels();
  };

  const toggle = async (id: string, on: boolean) => {
    const mine = opening.current;
    setBusy((b) => ({ ...b, [id]: on }));
    setError(undefined);
    const path = { params: { path: { key: issue.key, id: Number(id) } } };
    const result: Result<Issue> = on
      ? await call(api.PUT('/api/console/issues/{key}/labels/{id}', path))
      : await call(api.DELETE('/api/console/issues/{key}/labels/{id}', path));
    if (!live()) return;
    if (result.ok) props.onAnswer(result.data);
    else if (result.failure.kind === 'unauthenticated') toLogin();
    else if (result.failure.kind === 'rejected' && result.failure.code === 'ISSUE_CLOSED') props.onStale();
    // A failure of an earlier opening is dropped: the picker it belonged to is gone.
    if (mine !== opening.current) return;
    setBusy((b) => Object.fromEntries(Object.entries(b).filter(([k]) => k !== id)));
    if (!result.ok) setError(t('issueLabels.failed'));
  };

  // The rows show the request on its way: a box pressed stays as pressed until the answer.
  const selected = issue.labels.map((l) => String(l.id)).filter((id) => busy[id] !== false);
  for (const [id, on] of Object.entries(busy)) if (on && !selected.includes(id)) selected.push(id);
  const shown = byName(issue.labels).map(labelItem);
  const ready = typeof labels === 'object';

  return (
    <div>
      <dt>{t('issueList.labels')}</dt>
      <dd className="pd-inline">
        {shown.length === 0 ? <span className="pd-muted">{t('issueLabels.none')}</span> : <LabelChips labels={shown} max={shown.length} moreText={moreLabelsText} />}
        {props.canChange && (
          <LabelPicker
            id={toggleId}
            label={t('issueList.labels')}
            toggleLabel={t('issueLabels.change')}
            toggleName={t('issueLabels.changeLabels')}
            labels={ready ? labels.items : []}
            selected={selected}
            onToggle={(id, on) => {
              void toggle(id, on);
            }}
            busyIds={Object.keys(busy)}
            limit={ISSUE_LABEL_LIMIT}
            limitText={t('issueLabels.limit')}
            open={open}
            onOpenChange={onOpenChange}
            filterLabel={t('issueLabels.filter')}
            noMatchText={t('issueLabels.noMatch')}
            emptyText={labels === 'loading' ? t('issueLabels.loading') : labels === 'failed' ? t('issueLabels.loadFailed') : t('issueLabels.empty')}
            emptyAction={
              labels === 'failed' ? (
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => {
                    void loadLabels();
                  }}
                >
                  {TEXT.retryLoad}
                </Button>
              ) : ready && props.isAdmin ? (
                <Link to={`/companies/${encodeURIComponent(issue.companyCode)}#labels`}>{t('issueLabels.create')}</Link>
              ) : undefined
            }
            {...(error === undefined ? {} : { error })}
          />
        )}
      </dd>
    </div>
  );
}

function dueFailureText(failure: Failure): string {
  if (failure.kind === 'invalid' && failure.fields.some((f) => f.field === 'dueDate')) return t('issueLabels.pastDate');
  return failureText(failure);
}

export function IssueDueLine(props: IssueLineProps) {
  const { issue } = props;
  const live = useLive();
  const toLogin = useToLogin();
  const { open, setOpen, opening } = useOpening();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);
  const toggleId = useId();

  const onOpenChange = (next: boolean) => {
    setOpen(next);
    setBusy(false);
    setError(undefined);
  };

  const change = async (date: string | null) => {
    const mine = opening.current;
    setBusy(true);
    setError(undefined);
    const path = { params: { path: { key: issue.key } } };
    const result: Result<Issue> =
      date === null
        ? await call(api.DELETE('/api/console/issues/{key}/due-date', path))
        : await call(api.PUT('/api/console/issues/{key}/due-date', { ...path, body: { dueDate: date } }));
    if (!live()) return;
    if (result.ok) props.onAnswer(result.data);
    else if (result.failure.kind === 'unauthenticated') toLogin();
    else if (result.failure.kind === 'rejected' && result.failure.code === 'ISSUE_CLOSED') props.onStale();
    if (mine !== opening.current) return;
    setBusy(false);
    if (result.ok) {
      // Done: the picker folds and focus goes back to its button.
      setOpen(false);
      document.getElementById(toggleId)?.focus();
    } else {
      setError(dueFailureText(result.failure));
    }
  };

  return (
    <div>
      <dt>{t('issueList.due')}</dt>
      <dd className="pd-inline">
        {issue.dueDate === null ? (
          <span className="pd-muted">{t('issueLabels.none')}</span>
        ) : (
          <span className="pd-inline">
            <time dateTime={issue.dueDate}>{longDate(issue.dueDate)}</time>
            {issue.overdue && <Badge tone="warning">{t('issueList.overdue')}</Badge>}
          </span>
        )}
        {props.canChange && (
          <DueDatePicker
            id={toggleId}
            label={t('issueList.due')}
            toggleLabel={t('issueLabels.change')}
            toggleName={t('issueLabels.changeDue')}
            value={issue.dueDate}
            min={todayInBrowser()}
            onSubmit={(date) => {
              void change(date);
            }}
            onClear={() => {
              void change(null);
            }}
            busy={busy}
            {...(error === undefined ? {} : { error })}
            submitLabel={t('issueLabels.setDue')}
            clearLabel={t('issueLabels.clearDue')}
            open={open}
            onOpenChange={onOpenChange}
          />
        )}
      </dd>
    </div>
  );
}
