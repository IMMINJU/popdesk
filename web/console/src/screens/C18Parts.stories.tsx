// The states of C-18 시스템 상태 (4b screens.md 5절), part by part. The console has no Storybook
// packages of its own, so the stories are plain CSF objects; the Storybook of @popdesk/ui picks
// this file up (packages/ui/src/storybook/main.ts).
import { EmptyState } from '@popdesk/ui';
import type { ReactElement } from 'react';
import type { Schemas } from '../api';
import { ChannelTable, CleanupJobs, FailedTable, SandboxBand, TestMail } from './C18Parts';

type SystemStatus = Schemas['SystemStatus'];
type ChannelStatus = Schemas['ChannelStatus'];
type FailedDelivery = Schemas['FailedDelivery'];

interface Story {
  render: () => ReactElement;
}

const meta = { title: 'Console/C-18 시스템 상태' };
export default meta;

const T0 = '2026-10-08T03:15:00Z';
const noop = () => undefined;

function channel(over: Partial<ChannelStatus> = {}): ChannelStatus {
  return { enabled: true, lastSuccessAt: T0, queued: 0, failed: 0, ...over };
}

const OFF = channel({ enabled: false, lastSuccessAt: null });
const OIDC_OFF: SystemStatus['oidc'] = { enabled: false, apps: [], lastSuccessAt: null, providerFailures: 0, lastFailure: null };
const MAIL_IN_OFF: SystemStatus['mailIn'] = {
  enabled: false,
  offReason: 'NOT_SET',
  lastSuccessAt: null,
  roundFailures: 0,
  lastFailure: null,
  retrying: 0,
  quarantined90d: 0,
};

function status(over: Partial<SystemStatus> = {}): SystemStatus {
  return { mail: channel(), webhook: channel(), auditForward: OFF, sandbox: false, sandboxMailToSet: false, cleanupJobs: [], oidc: OIDC_OFF, mailIn: MAIL_IN_OFF, ...over };
}

function sandboxed(mailToSet: boolean): SystemStatus {
  return status({ sandbox: true, sandboxMailToSet: mailToSet, mail: mailToSet ? channel() : OFF, webhook: OFF, auditForward: OFF });
}

function failed(over: Partial<FailedDelivery> = {}): FailedDelivery {
  return { id: 1, channel: 'MAIL', createdAt: T0, lastErrorKind: 'CONNECTION_REFUSED', recipientAccountId: 11, auditEntryId: null, ...over };
}

const ROWS: FailedDelivery[] = [
  failed({ id: 3, lastErrorKind: 'RATE_LIMITED', recipientAccountId: 12 }),
  failed({ id: 1 }),
  failed({ id: 2, channel: 'WEBHOOK', lastErrorKind: 'HTTP_500', recipientAccountId: null }),
  failed({ id: 4, channel: 'AUDIT_FORWARD', lastErrorKind: 'TIMEOUT', recipientAccountId: null, auditEntryId: 7001 }),
];

const NO_ROWS = <EmptyState title="보내지 못한 항목이 없습니다." headingLevel={3} />;

// 샌드박스 띠
export const SandboxBandOneAddress: Story = { render: () => <SandboxBand mailToSet /> };
export const SandboxBandAllOff: Story = { render: () => <SandboxBand mailToSet={false} /> };

// 채널 표
export const Channels: Story = { render: () => <ChannelTable status={status()} /> };
export const ChannelsOffBySandbox: Story = { render: () => <ChannelTable status={sandboxed(true)} /> };
export const ChannelsOffBySandboxWithoutAddress: Story = { render: () => <ChannelTable status={sandboxed(false)} /> };
/** A sandbox with an address but no SMTP settings: the mail row says 설정 없음, not the sandbox. */
export const ChannelsSandboxMailNotSetUp: Story = { render: () => <ChannelTable status={{ ...sandboxed(true), mail: OFF }} /> };

// `failed` 표
export const FailedRows: Story = {
  render: () => <FailedTable rows={ROWS} total={4} status={status({ auditForward: channel() })} sending={new Set()} onResend={noop} empty={NO_ROWS} />,
};
export const FailedRowSending: Story = {
  render: () => <FailedTable rows={ROWS} total={4} status={status({ auditForward: channel() })} sending={new Set([1])} onResend={noop} empty={NO_ROWS} />,
};
export const FailedRowsOfChannelOff: Story = {
  render: () => <FailedTable rows={ROWS} total={4} status={status({ webhook: OFF })} sending={new Set()} onResend={noop} empty={NO_ROWS} />,
};
export const FailedRowsEmpty: Story = {
  render: () => <FailedTable rows={[]} total={0} status={status()} sending={new Set()} onResend={noop} empty={NO_ROWS} />,
};

// 시험 메일
export const TestMailFirst: Story = { render: () => <TestMail enabled sandbox={false} state={{ step: 'idle' }} onSend={noop} /> };
export const TestMailInSandbox: Story = { render: () => <TestMail enabled sandbox state={{ step: 'idle' }} onSend={noop} /> };
export const TestMailSending: Story = { render: () => <TestMail enabled sandbox={false} state={{ step: 'sending' }} onSend={noop} /> };
export const TestMailSent: Story = {
  render: () => (
    <TestMail enabled sandbox={false} state={{ step: 'done', result: { ok: true, errorKind: null, serverReply: '250 2.0.0 Ok: queued as 4B7XQ1\n' } }} onSend={noop} />
  ),
};
export const TestMailSentWithoutReply: Story = {
  render: () => <TestMail enabled sandbox={false} state={{ step: 'done', result: { ok: true, errorKind: null, serverReply: null } }} onSend={noop} />,
};
export const TestMailFailedWithLongReply: Story = {
  render: () => (
    <TestMail
      enabled
      sandbox={false}
      state={{
        step: 'done',
        result: {
          ok: false,
          errorKind: 'SMTP_REFUSED',
          serverReply:
            '550-5.7.1 <b>relay denied</b> for this sender, the receiving server does not accept mail from this network without authentication\n550 5.7.1 see http://help.invalid/5.7.1 [link](http://help.invalid)\n',
        },
      }}
      onSend={noop}
    />
  ),
};
export const TestMailFailedWithoutReply: Story = {
  render: () => <TestMail enabled sandbox={false} state={{ step: 'done', result: { ok: false, errorKind: 'CONNECTION_FAILED', serverReply: null } }} onSend={noop} />,
};
export const TestMailOff: Story = { render: () => <TestMail enabled={false} sandbox={false} state={{ step: 'idle' }} onSend={noop} /> };

// 정리 작업
export const Cleanup: Story = {
  render: () => (
    <CleanupJobs
      jobs={[
        { name: 'attachment-cleanup', lastRunAt: '2026-10-08T01:00:00Z', deleted: 37 },
        { name: 'idempotency-key-cleanup', lastRunAt: '2026-10-07T18:30:00Z', deleted: 0 },
        { name: 'session-cleanup', lastRunAt: null, deleted: null },
      ]}
    />
  ),
};
