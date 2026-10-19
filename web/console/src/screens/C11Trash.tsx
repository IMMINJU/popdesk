// C-11 휴지통 (관리자, 7a-trash screens.md 2절). Deleted issues for 30 days: 복구 at once, 영구 삭제 after
// asking. A restored row stays as a link to the issue until the page is read again.
import { Alert, Button, ConfirmDialog, DataTable, EmptyState, RelativeTime, StatusBadge, type DataTableColumn } from '@popdesk/ui';
import { useLayoutEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { api, type Schemas } from '../api';
import { useMainBusy } from '../app/busy';
import { useLive, useLoad } from '../app/hooks';
import { LoadFailed, PageTitle } from '../app/layout';
import { texts, tf } from '../app/locale';
import { PageNav } from '../app/PageNav';
import { PAGE_SIZE, usePageMove, usePageParam } from '../app/paging';
import { call, failureOf } from '../app/result';
import { useToLogin } from '../app/sessionContext';
import { purgeInText } from '../app/trashWords';

type TrashedIssue = Schemas['TrashedIssue'];

const PATH = '/trash';
// `byCleanup` is the "지운 사람" of an issue the retention cleanup moved (10b screens.md C-11): the API gives no person.
const WORDS = texts({
  changeFailed: 'trash.changeFailed',
  purgeTitle: 'trash.purgeTitle',
  purgeText: 'trash.purgeText',
  byCleanup: 'trash.byCleanup',
  restored: 'trash.restored',
  restore: 'trash.restore',
  purge: 'trash.purge',
  issue: 'trash.issue',
  company: 'trash.company',
  status: 'trash.status',
  deletedAt: 'trash.deletedAt',
  deletedBy: 'trash.deletedBy',
  actions: 'trash.actions',
  title: 'trash.title',
  help: 'trash.help',
  noneOnPage: 'trash.noneOnPage',
  empty: 'trash.empty',
  keep: 'trash.keep',
  purging: 'trash.purging',
});

/** What happened to a row on this page since it was read. */
type Mark = { restoring: true } | { restored: true } | { error: string };

export function C11Trash() {
  const page = usePageParam();
  const live = useLive();
  // "{n}일 뒤" counts from when the screen opened: it moves by days, not while one looks.
  const [now] = useState(() => Date.now());
  const toLogin = useToLogin();
  const heading = useRef<HTMLHeadingElement>(null);
  const load = useLoad(() => call(api.GET('/api/console/trash', { params: { query: { page, size: PAGE_SIZE } } })), String(page));
  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);
  const move = usePageMove(load.data, heading);
  const data = load.data;
  const outOfRange = data !== undefined && data.items.length === 0 && data.totalElements > 0;

  // Marks belong to the page they were made on, and go when the page is read again.
  const [marks, setMarks] = useState<{ page: number; rows: Record<string, Mark> }>({ page, rows: {} });
  const rowMarks = marks.page === page ? marks.rows : {};
  // The page now, for answers that arrive after a move: those are dropped.
  const pageNow = useRef(page);
  useLayoutEffect(() => {
    pageNow.current = page;
  });
  const mark = (on: number, key: string, next: Mark) => {
    setMarks((before) => ({ page: on, rows: { ...(before.page === on ? before.rows : {}), [key]: next } }));
  };
  const readAgain = () => {
    setMarks({ page: pageNow.current, rows: {} });
    void load.reload();
  };
  const stillHere = (on: number) => live() && pageNow.current === on;

  const restore = async (t: TrashedIssue) => {
    const on = page;
    mark(on, t.key, { restoring: true });
    // Not through `call`: the 409 names the other issues in `issueKeys`, which a Failure does not keep.
    const answer = await api.POST('/api/console/trash/{key}/restore', { params: { path: { key: t.key } } }).catch(() => null);
    if (!stillHere(on)) return;
    const failure = answer === null ? null : answer.response.ok ? undefined : failureOf(answer.response, answer.error);
    if (failure === undefined) mark(on, t.key, { restored: true });
    else if (failure?.kind === 'notFound') readAgain();
    else if (failure?.kind === 'unauthenticated') toLogin();
    else if (failure?.kind === 'rejected' && failure.code === 'RESTORE_LINK_CONFLICT') {
      const others = answer?.error?.issueKeys ?? [];
      mark(on, t.key, { error: tf('trash.linkConflict', { keys: others.join(', ') }) });
    } else mark(on, t.key, { error: WORDS.changeFailed });
  };

  // 영구 삭제: asked first; an answer for a question already closed is dropped unless it succeeded.
  const [purging, setPurging] = useState<TrashedIssue | null>(null);
  const [purgePending, setPurgePending] = useState(false);
  const [purgeError, setPurgeError] = useState<string | undefined>(undefined);
  const purgeAsk = useRef(0);
  const closePurge = () => {
    purgeAsk.current += 1;
    setPurging(null);
    setPurgePending(false);
    setPurgeError(undefined);
  };
  const purge = async () => {
    if (!purging || purgePending) return;
    const on = page;
    const mine = purgeAsk.current;
    setPurgePending(true);
    setPurgeError(undefined);
    const result = await call(api.DELETE('/api/console/trash/{key}', { params: { path: { key: purging.key } } }));
    if (!stillHere(on)) return;
    if (mine !== purgeAsk.current) {
      // Closed while it was on its way: the row of a purged issue still goes.
      if (result.ok) readAgain();
      return;
    }
    if (result.ok || result.failure.kind === 'notFound') {
      closePurge();
      readAgain();
    } else {
      setPurgePending(false);
      setPurgeError(WORDS.changeFailed);
    }
  };

  const actions = (t: TrashedIssue) => {
    const m = rowMarks[t.key];
    if (m && 'restored' in m) {
      return (
        <span className="pd-inline">
          {WORDS.restored} <Link to={`/issues/${encodeURIComponent(t.key)}`}>{tf('trash.open', { key: t.key })}</Link>
        </span>
      );
    }
    return (
      <span className="pd-two-lines">
        <span className="pd-row-actions">
          <Button
            variant="secondary"
            size="sm"
            pending={m !== undefined && 'restoring' in m}
            onClick={() => {
              void restore(t);
            }}
          >
            <span className="pd-sr-only">{t.key}</span> {WORDS.restore}
          </Button>
          <Button
            variant="quiet"
            size="sm"
            blocked={m !== undefined && 'restoring' in m}
            onClick={() => {
              setPurgeError(undefined);
              setPurging(t);
            }}
          >
            <span className="pd-sr-only">{t.key}</span> {WORDS.purge}
          </Button>
        </span>
        {m && 'error' in m && <Alert tone="error">{m.error}</Alert>}
      </span>
    );
  };

  const columns: DataTableColumn<TrashedIssue>[] = [
    {
      key: 'issue',
      header: WORDS.issue,
      cell: (t) => (
        <span className="pd-two-lines pd-trash-issue">
          <span className="pd-key">{t.key}</span>
          <span>{t.title}</span>
        </span>
      ),
    },
    { key: 'company', header: WORDS.company, cell: (t) => t.companyName },
    { key: 'status', header: WORDS.status, cell: (t) => <StatusBadge kind="issue" status={t.status} audience="agent" /> },
    { key: 'deletedAt', header: WORDS.deletedAt, cell: (t) => <RelativeTime dateTime={t.deletedAt} /> },
    { key: 'deletedBy', header: WORDS.deletedBy, cell: (t) => (t.deletedBy === null ? <span className="pd-muted">{WORDS.byCleanup}</span> : t.deletedBy.name) },
    { key: 'purgeAt', header: WORDS.purge, cell: (t) => purgeInText(t.purgeAt, now) },
    { key: 'actions', header: <span className="pd-sr-only">{WORDS.actions}</span>, cell: actions },
  ];

  return (
    <div className="pd-screen pd-trash">
      <PageTitle titleRef={heading}>{WORDS.title}</PageTitle>
      <p className="pd-muted">{WORDS.help}</p>
      {load.status === 'failed' && (
        <LoadFailed
          onRetry={() => {
            void load.reload();
          }}
        />
      )}
      {(load.status !== 'failed' || data) && (
        <DataTable
          caption={tf('trash.caption', { count: data?.totalElements ?? 0 })}
          columns={columns}
          rows={data?.items ?? []}
          rowKey={(t) => t.key}
          status={firstLoad ? 'loading' : 'ready'}
          skeletonRows={load.slow ? 8 : 0}
          density="compact"
          empty={<EmptyState title={outOfRange ? WORDS.noneOnPage : WORDS.empty} />}
        />
      )}
      {/* A page past the end (its rows were restored or purged) keeps the way back to the pages before it. */}
      {data && <PageNav path={PATH} page={data.page} pageCount={Math.max(data.totalPages, data.page)} onPageChange={move.onPageChange} />}
      <p role="status" className="pd-sr-only">
        {move.announcement}
      </p>
      <ConfirmDialog
        open={purging !== null}
        tone="danger"
        title={WORDS.purgeTitle}
        message={WORDS.purgeText}
        confirmLabel={WORDS.purge}
        cancelLabel={WORDS.keep}
        pending={purgePending}
        pendingLabel={WORDS.purging}
        {...(purgeError === undefined ? {} : { error: purgeError })}
        onConfirm={() => {
          void purge();
        }}
        onCancel={closePurge}
      />
    </div>
  );
}
