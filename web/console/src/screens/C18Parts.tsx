// The parts of C-18 시스템 상태 (4b screens.md 1~4절): the sandbox band, the channel table,
// OIDC sign-in (11 screens.md C-18), receiving email (12 screens.md C-18), the `failed` rows with 다시 보내기, 시험 메일 and the cleanup jobs. They draw what they are
// given; the screen asks the server and keeps the states. No secret values.
import { Alert, Badge, Button, DataTable, RelativeTime, type DataTableColumn } from '@popdesk/ui';
import type { ReactNode } from 'react';
import type { Schemas } from '../api';
import { TEXT } from '../app/messages';

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

const FORWARD = '감사 바깥 사본';
const CHANNEL_WORDS: Record<FailedDelivery['channel'], string> = { MAIL: '메일', WEBHOOK: '웹훅', AUDIT_FORWARD: FORWARD };
const CHANNEL_KEYS = { MAIL: 'mail', WEBHOOK: 'webhook', AUDIT_FORWARD: 'auditForward' } as const;
const NEVER = '아직 없음';

/** The band under the title while the installation is a sandbox. */
export function SandboxBand(props: { mailToSet: boolean }) {
  return (
    <Alert tone="info">
      {props.mailToSet
        ? '샌드박스로 떠 있습니다. 메일은 설정한 한 주소로만 가고, 웹훅과 감사 바깥 사본은 보내지 않습니다.'
        : '샌드박스로 떠 있습니다. 메일, 웹훅, 감사 바깥 사본을 보내지 않습니다.'}
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
          <Badge tone="neutral">{c.bySandbox ? '꺼짐(샌드박스)' : c.off}</Badge>
          <a href={c.bySandbox ? SANDBOX_DOC : CONFIG_DOC} target="_blank" rel="noopener noreferrer">
            {c.bySandbox ? '샌드박스 안내' : c.hint}
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

/** The three channels. Without `status` (the first load) only the column heads, or the skeleton rows. */
export function ChannelTable(props: { status: SystemStatus | undefined; loading?: boolean; skeletonRows?: number }) {
  const s = props.status;
  // A sandbox turns the webhook and the outside copy off always, and mail when it has no address to send to.
  const rows: ChannelRow[] = s
    ? [
        { key: 'mail', name: '메일 보내기', off: '설정 없음', hint: '메일 서버 설정 방법', status: s.mail, bySandbox: s.sandbox && !s.sandboxMailToSet },
        { key: 'webhook', name: '웹훅', off: '설정 없음', hint: '웹훅 설정 방법', status: s.webhook, bySandbox: s.sandbox },
        { key: 'auditForward', name: FORWARD, off: '꺼짐', hint: `${FORWARD} 설정 방법`, status: s.auditForward, bySandbox: s.sandbox },
      ]
    : [];
  return (
    <DataTable
      caption="보내기 채널"
      columns={channelColumns}
      rows={rows}
      rowKey={(c) => c.key}
      status={props.loading ? 'loading' : 'ready'}
      skeletonRows={props.skeletonRows ?? 0}
      density="compact"
    />
  );
}

const OIDC_APPS_WORDS: Record<OidcStatus['apps'][number], string> = { CONSOLE: '콘솔', PORTAL: '포털' };
const OIDC_FAILURE_WORDS: Record<NonNullable<OidcStatus['lastFailure']>['kind'], string> = {
  DISCOVERY: '제공자 정보를 받지 못함',
  TOKEN: '토큰을 받지 못함',
  ID_TOKEN: 'ID 토큰이 맞지 않음',
};

/** OIDC sign-in, right after the channels: off, or the apps it is on for and what the app counted since it started. */
export function OidcPart(props: { oidc: OidcStatus }) {
  const { oidc } = props;
  return (
    <section className="pd-section" aria-labelledby="oidc-title">
      <h2 id="oidc-title" className="pd-section-title">
        OIDC 로그인
      </h2>
      {oidc.enabled ? (
        <>
          <ul className="pd-oidc-status">
            <li>켜짐: {oidc.apps.map((app) => OIDC_APPS_WORDS[app]).join('과 ')}</li>
            <li>
              마지막 성공: {oidc.lastSuccessAt === null ? <span className="pd-muted">앱이 뜬 뒤 없음</span> : <RelativeTime dateTime={oidc.lastSuccessAt} />}
            </li>
            <li>제공자 실패: {oidc.providerFailures.toLocaleString('ko')}건</li>
            {oidc.lastFailure !== null && (
              <li>
                마지막 실패: {OIDC_FAILURE_WORDS[oidc.lastFailure.kind]}, <RelativeTime dateTime={oidc.lastFailure.at} />
              </li>
            )}
          </ul>
          <p className="pd-muted pd-section-note">수는 앱이 다시 시작하면 0부터 셉니다.</p>
        </>
      ) : (
        <p>
          <Badge tone="neutral">꺼짐</Badge>
        </p>
      )}
    </section>
  );
}

const MAIL_IN_FAILURE_WORDS: Record<NonNullable<MailInStatus['lastFailure']>['kind'], string> = {
  CONNECT: '메일 서버에 연결하지 못함',
  AUTH: '로그인이 거절됨',
  FOLDER: '받는 폴더가 없음',
  UNSUPPORTED: '메일 서버가 메일 옮기기를 지원하지 않음',
};

/**
 * Receiving email, right after OIDC: off and why, or the last round, the failed rounds and the emails left to try
 * again or quarantined. No folder name and no server address (the status has none).
 */
export function MailInPart(props: { mailIn: MailInStatus }) {
  const { mailIn } = props;
  return (
    <section className="pd-section" aria-labelledby="mail-in-title">
      <h2 id="mail-in-title" className="pd-section-title">
        메일 받기
      </h2>
      {mailIn.enabled ? (
        <>
          <ul className="pd-oidc-status">
            <li>
              <span className="pd-on">켜짐</span>
            </li>
            <li>
              마지막 성공: {mailIn.lastSuccessAt === null ? <span className="pd-muted">{NEVER}</span> : <RelativeTime dateTime={mailIn.lastSuccessAt} />}
            </li>
            <li>실패한 회차: {mailIn.roundFailures.toLocaleString('ko')}회</li>
            {mailIn.lastFailure !== null && (
              <li>
                마지막 실패: {MAIL_IN_FAILURE_WORDS[mailIn.lastFailure.kind]}, <RelativeTime dateTime={mailIn.lastFailure.at} />
              </li>
            )}
            {mailIn.retrying > 0 && <li>다시 시도할 메일: {mailIn.retrying.toLocaleString('ko')}통</li>}
            {mailIn.quarantined90d > 0 && (
              <li>
                격리한 메일(90일): {mailIn.quarantined90d.toLocaleString('ko')}통
                <span className="pd-muted pd-status-note">
                  메일함의 격리 폴더(설정 <code className="pd-code">IMAP_QUARANTINE_FOLDER</code>)에서 확인하세요.
                </span>
              </li>
            )}
          </ul>
          <p className="pd-muted pd-section-note">회차와 실패 수는 앱이 다시 시작하면 0부터 셉니다.</p>
        </>
      ) : mailIn.offReason === 'SANDBOX' ? (
        <p>
          <Badge tone="neutral">꺼짐(샌드박스)</Badge>
        </p>
      ) : (
        <p className="pd-inline">
          <Badge tone="neutral">설정 없음</Badge>
          <a href={MAIL_IN_DOC} target="_blank" rel="noopener noreferrer">
            메일 받기 설정 방법
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
    { key: 'channel', header: '채널', cell: (f) => <span id={`failed-${String(f.id)}-channel`}>{CHANNEL_WORDS[f.channel]}</span> },
    {
      key: 'created',
      header: '만든 시각',
      cell: (f) => (
        <span id={`failed-${String(f.id)}-created`}>
          <RelativeTime dateTime={f.createdAt} focusable={false} />
        </span>
      ),
    },
    {
      key: 'error',
      header: '마지막 오류 종류',
      cell: (f) =>
        f.lastErrorKind === 'RATE_LIMITED' ? (
          <span className="pd-two-lines pd-kind-note">
            <code className="pd-code">{f.lastErrorKind}</code>
            <span className="pd-muted">한 주소에 한 시간 50통을 넘겼습니다.</span>
          </span>
        ) : (
          <code className="pd-code">{f.lastErrorKind}</code>
        ),
    },
    {
      key: 'recipient',
      header: '받는 계정 ID',
      cell: (f) => (f.recipientAccountId === null ? <span className="pd-muted">없음</span> : String(f.recipientAccountId)),
      align: 'end',
    },
    {
      key: 'auditEntry',
      header: '감사 항목 ID',
      cell: (f) => (f.auditEntryId === null ? <span className="pd-muted">없음</span> : String(f.auditEntryId)),
      align: 'end',
    },
    {
      key: 'action',
      header: '동작',
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
            다시 보내기
          </Button>
        ) : (
          <span className="pd-muted">채널이 꺼져 있습니다</span>
        ),
    },
  ];
  return (
    <DataTable
      caption={`보내지 못한 항목, ${String(props.total)}건`}
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
        시험 메일
      </h2>
      {props.enabled ? (
        <>
          <p className="pd-section-note">{props.sandbox ? '샌드박스 주소로 메일 한 통을 보냅니다.' : '지금 로그인한 계정의 주소로 메일 한 통을 보냅니다.'}</p>
          <div className="pd-inline">
            <Button variant="secondary" pending={state.step === 'sending'} pendingLabel={TEXT.sending} blocked={props.blocked === true} onClick={props.onSend}>
              시험 메일 보내기
            </Button>
            {state.step === 'sending' && <span className="pd-muted pd-section-note">메일 서버에 따라 30초까지 걸릴 수 있습니다.</span>}
          </div>
        </>
      ) : (
        <p className="pd-muted pd-section-note">메일 보내기가 꺼져 있습니다.</p>
      )}
      {result !== null &&
        (result.ok ? (
          <Alert tone="success">보냈습니다. 메일함을 확인하세요.</Alert>
        ) : (
          <Alert tone="error">
            보내지 못했습니다. {result.errorKind !== null && <code className="pd-code">{result.errorKind}</code>}
          </Alert>
        ))}
      {/* As the server gave it: text only, not trimmed (screens.md 6절 12). */}
      {result !== null && result.serverReply !== null && (
        <div className="pd-server-reply">
          <p className="pd-muted">메일 서버가 한 답</p>
          <pre tabIndex={0}>{result.serverReply}</pre>
        </div>
      )}
    </section>
  );
}

const jobColumns: DataTableColumn<CleanupJobStatus>[] = [
  { key: 'name', header: '작업', cell: (j) => <code className="pd-code">{j.name}</code> },
  {
    key: 'lastRun',
    header: '마지막으로 돈 시각',
    cell: (j) => (j.lastRunAt === null ? <span className="pd-muted">{NEVER}</span> : <RelativeTime dateTime={j.lastRunAt} focusable={false} />),
  },
  { key: 'deleted', header: '지운 수', cell: (j) => (j.deleted === null ? <span className="pd-muted">{NEVER}</span> : j.deleted.toLocaleString('ko')), align: 'end' },
];

/** The cleanup jobs by name. Nothing when there is none. */
export function CleanupJobs(props: { jobs: readonly CleanupJobStatus[] }) {
  if (props.jobs.length === 0) return null;
  const jobs = [...props.jobs].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  return (
    <section className="pd-section" aria-labelledby="cleanup-title">
      <h2 id="cleanup-title" className="pd-section-title">
        정리 작업
      </h2>
      <DataTable caption="정리 작업" columns={jobColumns} rows={jobs} rowKey={(j) => j.name} density="compact" />
    </section>
  );
}
