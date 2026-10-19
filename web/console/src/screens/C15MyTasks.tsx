// C-15 내 작업 (6b-tasks screens.md 2절): the tasks assigned to the reader, 할 일 (`OPEN`, the
// default) or 끝낸 일 (`DONE`), 20 a page. The view and the page live in the address; an answer for
// a view or page left behind is dropped (useLoad). "완료" ends a task in place: its row says so
// until the page is read again.
import { Badge, DataTable, EmptyState, InboxIcon, RelativeTime, type DataTableColumn } from '@popdesk/ui';
import { useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api, type Schemas } from '../api';
import { useMainBusy } from '../app/busy';
import { longDate } from '../app/dueDates';
import { useLoad } from '../app/hooks';
import { LoadFailed, PageTitle } from '../app/layout';
import { t, tf } from '../app/locale';
import { rateLimitedText } from '../app/messages';
import { PageNav } from '../app/PageNav';
import { PAGE_SIZE, usePageMove, usePageParam } from '../app/paging';
import { call, type Failure } from '../app/result';
import { useToLogin } from '../app/sessionContext';

type MyTask = Schemas['MyTask'];
type View = 'OPEN' | 'DONE';

const PATH = '/tasks';
const VIEWS = [
  { status: 'OPEN', name: 'myTasks.openView', href: PATH, empty: 'myTasks.emptyOpen' },
  { status: 'DONE', name: 'myTasks.doneView', href: `${PATH}?status=DONE`, empty: 'myTasks.emptyDone' },
] as const satisfies readonly { status: View; name: Parameters<typeof t>[0]; href: string; empty: Parameters<typeof t>[0] }[];

/** What "완료" did on a row: on its way, done, or refused with a failure. */
type Finish = { state: 'pending' } | { state: 'done' } | { state: 'failed'; failure: Failure };

function issueHref(key: string): string {
  return `/issues/${encodeURIComponent(key)}`;
}

function dueCell(task: MyTask) {
  if (task.dueDate === null) return null;
  return (
    <span className="pd-inline pd-due-cell">
      <time dateTime={task.dueDate}>{longDate(task.dueDate)}</time>
      {task.overdue && <Badge tone="warning">{t('issueTasks.overdue')}</Badge>}
    </span>
  );
}

export function C15MyTasks() {
  const [params] = useSearchParams();
  const status: View = params.get('status') === 'DONE' ? 'DONE' : 'OPEN';
  const view = VIEWS.find((v) => v.status === status) ?? VIEWS[0];
  const page = usePageParam();
  const heading = useRef<HTMLHeadingElement>(null);
  const toLogin = useToLogin();
  const key = `${status}\n${String(page)}`;
  // What "완료" did on the rows of this view and page.
  const [finishes, setFinishes] = useState<{ key: string; rows: Record<number, Finish> }>({ key, rows: {} });
  const finished = finishes.key === key ? finishes.rows : {};

  const load = useLoad(() => call(api.GET('/api/console/tasks', { params: { query: { status, page, size: PAGE_SIZE } } })), key);
  const data = load.data;
  const firstLoad = load.status === 'loading' && data === undefined;
  useMainBusy(firstLoad);
  const move = usePageMove(data, heading);
  const rows = data?.items ?? [];

  const finish = async (t: MyTask) => {
    if (finished[t.id]?.state === 'pending') return;
    const setFinished = (one: Finish) => {
      setFinishes((now) => ({ key, rows: { ...(now.key === key ? now.rows : {}), [t.id]: one } }));
    };
    setFinished({ state: 'pending' });
    const result = await call(api.PATCH('/api/console/issues/{key}/tasks/{id}', { params: { path: { key: t.issue.key, id: t.id } }, body: { status: 'DONE' } }));
    if (!result.ok && result.failure.kind === 'unauthenticated') {
      toLogin();
      return;
    }
    setFinished(result.ok ? { state: 'done' } : { state: 'failed', failure: result.failure });
  };

  const columns: DataTableColumn<MyTask>[] = [
    {
      key: 'task',
      header: t('myTasks.task'),
      width: '14rem',
      cell: (t) => (
        <Link className="pd-wrap-cell" to={`${issueHref(t.issue.key)}#task-${String(t.id)}`}>
          {t.title}
        </Link>
      ),
    },
    {
      key: 'issue',
      header: t('myTasks.issue'),
      width: '14rem',
      cell: (t) => (
        <Link className="pd-wrap-cell" to={issueHref(t.issue.key)}>
          <span className="pd-key">{t.issue.key}</span> {t.issue.title}
        </Link>
      ),
    },
    { key: 'company', header: t('myTasks.company'), cell: (t) => t.companyName },
    { key: 'due', header: t('myTasks.dueDate'), cell: dueCell },
    status === 'OPEN'
      ? { key: 'finish', header: t('myTasks.status'), cell: (t) => <FinishCell task={t} finish={finished[t.id]} onFinish={finish} /> }
      : { key: 'doneAt', header: t('myTasks.doneAt'), align: 'end', cell: (t) => (t.doneAt === null ? null : <RelativeTime dateTime={t.doneAt} focusable={false} />) },
  ];

  return (
    <div className="pd-screen pd-my-tasks">
      <div className="pd-screen-head">
        <PageTitle titleRef={heading}>{t('myTasks.title')}</PageTitle>
      </div>
      <nav className="pd-views" aria-label={t('myTasks.views')}>
        {VIEWS.map((v) => (
          <Link key={v.status} to={v.href} aria-current={v.status === status ? 'page' : undefined}>
            {t(v.name)}
          </Link>
        ))}
      </nav>
      {load.status === 'failed' &&
        (load.failure?.kind === 'rateLimited' ? (
          <p role="alert" className="pd-field-error">
            {rateLimitedText(load.failure.seconds)}
          </p>
        ) : (
          <LoadFailed
            onRetry={() => {
              void load.reload();
            }}
          />
        ))}
      {load.status !== 'failed' && (
        <DataTable
          caption={tf('myTasks.caption', { count: data?.totalElements ?? 0 })}
          columns={columns}
          rows={rows}
          rowKey={(t) => String(t.id)}
          status={firstLoad ? 'loading' : 'ready'}
          skeletonRows={load.slow ? 5 : 0}
          empty={<EmptyState icon={<InboxIcon size={20} />} title={t(view.empty)} />}
          density="compact"
        />
      )}
      {data && <PageNav path={PATH} page={data.page} pageCount={data.totalPages} onPageChange={move.onPageChange} />}
      <p role="status" className="pd-sr-only">
        {move.announcement}
      </p>
    </div>
  );
}

/** "완료" of a row on 할 일, and what became of it. */
function FinishCell(props: { task: MyTask; finish: Finish | undefined; onFinish: (t: MyTask) => Promise<void> }) {
  const { task, finish } = props;
  if (finish?.state === 'done') return <span>{t('myTasks.finished')}</span>;
  const pending = finish?.state === 'pending';
  return (
    <span className="pd-finish-cell">
      {/* Named with the title: every row has one (screens.md 2절). */}
      <button
        type="button"
        className="pd-button"
        data-variant="secondary"
        data-size="sm"
        aria-label={tf('myTasks.finishOf', { title: task.title })}
        aria-disabled={pending ? true : undefined}
        onClick={() => {
          if (!pending) void props.onFinish(task);
        }}
      >
        <span className="pd-button-text">{t('myTasks.finish')}</span>
      </button>
      {finish?.state === 'failed' && (
        <span role="alert" className="pd-field-error">
          {finish.failure.kind === 'rateLimited' ? rateLimitedText(finish.failure.seconds) : t('issueTasks.failed')}
        </span>
      )}
    </span>
  );
}
