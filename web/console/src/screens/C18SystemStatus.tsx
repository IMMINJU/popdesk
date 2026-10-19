// C-18 시스템 상태 (관리자, screens.md 4절, 1b screens.md 2절, 4b screens.md). The channels
// (mail, webhook, the outside copy of the audit log), the `failed` rows with 다시 보내기,
// 시험 메일 and the cleanup jobs (OPS-3 part, S0-AC-81, S1B-AC-33, S4B-AC-32~36). No secret values.
// OIDC sign-in and receiving email follow the channels (11, 12 screens.md C-18).
import { EmptyState } from '@popdesk/ui';
import { useRef, useState } from 'react';
import { Link } from 'react-router';
import { api, type Schemas } from '../api';
import { RateLimitBand, SaveFailure } from '../app/bands';
import { useMainBusy } from '../app/busy';
import { useCooldown, useLive, useLoad } from '../app/hooks';
import { LoadFailed, NoPermission, PageTitle } from '../app/layout';
import { PageNav } from '../app/PageNav';
import { PAGE_SIZE, usePageMove, usePageParam } from '../app/paging';
import { call, type Failure, type Result } from '../app/result';
import { useToast } from '../app/toastContext';
import { ChannelTable, CleanupJobs, FailedTable, MailInPart, OidcPart, SandboxBand, TestMail, type TestMailState } from './C18Parts';

type FailedDelivery = Schemas['FailedDelivery'];

interface StatusData {
  status: Schemas['SystemStatus'];
  failed: Schemas['PagedFailedDelivery'];
}

/** A request the server refused, with the screen's own text for the codes it knows. */
interface Refusal {
  failure: Failure;
  known: Record<string, string>;
}

const RESEND_KNOWN = { CHANNEL_DISABLED: '채널이 꺼져 있어 보낼 수 없습니다.' };
const TEST_MAIL_KNOWN = { CHANNEL_DISABLED: '메일 보내기가 꺼져 있습니다.' };

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

  const live = useLive();
  const toast = useToast();
  const cooldown = useCooldown();
  const [refusal, setRefusal] = useState<Refusal | null>(null);
  const [sending, setSending] = useState<ReadonlySet<number>>(new Set());
  const [testMail, setTestMail] = useState<TestMailState>({ step: 'idle' });

  const refused = (failure: Failure, known: Record<string, string>) => {
    if (failure.kind === 'rateLimited') cooldown.start(failure.seconds);
    else setRefusal({ failure, known });
    // The channel went off meanwhile: the channel table says so.
    if (failure.kind === 'rejected' && failure.code === 'CHANNEL_DISABLED') void load.reload();
  };

  // An answer that comes after the screen has gone is dropped: no band, no toast, nothing read again (S4B-AC-36).
  const resend = async (row: FailedDelivery) => {
    if (sending.has(row.id)) return;
    setSending((current) => new Set(current).add(row.id));
    setRefusal(null);
    cooldown.clearEnded();
    const result = await call(api.POST('/api/console/system/failed-deliveries/{id}/resend', { params: { path: { id: row.id } } }));
    if (!live()) return;
    setSending((current) => {
      const next = new Set(current);
      next.delete(row.id);
      return next;
    });
    if (result.ok) {
      await load.reload();
      if (live()) toast('다시 보냈습니다. 결과는 잠시 뒤 이 화면에 반영됩니다.');
      return;
    }
    // Sent already, or cleaned up: the list just reads again.
    if (result.failure.kind === 'notFound') void load.reload();
    else refused(result.failure, RESEND_KNOWN);
  };

  const sendTestMail = async () => {
    if (testMail.step === 'sending' || cooldown.active) return;
    setTestMail({ step: 'sending' });
    setRefusal(null);
    cooldown.clearEnded();
    const result = await call(api.POST('/api/console/system/test-mail'));
    if (!live()) return;
    if (result.ok) {
      setTestMail({ step: 'done', result: result.data });
      return;
    }
    setTestMail({ step: 'idle' });
    refused(result.failure, TEST_MAIL_KNOWN);
  };

  // The role changed meanwhile: the same whole-screen state as for an agent (S0-AC-81).
  if (load.status === 'failed' && load.failure?.kind === 'forbidden') return <NoPermission />;

  const data = load.data;
  const failed = data?.failed;

  return (
    <div className="pd-screen">
      <div className="pd-screen-head">
        <PageTitle titleRef={heading}>시스템 상태</PageTitle>
      </div>
      <SaveFailure failure={refusal?.failure ?? null} {...(refusal ? { known: refusal.known } : {})} />
      <RateLimitBand {...cooldown} />
      {load.status === 'failed' && (
        <LoadFailed
          onRetry={() => {
            void load.reload();
          }}
        />
      )}
      {data?.status.sandbox && <SandboxBand mailToSet={data.status.sandboxMailToSet} />}
      <section className="pd-section" aria-labelledby="channels-title">
        <h2 id="channels-title" className="pd-section-title">
          보내기 채널
        </h2>
        {(load.status !== 'failed' || data) && <ChannelTable status={data?.status} loading={firstLoad} skeletonRows={load.slow ? 3 : 0} />}
      </section>
      {data && <OidcPart oidc={data.status.oidc} />}
      {data && <MailInPart mailIn={data.status.mailIn} />}
      {data && (
        <TestMail
          enabled={data.status.mail.enabled}
          sandbox={data.status.sandbox}
          state={testMail}
          blocked={cooldown.active}
          onSend={() => {
            void sendTestMail();
          }}
        />
      )}
      {data && failed && (
        <section className="pd-section" aria-labelledby="failed-title">
          <h2 id="failed-title" ref={failedHeading} className="pd-section-title" tabIndex={-1}>
            보내지 못한 항목
          </h2>
          <FailedTable
            rows={failed.items}
            total={failed.totalElements}
            status={data.status}
            sending={sending}
            onResend={(row) => {
              void resend(row);
            }}
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
      {data && <CleanupJobs jobs={data.status.cleanupJobs} />}
      <p role="status" className="pd-sr-only">
        {move.announcement}
      </p>
    </div>
  );
}
