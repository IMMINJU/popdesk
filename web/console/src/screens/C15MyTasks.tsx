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
import { rateLimitedText } from '../app/messages';
import { PageNav } from '../app/PageNav';
import { PAGE_SIZE, usePageMove, usePageParam } from '../app/paging';
import { call, type Failure } from '../app/result';
import { useToLogin } from '../app/sessionContext';

type MyTask = Schemas['MyTask'];
type View = 'OPEN' | 'DONE';

const PATH = '/tasks';
const FAILED_TEXT = '작업을 바꾸지 못했습니다. 다시 해 보세요.';
const VIEWS: { status: View; name: string; href: string; empty: string }[] = [
  { status: 'OPEN', name: '할 일', href: PATH, empty: '맡은 작업이 없습니다.' },
  { status: 'DONE', name: '끝낸 일', href: `${PATH}?status=DONE`, empty: '끝낸 작업이 없습니다.' },
];

/** What "완료" did on a row: on its way, done, or refused with a failure. */
type Finish = { state: 'pending' } | { state: 'done' } | { state: 'failed'; failure: Failure };

function issueHref(key: string): string {
  return `/issues/${encodeURIComponent(key)}`;
}

function dueCell(t: MyTask) {
  if (t.dueDate === null) return null;
  return (
    <span className="pd-inline pd-due-cell">
      <time dateTime={t.dueDate}>{longDate(t.dueDate)}</time>
      {t.overdue && <Badge tone="warning">지남</Badge>}
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
      header: '작업',
      width: '14rem',
      cell: (t) => (
        <Link className="pd-title-cell" to={`${issueHref(t.issue.key)}#task-${String(t.id)}`}>
          {t.title}
        </Link>
      ),
    },
    {
      key: 'issue',
      header: '문의',
      width: '14rem',
      cell: (t) => (
        <Link className="pd-title-cell" to={issueHref(t.issue.key)}>
          <span className="pd-key">{t.issue.key}</span> {t.issue.title}
        </Link>
      ),
    },
    { key: 'company', header: '고객사', cell: (t) => t.companyName },
    { key: 'due', header: '마감일', cell: dueCell },
    status === 'OPEN'
      ? { key: 'finish', header: '상태', cell: (t) => <FinishCell task={t} finish={finished[t.id]} onFinish={finish} /> }
      : { key: 'doneAt', header: '끝낸 시각', align: 'end', cell: (t) => (t.doneAt === null ? null : <RelativeTime dateTime={t.doneAt} focusable={false} />) },
  ];

  return (
    <div className="pd-screen pd-my-tasks">
      <div className="pd-screen-head">
        <PageTitle titleRef={heading}>내 작업</PageTitle>
      </div>
      <nav className="pd-views" aria-label="보기">
        {VIEWS.map((v) => (
          <Link key={v.status} to={v.href} aria-current={v.status === status ? 'page' : undefined}>
            {v.name}
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
          caption={`내 작업, ${String(data?.totalElements ?? 0)}건`}
          columns={columns}
          rows={rows}
          rowKey={(t) => String(t.id)}
          status={firstLoad ? 'loading' : 'ready'}
          skeletonRows={load.slow ? 5 : 0}
          empty={<EmptyState icon={<InboxIcon size={20} />} title={view?.empty ?? ''} />}
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
  if (finish?.state === 'done') return <span>완료했습니다</span>;
  const pending = finish?.state === 'pending';
  return (
    <span className="pd-finish-cell">
      {/* Named with the title: every row has one (screens.md 2절). */}
      <button
        type="button"
        className="pd-button"
        data-variant="secondary"
        data-size="sm"
        aria-label={`${task.title} 완료`}
        aria-disabled={pending ? true : undefined}
        onClick={() => {
          if (!pending) void props.onFinish(task);
        }}
      >
        <span className="pd-button-text">완료</span>
      </button>
      {finish?.state === 'failed' && (
        <span role="alert" className="pd-field-error">
          {finish.failure.kind === 'rateLimited' ? rateLimitedText(finish.failure.seconds) : FAILED_TEXT}
        </span>
      )}
    </span>
  );
}
