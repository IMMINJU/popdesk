// C-9 "내보내기" (10b screens.md "C-9 내보내기 구역", admins only like the whole screen): the
// company's exports, the latest first, and the dialog that starts one. While one runs the section
// reads its own list again every 5 seconds (10b design.md 4절); the rest of C-9 is not read again.
// An answer that comes after the dialog closed reads the list again, whatever it was.
import { Alert, Badge, Button, Checkbox, DataTable, Dialog, EmptyState, RelativeTime, formatBytes, type BadgeTone, type DataTableColumn } from '@popdesk/ui';
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { api, type Schemas } from '../api';
import { startExport } from './exportDownload';
import { useLive } from './hooks';
import { TEXT, failureText } from './messages';
import { usePoll } from './poll';
import { call, failureOf, retryAfter, type Failure, type Result } from './result';
import { useToLogin } from './sessionContext';

type CompanyExport = Schemas['CompanyExport'];
type ExportState = CompanyExport['state'];
type ExportFailure = NonNullable<CompanyExport['failure']>;

/** How often the list is read while an export runs (10b acceptance.md 5절 10). */
export const EXPORT_POLL_MS = 5000;

const STATES: Readonly<Record<ExportState, { word: string; tone: BadgeTone }>> = {
  RUNNING: { word: '진행 중', tone: 'accent' },
  DONE: { word: '완료', tone: 'neutral' },
  // The shared Badge keeps its tones (acceptance.md 5절 22); the reason stands next to it.
  FAILED: { word: '실패', tone: 'warning' },
  EXPIRED: { word: '기간 지남', tone: 'neutral' },
};

const FAILURE_WORDS: Readonly<Record<ExportFailure, string>> = {
  INTERRUPTED: '앱이 다시 시작되어 멈췄습니다.',
  STORAGE: '첨부 저장소를 읽지 못했습니다.',
  DISK: '내보내기 파일을 쓸 공간이 없습니다.',
  UNEXPECTED: '알 수 없는 오류로 멈췄습니다.',
};

const FINISHED_TEXT = '내보내기가 끝났습니다';
const FAILED_TEXT = '내보내기가 실패했습니다';
const NONE_TEXT = '아직 내보낸 적이 없습니다.';
const LOAD_FAILED_TEXT = '내보내기 목록을 불러오지 못했습니다.';
const RELOAD_FAILED_TEXT = '목록을 새로 고치지 못했습니다.';
const NO_FILE_TEXT = '파일이 없습니다. 기간이 지났거나 지워졌습니다.';

const BODY_TEXT = '이 고객사의 공개 문의, 공개 댓글, 첨부 파일, 소속 고객(이메일과 이름)을 ZIP 파일 하나로 만듭니다. 다 되면 알림함으로 알립니다.';
const PRIVATE_LABEL = '비공개 문의도 넣기';
const PRIVATE_HELP = '비공개 문의는 같은 고객사의 다른 고객도 보지 못하는 문의입니다.';
const INTERNAL_LABEL = '내부 댓글과 작업도 넣기';
const INTERNAL_HELP = "상담 쪽 사람의 이름은 '지원팀'으로 적습니다. 내부 댓글 본문에 쓴 이름은 그대로 들어갑니다.";

const EXPORT_ERRORS: Record<string, string> = {
  EXPORT_RUNNING: '다른 내보내기가 진행 중입니다. 끝난 뒤 다시 시도해 주세요.',
  NOT_FOUND: '고객사를 찾을 수 없습니다.',
};

/** The 503 text with the seconds of `Retry-After`, or "잠시 뒤" without the header. */
function busyText(seconds: number | null): string {
  return `서버가 잠시 내보내기를 받을 수 없습니다. ${seconds === null ? '잠시 뒤' : `${String(seconds)}초 뒤`} 다시 시도해 주세요.`;
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
      title={`${props.name} 내보내기`}
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
            pendingLabel="요청하는 중…"
            onClick={() => {
              void send();
            }}
          >
            내보내기
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
        <p>{BODY_TEXT}</p>
        <Checkbox label={PRIVATE_LABEL} description={PRIVATE_HELP} checked={includePrivate} onChange={setIncludePrivate} />
        <Checkbox label={INTERNAL_LABEL} description={INTERNAL_HELP} checked={includeInternal} onChange={setIncludeInternal} />
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
    if (e.state === 'DONE') said = FINISHED_TEXT;
    else if (e.state === 'FAILED') said = FAILED_TEXT;
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
      known: { NOT_FOUND: NO_FILE_TEXT },
    });
    if (!live() || check.ok) return;
    if (check.status === 401) toLogin();
    else setFileProblem(check.text);
  };

  const columns: DataTableColumn<CompanyExport>[] = [
    {
      key: 'state',
      header: '상태',
      cell: (e) => <Badge tone={STATES[e.state].tone}>{STATES[e.state].word}</Badge>,
    },
    { key: 'requestedBy', header: '요청한 사람', cell: (e) => <span className="pd-company-export-who">{e.requestedBy.name}</span> },
    { key: 'requestedAt', header: '요청 시각', cell: (e) => <RelativeTime dateTime={e.requestedAt} focusable={false} /> },
    { key: 'size', header: '크기', cell: (e) => (e.state === 'DONE' && e.size !== null ? formatBytes(e.size) : null) },
    {
      key: 'end',
      header: <span className="pd-sr-only">내려받기 또는 실패 까닭</span>,
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
              내려받기
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
          내보내기
        </h2>
        <Button
          variant="secondary"
          size="sm"
          onClick={() => {
            setOpenings((n) => n + 1);
            setOpened(true);
          }}
        >
          내보내기
        </Button>
      </div>
      {firstFailed ? (
        <Alert
          tone="error"
          action={
            <Button variant="secondary" size="sm" onClick={reload}>
              다시 시도
            </Button>
          }
        >
          {LOAD_FAILED_TEXT}
        </Alert>
      ) : (
        <DataTable
          caption={`내보내기, ${String(exports.length)}건`}
          columns={columns}
          rows={exports}
          rowKey={(e) => e.id}
          status={load.data === undefined ? 'loading' : 'ready'}
          density="compact"
          empty={<EmptyState title={NONE_TEXT} headingLevel={3} />}
        />
      )}
      {load.failed && load.data !== undefined && <p className="pd-muted">{RELOAD_FAILED_TEXT}</p>}
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
