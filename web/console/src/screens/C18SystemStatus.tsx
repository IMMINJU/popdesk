// C-18 시스템 상태 (관리자, screens.md 4절). Read only in the first slice: the
// two channels and the `failed` rows (OPS-3 part, S0-AC-81). No secret values.
import { Badge, DataTable, EmptyState, RelativeTime, type DataTableColumn } from '@popdesk/ui';
import { useRef } from 'react';
import { Link } from 'react-router';
import { api, type Schemas } from '../api';
import { useMainBusy } from '../app/busy';
import { useLoad } from '../app/hooks';
import { LoadFailed, NoPermission, PageTitle } from '../app/layout';
import { PageNav } from '../app/PageNav';
import { PAGE_SIZE, usePageMove, usePageParam } from '../app/paging';
import { call, type Result } from '../app/result';

type ChannelStatus = Schemas['ChannelStatus'];
type FailedDelivery = Schemas['FailedDelivery'];

/** Where an operator reads which environment variables turn a channel on. */
const CONFIG_DOC = 'https://github.com/IMMINJU/popdesk/blob/main/docs/operate/configuration.md';

interface ChannelRow {
  key: 'mail' | 'webhook';
  name: string;
  hint: string;
  status: ChannelStatus;
}

const channelColumns: DataTableColumn<ChannelRow>[] = [
  { key: 'name', header: '채널', cell: (c) => <span className="pd-strong">{c.name}</span> },
  {
    key: 'enabled',
    header: '상태',
    cell: (c) =>
      c.status.enabled ? (
        <span className="pd-on">켜짐</span>
      ) : (
        <span className="pd-inline">
          <Badge tone="neutral">설정 없음</Badge>
          <a href={CONFIG_DOC} target="_blank" rel="noopener noreferrer">
            {c.hint}
          </a>
        </span>
      ),
  },
  {
    key: 'last',
    header: '마지막 성공',
    cell: (c) => (c.status.lastSuccessAt ? <RelativeTime dateTime={c.status.lastSuccessAt} focusable={false} /> : <span className="pd-muted">없음</span>),
  },
  { key: 'queued', header: '보낼 차례', cell: (c) => c.status.queued.toLocaleString('ko'), align: 'end' },
  { key: 'failed', header: '실패 수', cell: (c) => c.status.failed.toLocaleString('ko'), align: 'end' },
];

const failedColumns: DataTableColumn<FailedDelivery>[] = [
  { key: 'channel', header: '채널', cell: (f) => (f.channel === 'MAIL' ? '메일' : '웹훅') },
  { key: 'created', header: '만든 시각', cell: (f) => <RelativeTime dateTime={f.createdAt} focusable={false} /> },
  { key: 'error', header: '마지막 오류 종류', cell: (f) => <code className="pd-code">{f.lastErrorKind}</code> },
  {
    key: 'recipient',
    header: '받는 계정 ID',
    cell: (f) => (f.recipientAccountId === null ? <span className="pd-muted">없음</span> : String(f.recipientAccountId)),
    align: 'end',
  },
];

interface StatusData {
  status: Schemas['SystemStatus'];
  failed: Schemas['PagedFailedDelivery'];
}

export function C18SystemStatus() {
  const page = usePageParam();
  const heading = useRef<HTMLHeadingElement>(null);
  const failedHeading = useRef<HTMLHeadingElement>(null);
  const load = useLoad(async (): Promise<Result<StatusData>> => {
    const [status, failed] = await Promise.all([
      call(api.GET('/api/console/system/status')),
      call(api.GET('/api/console/system/failed-deliveries', { params: { query: { page, size: PAGE_SIZE } } })),
    ]);
    if (!status.ok) return status;
    if (!failed.ok) return failed;
    return { ok: true, data: { status: status.data, failed: failed.data }, response: status.response };
  }, String(page));

  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);
  const move = usePageMove(load.data?.failed, failedHeading);

  // The role changed meanwhile: the same whole-screen state as for an agent (S0-AC-81).
  if (load.status === 'failed' && load.failure?.kind === 'forbidden') return <NoPermission />;

  const data = load.data;
  const channels: ChannelRow[] = data
    ? [
        { key: 'mail', name: '메일 보내기', hint: '메일 서버 설정 방법', status: data.status.mail },
        { key: 'webhook', name: '웹훅', hint: '웹훅 설정 방법', status: data.status.webhook },
      ]
    : [];
  const failed = data?.failed;

  return (
    <div className="pd-screen">
      <div className="pd-screen-head">
        <PageTitle titleRef={heading}>시스템 상태</PageTitle>
      </div>
      {load.status === 'failed' && (
        <LoadFailed
          onRetry={() => {
            void load.reload();
          }}
        />
      )}
      <section className="pd-section" aria-labelledby="channels-title">
        <h2 id="channels-title" className="pd-section-title">
          보내기 채널
        </h2>
        {(load.status !== 'failed' || data) && (
          <DataTable
            caption="보내기 채널"
            columns={channelColumns}
            rows={channels}
            rowKey={(c) => c.key}
            status={firstLoad ? 'loading' : 'ready'}
            skeletonRows={load.slow ? 2 : 0}
            density="compact"
          />
        )}
      </section>
      {data && failed && (
        <section className="pd-section" aria-labelledby="failed-title">
          <h2 id="failed-title" ref={failedHeading} className="pd-section-title" tabIndex={-1}>
            보내지 못한 항목
          </h2>
          <DataTable
            caption={`보내지 못한 항목, ${String(failed.totalElements)}건`}
            columns={failedColumns}
            rows={failed.items}
            rowKey={(f) => String(f.id)}
            density="compact"
            empty={
              page > 1 && failed.items.length === 0 ? (
                <EmptyState title="이 쪽에는 항목이 없습니다" headingLevel={3} action={<Link to="/system">1쪽으로</Link>} />
              ) : (
                <EmptyState title="보내지 못한 항목이 없습니다." headingLevel={3} />
              )
            }
          />
          <PageNav path="/system" page={failed.page} pageCount={failed.totalPages} onPageChange={move.onPageChange} />
        </section>
      )}
      <p role="status" className="pd-sr-only">
        {move.announcement}
      </p>
    </div>
  );
}
