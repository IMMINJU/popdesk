// The parts of C-18 시스템 상태 (4b screens.md 1~4절): the sandbox band, the channel table,
// OIDC sign-in (11 screens.md C-18), receiving email (12 screens.md C-18), the `failed` rows with 다시 보내기, 시험 메일 and the cleanup jobs. They draw what they are
// given; the screen asks the server and keeps the states. No secret values.
import { Alert, Badge, Button, DataTable, RelativeTime, type DataTableColumn } from '@popdesk/ui';
import type { ReactNode } from 'react';
import type { Schemas } from '../api';
import { TEXT } from '../app/messages';
import { formatNumber, t, texts, tf } from '../app/locale';

type SystemStatus = Schemas['SystemStatus'];
type ChannelStatus = Schemas['ChannelStatus'];
type FailedDelivery = Schemas['FailedDelivery'];
type CleanupJobStatus = Schemas['CleanupJobStatus'];
type TestMailResult = Schemas['TestMailResult'];
type OidcStatus = Schemas['OidcStatus'];
type MailInStatus = Schemas['MailInStatus'];

const DOCS = 'https://github.com/IMMINJU/popdesk/blob/main/docs/operate/configuration.md';
/** Where an operator reads which environment variables turn a channel on. */
const CONFIG_DOC = DOCS;
/** Where an operator reads what a sandbox turns off. */
const SANDBOX_DOC = `${DOCS}#a-sandbox`;
/** Where an operator reads which environment variables turn receiving email on. */
const MAIL_IN_DOC = `${DOCS}#receiving-email`;

const CHANNEL_WORDS: Readonly<Record<FailedDelivery['channel'], string>> = texts({ MAIL: 'system.mail', WEBHOOK: 'system.webhook', AUDIT_FORWARD: 'system.forward' });
const CHANNEL_KEYS = { MAIL: 'mail', WEBHOOK: 'webhook', AUDIT_FORWARD: 'auditForward' } as const;

/** The band under the title while the installation is a sandbox. */
export function SandboxBand(props: { mailToSet: boolean }) {
  return (
    <Alert tone="info">
      {props.mailToSet ? t('system.sandboxMailTo') : t('system.sandboxNoMail')}
    </Alert>
  );
}

interface ChannelRow {
  key: 'mail' | 'webhook' | 'auditForward';
  name: string;
  /** What the state says while the channel is not set up. */
  off: string;
  hint: string;
  status: ChannelStatus;
  /** The sandbox turned it off, not a missing setting (screens.md 6절 1). */
  bySandbox: boolean;
}

const channelColumns = (): DataTableColumn<ChannelRow>[] => [
  { key: 'name', header: t('system.channel'), cell: (c) => <span className="pd-strong">{c.name}</span> },
  {
    key: 'enabled',
    header: t('system.state'),
    cell: (c) =>
      c.status.enabled ? (
        <span className="pd-on">{t('system.on')}</span>
      ) : (
        <span className="pd-inline">
          <Badge tone="neutral">{c.bySandbox ? t('system.offSandbox') : c.off}</Badge>
          <a href={c.bySandbox ? SANDBOX_DOC : CONFIG_DOC} target="_blank" rel="noopener noreferrer">
            {c.bySandbox ? t('system.sandboxGuide') : c.hint}
          </a>
        </span>
      ),
  },
  {
    key: 'last',
    header: t('system.lastSuccess'),
    cell: (c) => (c.status.lastSuccessAt ? <RelativeTime dateTime={c.status.lastSuccessAt} focusable={false} /> : <span className="pd-muted">{t('system.none')}</span>),
  },
  { key: 'queued', header: t('system.queued'), cell: (c) => formatNumber(c.status.queued), align: 'end' },
  { key: 'failed', header: t('system.failedCount'), cell: (c) => formatNumber(c.status.failed), align: 'end' },
];

/** The three channels. Without `status` (the first load) only the column heads, or the skeleton rows. */
export function ChannelTable(props: { status: SystemStatus | undefined; loading?: boolean; skeletonRows?: number }) {
  const s = props.status;
  // A sandbox turns the webhook and the outside copy off always, and mail when it has no address to send to.
  const rows: ChannelRow[] = s
    ? [
        { key: 'mail', name: t('system.mailOut'), off: t('system.notSet'), hint: t('system.mailHint'), status: s.mail, bySandbox: s.sandbox && !s.sandboxMailToSet },
        { key: 'webhook', name: t('system.webhook'), off: t('system.notSet'), hint: t('system.webhookHint'), status: s.webhook, bySandbox: s.sandbox },
        { key: 'auditForward', name: t('system.forward'), off: t('system.off'), hint: t('system.forwardHint'), status: s.auditForward, bySandbox: s.sandbox },
      ]
    : [];
  return (
    <DataTable
      caption={t('system.channels')}
      columns={channelColumns()}
      rows={rows}
      rowKey={(c) => c.key}
      status={props.loading ? 'loading' : 'ready'}
      skeletonRows={props.skeletonRows ?? 0}
      density="compact"
    />
  );
}

const OIDC_APPS_WORDS: Readonly<Record<OidcStatus['apps'][number], string>> = texts({ CONSOLE: 'system.console', PORTAL: 'system.portal' });
const OIDC_FAILURE_WORDS: Readonly<Record<NonNullable<OidcStatus['lastFailure']>['kind'], string>> = texts({
  DISCOVERY: 'system.oidcDiscovery',
  TOKEN: 'system.oidcToken',
  ID_TOKEN: 'system.oidcIdToken',
});

/** OIDC sign-in, right after the channels: off, or the apps it is on for and what the app counted since it started. */
export function OidcPart(props: { oidc: OidcStatus }) {
  const { oidc } = props;
  return (
    <section className="pd-section" aria-labelledby="oidc-title">
      <h2 id="oidc-title" className="pd-section-title">
        {t('system.oidcTitle')}
      </h2>
      {oidc.enabled ? (
        <>
          <ul className="pd-oidc-status">
            <li>{tf('system.oidcOnFor', { apps: oidc.apps.map((app) => OIDC_APPS_WORDS[app]).join(t('system.appsJoin')) })}</li>
            <li>
              {t('system.lastSuccess')}:{' '}
              {oidc.lastSuccessAt === null ? <span className="pd-muted">{t('system.oidcNoSuccess')}</span> : <RelativeTime dateTime={oidc.lastSuccessAt} />}
            </li>
            <li>{tf('system.providerFailures', { count: formatNumber(oidc.providerFailures) })}</li>
            {oidc.lastFailure !== null && (
              <li>
                {t('system.lastFailure')}: {OIDC_FAILURE_WORDS[oidc.lastFailure.kind]}, <RelativeTime dateTime={oidc.lastFailure.at} />
              </li>
            )}
          </ul>
          <p className="pd-muted pd-section-note">{t('system.countsReset')}</p>
        </>
      ) : (
        <p>
          <Badge tone="neutral">{t('system.off')}</Badge>
        </p>
      )}
    </section>
  );
}

const MAIL_IN_FAILURE_WORDS: Readonly<Record<NonNullable<MailInStatus['lastFailure']>['kind'], string>> = texts({
  CONNECT: 'system.mailInConnect',
  AUTH: 'system.mailInAuth',
  FOLDER: 'system.mailInFolder',
  UNSUPPORTED: 'system.mailInUnsupported',
});

/**
 * Receiving email, right after OIDC: off and why, or the last round, the failed rounds and the emails left to try
 * again or quarantined. No folder name and no server address (the status has none).
 */
export function MailInPart(props: { mailIn: MailInStatus }) {
  const { mailIn } = props;
  return (
    <section className="pd-section" aria-labelledby="mail-in-title">
      <h2 id="mail-in-title" className="pd-section-title">
        {t('system.mailInTitle')}
      </h2>
      {mailIn.enabled ? (
        <>
          <ul className="pd-oidc-status">
            <li>
              <span className="pd-on">{t('system.on')}</span>
            </li>
            <li>
              {t('system.lastSuccess')}:{' '}
              {mailIn.lastSuccessAt === null ? <span className="pd-muted">{t('system.never')}</span> : <RelativeTime dateTime={mailIn.lastSuccessAt} />}
            </li>
            <li>{tf('system.roundFailures', { count: formatNumber(mailIn.roundFailures) })}</li>
            {mailIn.lastFailure !== null && (
              <li>
                {t('system.lastFailure')}: {MAIL_IN_FAILURE_WORDS[mailIn.lastFailure.kind]}, <RelativeTime dateTime={mailIn.lastFailure.at} />
              </li>
            )}
            {mailIn.retrying > 0 && <li>{tf('system.retrying', { count: formatNumber(mailIn.retrying) })}</li>}
            {mailIn.quarantined90d > 0 && (
              <li>
                {tf('system.quarantined', { count: formatNumber(mailIn.quarantined90d) })}
                <span className="pd-muted pd-status-note">
                  {t('system.quarantineBefore')}
                  <code className="pd-code">IMAP_QUARANTINE_FOLDER</code>
                  {t('system.quarantineAfter')}
                </span>
              </li>
            )}
          </ul>
          <p className="pd-muted pd-section-note">{t('system.mailInCountsReset')}</p>
        </>
      ) : mailIn.offReason === 'SANDBOX' ? (
        <p>
          <Badge tone="neutral">{t('system.offSandbox')}</Badge>
        </p>
      ) : (
        <p className="pd-inline">
          <Badge tone="neutral">{t('system.notSet')}</Badge>
          <a href={MAIL_IN_DOC} target="_blank" rel="noopener noreferrer">
            {t('system.mailInHint')}
          </a>
        </p>
      )}
    </section>
  );
}

export interface FailedTableProps {
  rows: readonly FailedDelivery[];
  total: number;
  /** The channels as they are now: a row of a channel that is off cannot be sent again. */
  status: SystemStatus;
  /** The IDs of the rows being sent again. */
  sending: ReadonlySet<number>;
  onResend: (row: FailedDelivery) => void;
  empty: ReactNode;
}

/** The `failed` rows, each with 다시 보내기 in its last cell. */
export function FailedTable(props: FailedTableProps) {
  const columns: DataTableColumn<FailedDelivery>[] = [
    { key: 'channel', header: t('system.channel'), cell: (f) => <span id={`failed-${String(f.id)}-channel`}>{CHANNEL_WORDS[f.channel]}</span> },
    {
      key: 'created',
      header: t('system.created'),
      cell: (f) => (
        <span id={`failed-${String(f.id)}-created`}>
          <RelativeTime dateTime={f.createdAt} focusable={false} />
        </span>
      ),
    },
    {
      key: 'error',
      header: t('system.lastError'),
      cell: (f) =>
        f.lastErrorKind === 'RATE_LIMITED' ? (
          <span className="pd-two-lines pd-kind-note">
            <code className="pd-code">{f.lastErrorKind}</code>
            <span className="pd-muted">{t('system.rateLimited')}</span>
          </span>
        ) : (
          <code className="pd-code">{f.lastErrorKind}</code>
        ),
    },
    {
      key: 'recipient',
      header: t('system.recipient'),
      cell: (f) => (f.recipientAccountId === null ? <span className="pd-muted">{t('system.none')}</span> : String(f.recipientAccountId)),
      align: 'end',
    },
    {
      key: 'auditEntry',
      header: t('system.auditEntry'),
      cell: (f) => (f.auditEntryId === null ? <span className="pd-muted">{t('system.none')}</span> : String(f.auditEntryId)),
      align: 'end',
    },
    {
      key: 'action',
      header: t('system.action'),
      cell: (f) =>
        props.status[CHANNEL_KEYS[f.channel]].enabled ? (
          <Button
            variant="secondary"
            size="sm"
            pending={props.sending.has(f.id)}
            pendingLabel={TEXT.sending}
            describedBy={`failed-${String(f.id)}-channel failed-${String(f.id)}-created`}
            onClick={() => {
              props.onResend(f);
            }}
          >
            {t('system.resend')}
          </Button>
        ) : (
          <span className="pd-muted">{t('system.channelOff')}</span>
        ),
    },
  ];
  return (
    <DataTable
      caption={tf('system.failedCaption', { count: props.total })}
      columns={columns}
      rows={props.rows}
      rowKey={(f) => String(f.id)}
      density="compact"
      empty={props.empty}
    />
  );
}

/** Where 시험 메일 stands: nothing sent yet, being sent, or what the server said about the last one. */
export type TestMailState = { step: 'idle' } | { step: 'sending' } | { step: 'done'; result: TestMailResult };

export interface TestMailProps {
  /** `mail.enabled`: off without SMTP settings and in a sandbox without an address (screens.md 6절 3). */
  enabled: boolean;
  sandbox: boolean;
  state: TestMailState;
  /** Held back while a 429 countdown runs. */
  blocked?: boolean;
  onSend: () => void;
}

/** 시험 메일: one button, and what the SMTP server answered as plain text. */
export function TestMail(props: TestMailProps) {
  const { state } = props;
  const result = state.step === 'done' ? state.result : null;
  return (
    <section className="pd-section" aria-labelledby="test-mail-title">
      <h2 id="test-mail-title" className="pd-section-title">
        {t('system.testMail')}
      </h2>
      {props.enabled ? (
        <>
          <p className="pd-section-note">{props.sandbox ? t('system.testToSandbox') : t('system.testToMe')}</p>
          <div className="pd-inline">
            <Button variant="secondary" pending={state.step === 'sending'} pendingLabel={TEXT.sending} blocked={props.blocked === true} onClick={props.onSend}>
              {t('system.sendTest')}
            </Button>
            {state.step === 'sending' && <span className="pd-muted pd-section-note">{t('system.testWait')}</span>}
          </div>
        </>
      ) : (
        <p className="pd-muted pd-section-note">{t('system.mailDisabled')}</p>
      )}
      {result !== null &&
        (result.ok ? (
          <Alert tone="success">{t('system.testSent')}</Alert>
        ) : (
          <Alert tone="error">
            {t('system.testFailed')} {result.errorKind !== null && <code className="pd-code">{result.errorKind}</code>}
          </Alert>
        ))}
      {/* As the server gave it: text only, not trimmed (screens.md 6절 12). */}
      {result !== null && result.serverReply !== null && (
        <div className="pd-server-reply">
          <p className="pd-muted">{t('system.serverReply')}</p>
          <pre tabIndex={0}>{result.serverReply}</pre>
        </div>
      )}
    </section>
  );
}

const jobColumns = (): DataTableColumn<CleanupJobStatus>[] => [
  { key: 'name', header: t('system.job'), cell: (j) => <code className="pd-code">{j.name}</code> },
  {
    key: 'lastRun',
    header: t('system.lastRun'),
    cell: (j) => (j.lastRunAt === null ? <span className="pd-muted">{t('system.never')}</span> : <RelativeTime dateTime={j.lastRunAt} focusable={false} />),
  },
  { key: 'deleted', header: t('system.deleted'), cell: (j) => (j.deleted === null ? <span className="pd-muted">{t('system.never')}</span> : formatNumber(j.deleted)), align: 'end' },
];

/** The cleanup jobs by name. Nothing when there is none. */
export function CleanupJobs(props: { jobs: readonly CleanupJobStatus[] }) {
  if (props.jobs.length === 0) return null;
  const jobs = [...props.jobs].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  return (
    <section className="pd-section" aria-labelledby="cleanup-title">
      <h2 id="cleanup-title" className="pd-section-title">
        {t('system.cleanupJobs')}
      </h2>
      <DataTable caption={t('system.cleanupJobs')} columns={jobColumns()} rows={jobs} rowKey={(j) => j.name} density="compact" />
    </section>
  );
}
