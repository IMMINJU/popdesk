// C-9 "내보내기" (10b screens.md "C-9 내보내기 구역", admins only like the whole screen): the
// company's exports, the latest first, and the dialog that starts one. While one runs the section
// reads its own list again every 5 seconds (10b design.md 4절); the rest of C-9 is not read again.
// An answer that comes after the dialog closed reads the list again, whatever it was.
import { Alert, Badge, Button, Checkbox, DataTable, Dialog, EmptyState, RelativeTime, formatBytes, type BadgeTone, type DataTableColumn } from '@popdesk/ui';
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { api, type Schemas } from '../api';
import { startExport } from './exportDownload';
import { useLive } from './hooks';
import { t, texts, tf } from './locale';
import { TEXT, failureText } from './messages';
import { usePoll } from './poll';
import { call, failureOf, retryAfter, type Failure, type Result } from './result';
import { useToLogin } from './sessionContext';

type CompanyExport = Schemas['CompanyExport'];
type ExportState = CompanyExport['state'];
type ExportFailure = NonNullable<CompanyExport['failure']>;

/** How often the list is read while an export runs (10b acceptance.md 5절 10). */
export const EXPORT_POLL_MS = 5000;

const STATE_WORDS: Readonly<Record<ExportState, string>> = texts({
  RUNNING: 'companyExports.running',
  DONE: 'companyExports.done',
  FAILED: 'companyExports.failed',
  EXPIRED: 'companyExports.expired',
});

const STATE_TONES: Readonly<Record<ExportState, BadgeTone>> = {
  RUNNING: 'accent',
  DONE: 'neutral',
  // The shared Badge keeps its tones (acceptance.md 5절 22); the reason stands next to it.
  FAILED: 'warning',
  EXPIRED: 'neutral',
};

const FAILURE_WORDS: Readonly<Record<ExportFailure, string>> = texts({
  INTERRUPTED: 'companyExports.interrupted',
  STORAGE: 'companyExports.storage',
  DISK: 'companyExports.disk',
  UNEXPECTED: 'companyExports.unexpected',
});

const WORDS = texts({
  finished: 'companyExports.finished',
  failed: 'companyExports.failedText',
  none: 'companyExports.none',
  loadFailed: 'companyExports.loadFailed',
  reloadFailed: 'companyExports.reloadFailed',
  noFile: 'companyExports.noFile',
  body: 'companyExports.body',
  privateLabel: 'companyExports.privateLabel',
  privateHelp: 'companyExports.privateHelp',
  internalLabel: 'companyExports.internalLabel',
  internalHelp: 'companyExports.internalHelp',
});

const EXPORT_ERRORS: Readonly<Record<string, string>> = texts({
  EXPORT_RUNNING: 'companyExports.errRunning',
  NOT_FOUND: 'companyExports.errNotFound',
});

/** The 503 text with the seconds of `Retry-After`, or "잠시 뒤" without the header. */
function busyText(seconds: number | null): string {
  const when = seconds === null ? t('companyExports.busySoon') : tf('companyExports.busySeconds', { seconds });
  return tf('companyExports.busy', { when });
}

function fileOf(e: CompanyExport): string {
  return `/api/console/company-exports/${encodeURIComponent(e.id)}/file`;
}

type Sent = { ok: true } | { ok: false; failure: Failure; text: string };

/** Asks for an export. A 503 is told by its `Retry-After`, which `call` does not keep. */
async function requestExport(code: string, body: { includeInternal: boolean; includePrivate: boolean }): Promise<Sent> {
  let response: Response;
  let error: unknown;
  try {
    ({ response, error } = await api.POST('/api/console/companies/{code}/exports', { params: { path: { code } }, body }));
  } catch {
    const failure: Failure = { kind: 'network' };
    return { ok: false, failure, text: failureText(failure) };
  }
  if (response.ok) return { ok: true };
  const failure = failureOf(response, error);
  if (response.status === 503) return { ok: false, failure, text: busyText(response.headers.has('Retry-After') ? retryAfter(response) : null) };
  return { ok: false, failure, text: failureText(failure, EXPORT_ERRORS) };
}

function ExportDialog(props: { code: string; name: string; onClose: () => void; onAnswered: () => void }) {
  const live = useLive();
  const toLogin = useToLogin();
  const [includePrivate, setIncludePrivate] = useState(false);
  const [includeInternal, setIncludeInternal] = useState(false);
  const [pending, setPending] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const send = async () => {
    if (pending) return;
    setPending(true);
    setProblem(null);
    const sent = await requestExport(props.code, { includeInternal, includePrivate });
    if (!live()) {
      // Closed meanwhile: whatever came, only the list is read again.
      props.onAnswered();
      return;
    }
    setPending(false);
    if (sent.ok) {
      props.onAnswered();
      props.onClose();
      return;
    }
    if (sent.failure.kind === 'unauthenticated') toLogin();
    else setProblem(sent.text);
  };

  return (
    <Dialog
      open
      title={tf('companyExports.dialogTitle', { name: props.name })}
      closeLabel={TEXT.close}
      pending={pending}
      onClose={props.onClose}
      footer={
        <>
          <Button variant="secondary" onClick={props.onClose}>
            {TEXT.cancel}
          </Button>
          <Button
            pending={pending}
            pendingLabel={t('companyExports.requesting')}
            onClick={() => {
              void send();
            }}
          >
            {t('companyExports.export')}
          </Button>
        </>
      }
    >
      <form
        className="pd-form"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          void send();
        }}
      >
        {problem !== null && <Alert tone="error">{problem}</Alert>}
        <p>{WORDS.body}</p>
        <Checkbox label={WORDS.privateLabel} description={WORDS.privateHelp} checked={includePrivate} onChange={setIncludePrivate} />
        <Checkbox label={WORDS.internalLabel} description={WORDS.internalHelp} checked={includeInternal} onChange={setIncludeInternal} />
      </form>
    </Dialog>
  );
}

/**
 * What to say once: an export this section saw running that has now ended or failed. An export
 * that was already over when first read says nothing.
 */
function endedNow(before: ReadonlyMap<string, ExportState>, now: readonly CompanyExport[]): string | null {
  let said: string | null = null;
  for (const e of now) {
    if (before.get(e.id) !== 'RUNNING') continue;
    if (e.state === 'DONE') said = WORDS.finished;
    else if (e.state === 'FAILED') said = WORDS.failed;
  }
  return said;
}

interface ExportList {
  /** The last list read; undefined until the first answer. */
  data: CompanyExport[] | undefined;
  /** The last read failed. With `data` the list stays and a line says so. */
  failed: boolean;
  /** Reads the list. `quiet` (the reads every 5 seconds) does not go to the login on a 401, as the unread count does not. */
  read: (quiet: boolean) => Promise<Result<CompanyExport[]> | undefined>;
}

/**
 * The list of the section's own, not `useLoad`: the reads every 5 seconds give their 401 back to
 * `usePoll`, which stops, while the first read and 다시 시도 go to the login. An answer older than
 * the last one asked is dropped (a late read must not turn a line back to 진행 중).
 */
function useExportList(code: string): ExportList {
  const live = useLive();
  const toLogin = useToLogin();
  const toLoginRef = useRef(toLogin);
  useLayoutEffect(() => {
    toLoginRef.current = toLogin;
  });
  const [list, setList] = useState<{ data: CompanyExport[] | undefined; failed: boolean }>({ data: undefined, failed: false });
  const latest = useRef(0);

  /** Asks for the list; undefined when a later read was asked meanwhile or the section has gone. */
  const ask = useCallback(async () => {
    latest.current += 1;
    const mine = latest.current;
    const result = await call(api.GET('/api/console/companies/{code}/exports', { params: { path: { code } } }));
    return live() && mine === latest.current ? result : undefined;
  }, [code, live]);

  const take = useCallback((result: Result<CompanyExport[]> | undefined, quiet: boolean) => {
    if (result === undefined) return;
    if (!result.ok && result.failure.kind === 'unauthenticated') {
      if (!quiet) toLoginRef.current();
      return;
    }
    setList((current) => (result.ok ? { data: result.data, failed: false } : { data: current.data, failed: true }));
  }, []);

  const read = useCallback(
    async (quiet: boolean) => {
      const result = await ask();
      take(result, quiet);
      return result;
    },
    [ask, take],
  );

  useEffect(() => {
    void ask().then((result) => {
      take(result, false);
    });
  }, [ask, take]);

  return { ...list, read };
}

/** The section of one company; C-9 gives it a new `key` for another company. */
export function CompanyExports(props: { code: string; name: string }) {
  const { code } = props;
  const live = useLive();
  const toLogin = useToLogin();
  const load = useExportList(code);
  const [opened, setOpened] = useState(false);
  const [openings, setOpenings] = useState(0);
  const [spoken, setSpoken] = useState('');
  const [fileProblem, setFileProblem] = useState<string | null>(null);
  const seen = useRef<Map<string, ExportState>>(new Map());

  const exports = load.data ?? [];
  const running = exports.some((e) => e.state === 'RUNNING');
  usePoll(running, EXPORT_POLL_MS, () => load.read(true));

  useEffect(() => {
    if (load.data === undefined) return;
    const said = endedNow(seen.current, load.data);
    seen.current = new Map(load.data.map((e) => [e.id, e.state]));
    if (said !== null) setSpoken(said);
  }, [load.data]);

  const reload = () => {
    if (live()) void load.read(false);
  };

  const download = async (e: CompanyExport) => {
    setFileProblem(null);
    const check = await startExport(fileOf(e), {
      navigate: (url) => {
        if (live()) window.location.assign(url);
      },
      busyText,
      known: { NOT_FOUND: WORDS.noFile },
    });
    if (!live() || check.ok) return;
    if (check.status === 401) toLogin();
    else setFileProblem(check.text);
  };

  const columns: DataTableColumn<CompanyExport>[] = [
    {
      key: 'state',
      header: t('companyExports.status'),
      cell: (e) => <Badge tone={STATE_TONES[e.state]}>{STATE_WORDS[e.state]}</Badge>,
    },
    { key: 'requestedBy', header: t('companyExports.requestedBy'), cell: (e) => <span className="pd-company-export-who">{e.requestedBy.name}</span> },
    { key: 'requestedAt', header: t('companyExports.requestedAt'), cell: (e) => <RelativeTime dateTime={e.requestedAt} focusable={false} /> },
    { key: 'size', header: t('companyExports.size'), cell: (e) => (e.state === 'DONE' && e.size !== null ? formatBytes(e.size) : null) },
    {
      key: 'end',
      header: <span className="pd-sr-only">{t('companyExports.end')}</span>,
      cell: (e) => {
        if (e.state === 'DONE') {
          return (
            <Button
              variant="secondary"
              size="sm"
              onClick={() => {
                void download(e);
              }}
            >
              {t('companyExports.download')}
            </Button>
          );
        }
        if (e.state === 'FAILED' && e.failure !== null) return <span className="pd-muted">{FAILURE_WORDS[e.failure]}</span>;
        return null;
      },
    },
  ];

  const firstFailed = load.failed && load.data === undefined;
  return (
    <section id="exports" className="pd-section pd-company-exports" aria-labelledby="exports-title">
      <div className="pd-section-head">
        <h2 id="exports-title" className="pd-section-title">
          {t('companyExports.title')}
        </h2>
        <Button
          variant="secondary"
          size="sm"
          onClick={() => {
            setOpenings((n) => n + 1);
            setOpened(true);
          }}
        >
          {t('companyExports.export')}
        </Button>
      </div>
      {firstFailed ? (
        <Alert
          tone="error"
          action={
            <Button variant="secondary" size="sm" onClick={reload}>
              {t('companyExports.retry')}
            </Button>
          }
        >
          {WORDS.loadFailed}
        </Alert>
      ) : (
        <DataTable
          caption={tf('companyExports.caption', { count: exports.length })}
          columns={columns}
          rows={exports}
          rowKey={(e) => e.id}
          status={load.data === undefined ? 'loading' : 'ready'}
          density="compact"
          empty={<EmptyState title={WORDS.none} headingLevel={3} />}
        />
      )}
      {load.failed && load.data !== undefined && <p className="pd-muted">{WORDS.reloadFailed}</p>}
      {fileProblem !== null && <Alert tone="error">{fileProblem}</Alert>}
      <p role="status" className="pd-sr-only">
        {spoken}
      </p>
      {opened && (
        <ExportDialog
          key={openings}
          code={code}
          name={props.name}
          onClose={() => {
            setOpened(false);
          }}
          onAnswered={reload}
        />
      )}
    </section>
  );
}
