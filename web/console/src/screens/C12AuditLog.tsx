// C-12 감사 로그 (관리자, 1b screens.md 1절). The entries newest first with four
// filters that live in the address, 내보내기 (CSV, JSON Lines) and 무결성 확인.
// Read only: an entry is never changed here (S1B-AC-08).
import {
  Alert,
  Button,
  CloseIcon,
  DataTable,
  Dialog,
  EmptyState,
  IconButton,
  RelativeTime,
  Select,
  TextField,
  type DataTableColumn,
  type SelectOption,
} from '@popdesk/ui';
import type { ConsolePaths } from '@popdesk/api/console';
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api, type Schemas } from '../api';
import { AccountFinder } from '../app/AccountFinder';
import {
  accountWord,
  changeLines,
  entryKindWord,
  KIND_GROUPS,
  kindsOfChoice,
  kindWord,
  NOT_SIGN_IN,
  systemWord,
  targetOf,
  targetWord,
  type Target,
} from '../app/auditWords';
import { SaveFailure } from '../app/bands';
import { useMainBusy } from '../app/busy';
import { startExport } from '../app/exportDownload';
import { useLive, useLoad } from '../app/hooks';
import { LoadFailed, NoPermission, PageTitle } from '../app/layout';
import { formatNumber, screenLocale, t, texts, tf } from '../app/locale';
import { TEXT } from '../app/messages';
import { PageNav } from '../app/PageNav';
import { PAGE_SIZE, pageHref, usePageMove, usePageParam } from '../app/paging';
import { call, type Failure } from '../app/result';
import { useToLogin } from '../app/sessionContext';

type AuditEntry = Schemas['AuditEntry'];
type AuditVerification = Schemas['AuditVerification'];
type ListQuery = NonNullable<ConsolePaths['/api/console/audit-log']['get']['parameters']['query']>;
type FilterQuery = Omit<ListQuery, 'page' | 'size'>;
type ExportFormat = 'CSV' | 'JSONL';

const PATH = '/audit-log';
const EXPORT_PATH = '/api/console/audit-log/export';
/** popdesk itself as the one who did it: the first admin, the commands in the container. */
const SYSTEM_ID = 0;
const TARGET_TYPES = ['ACCOUNT', 'COMPANY', 'DATABASE', 'AUDIT_LOG'] as const;
/**
 * The server records an export when the download starts, which the screen
 * cannot see. The list is read again at once and once more after this long.
 */
const EXPORT_RELOAD_MS = 1500;

/** The choices of the 종류 filter, in the screen language. */
function kindOptions(): SelectOption[] {
  return [
    { value: NOT_SIGN_IN, label: t('audit.notSignIn') },
    ...KIND_GROUPS.map((g) => ({ value: g.key, label: g.name })),
    ...KIND_GROUPS.flatMap((g) => Object.entries(g.kinds).map(([value, label]) => ({ value, label, group: g.name }))),
  ];
}

const VERIFY_ERRORS: Readonly<Record<string, string>> = texts({
  AUDIT_VERIFICATION_RUNNING: 'auditLog.verifyRunning',
});

/** The filters as the address holds them. `from` and `to` are days (`YYYY-MM-DD`) in the browser's time zone; `to` is the last day shown. */
interface Filters {
  kind: string;
  actor: number | null;
  target: Target | null;
  from: string;
  to: string;
}

const NO_FILTERS: Filters = { kind: '', actor: null, target: null, from: '', to: '' };

function wholeNumber(raw: string | null): number | null {
  return raw !== null && /^\d+$/.test(raw) ? Number(raw) : null;
}

function day(raw: string | null): string {
  return raw !== null && /^\d{4}-\d{2}-\d{2}$/.test(raw) && !Number.isNaN(dayStart(raw, 0).getTime()) ? raw : '';
}

function readFilters(params: URLSearchParams): Filters {
  const type = params.get('targetType');
  const accountId = wholeNumber(params.get('targetAccountId'));
  const code = params.get('targetCode');
  return {
    kind: params.get('kind') ?? '',
    actor: wholeNumber(params.get('actor')),
    target:
      type !== null && (TARGET_TYPES as readonly string[]).includes(type)
        ? { type, ...(accountId === null ? {} : { accountId }), ...(code === null || code === '' ? {} : { code }) }
        : null,
    from: day(params.get('from')),
    to: day(params.get('to')),
  };
}

function writeFilters(filters: Filters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.kind !== '') params.set('kind', filters.kind);
  if (filters.actor !== null) params.set('actor', String(filters.actor));
  if (filters.target) {
    params.set('targetType', filters.target.type);
    if (filters.target.accountId !== undefined) params.set('targetAccountId', String(filters.target.accountId));
    if (filters.target.code !== undefined) params.set('targetCode', filters.target.code);
  }
  if (filters.from !== '') params.set('from', filters.from);
  if (filters.to !== '') params.set('to', filters.to);
  return params;
}

/** 0:00 of `date` in the browser's time zone, `addDays` days on. */
function dayStart(date: string, addDays: number): Date {
  const [year = 0, month = 1, dayOfMonth = 1] = date.split('-').map(Number);
  return new Date(year, month - 1, dayOfMonth + addDays);
}

function pad(n: number): string {
  return String(n).padStart(2, '0');
}

/** The day of `at` in the browser's time zone, as a date field holds it. */
function dayOf(at: Date): string {
  return `${String(at.getFullYear())}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}`;
}

/** What the list and the export are asked for. 까지 takes in its whole day: it goes out as 0:00 of the next day. */
function queryOf(filters: Filters): FilterQuery {
  const kinds = kindsOfChoice(filters.kind);
  return {
    ...(kinds.length === 0 ? {} : { action: kinds as NonNullable<FilterQuery['action']> }),
    ...(filters.actor === null ? {} : { actorAccountId: filters.actor }),
    ...(filters.target
      ? {
          targetType: filters.target.type as NonNullable<FilterQuery['targetType']>,
          ...(filters.target.accountId === undefined ? {} : { targetAccountId: filters.target.accountId }),
          ...(filters.target.code === undefined ? {} : { targetCode: filters.target.code }),
        }
      : {}),
    ...(filters.from === '' ? {} : { from: dayStart(filters.from, 0).toISOString() }),
    ...(filters.to === '' ? {} : { to: dayStart(filters.to, 1).toISOString() }),
  };
}

/** The address of the download: the same query the `HEAD` was asked with. */
function exportHref(format: ExportFormat, query: FilterQuery): string {
  const params = new URLSearchParams({ format });
  for (const [name, value] of Object.entries(query)) {
    for (const item of Array.isArray(value) ? value : [value]) params.append(name, String(item));
  }
  return `${EXPORT_PATH}?${params.toString()}`;
}

/** "2026년 10월 6일 14:07" in the browser's time zone. */
function minuteText(iso: string): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return iso;
  if (screenLocale() !== 'ko') return new Intl.DateTimeFormat(screenLocale(), { dateStyle: 'long', timeStyle: 'short' }).format(at);
  return `${String(at.getFullYear())}년 ${String(at.getMonth() + 1)}월 ${String(at.getDate())}일 ${pad(at.getHours())}:${pad(at.getMinutes())}`;
}

/** The exact time of an entry, to the second, in the browser's time zone. */
function secondText(iso: string): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return iso;
  return new Intl.DateTimeFormat(screenLocale(), { dateStyle: 'long', timeStyle: 'medium' }).format(at);
}

const ACTOR_FORM_ID = 'audit-actor';

/** 한 사람 필터: finds an account the way C-9 does, with 시스템 on top. Mounted anew for every opening. */
function ActorDialog(props: { actor: number | null; onPick: (accountId: number, name: string) => void; onClose: () => void }) {
  const [chosen, setChosen] = useState<{ value: string; name: string } | null>(
    props.actor === SYSTEM_ID ? { value: String(SYSTEM_ID), name: systemWord() } : null,
  );
  const [failure, setFailure] = useState<Failure | null>(null);
  const [missing, setMissing] = useState(false);

  return (
    <Dialog
      open
      title={t('auditLog.actorTitle')}
      description={t('auditLog.actorHelp')}
      closeLabel={TEXT.close}
      onClose={props.onClose}
      footer={
        <>
          <Button variant="secondary" onClick={props.onClose}>
            {TEXT.cancel}
          </Button>
          <Button type="submit" form={ACTOR_FORM_ID}>
            {t('auditLog.choose')}
          </Button>
        </>
      }
    >
      <form
        id={ACTOR_FORM_ID}
        className="pd-form"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          if (chosen === null) setMissing(true);
          else props.onPick(Number(chosen.value), chosen.name);
        }}
      >
        <SaveFailure failure={failure} />
        <AccountFinder
          legend={t('auditLog.actor')}
          value={chosen?.value ?? null}
          onChange={(value, account) => {
            setChosen({ value, name: account?.name ?? systemWord() });
            setMissing(false);
          }}
          fixed={[{ value: String(SYSTEM_ID), label: systemWord() }]}
          idleText={t('auditLog.actorIdle')}
          emptyText={t('auditLog.actorEmpty')}
          {...(missing ? { error: t('auditLog.actorMissing') } : {})}
          onFound={() => {
            // 시스템 stays on the list whatever was found.
            setChosen((current) => (current?.value === String(SYSTEM_ID) ? current : null));
          }}
          onFailure={setFailure}
        />
      </form>
    </Dialog>
  );
}

type Verify = { state: 'idle' } | { state: 'running' } | { state: 'done'; result: AuditVerification } | { state: 'failed'; failure: Failure };

/** The band of a finished 무결성 확인. It goes when the screen is left: nothing keeps it. */
function VerifyResult(props: { verify: Verify; onShow: (occurredAt: string) => void }) {
  const { verify } = props;
  if (verify.state === 'failed') {
    const { failure } = verify;
    if (failure.kind === 'server' || failure.kind === 'network') {
      return <Alert tone="error">{TEXT.server} {t('auditLog.verifyProxy')}</Alert>;
    }
    return <SaveFailure failure={failure} known={VERIFY_ERRORS} />;
  }
  if (verify.state !== 'done') return null;
  const { result } = verify;
  if (result.valid) {
    return (
      <Alert tone="success">
        <p>{tf('auditLog.verified', { count: formatNumber(result.checkedCount) })}</p>
        <p className="pd-audit-help">{t('auditLog.verifiedHelp')}</p>
      </Alert>
    );
  }
  const broken = result.brokenEntry;
  if (!broken) return <Alert tone="error">{t('auditLog.tailBroken')}</Alert>;
  return (
    <Alert
      tone="error"
      action={
        <Button
          variant="secondary"
          size="sm"
          onClick={() => {
            props.onShow(broken.occurredAt);
          }}
        >
          {t('auditLog.showEntry')}
        </Button>
      }
    >
      {tf('auditLog.brokenAt', { at: minuteText(broken.occurredAt), kind: kindWord(broken.action) ?? broken.action })}
    </Alert>
  );
}

export function C12AuditLog() {
  const page = usePageParam();
  const [params, setParams] = useSearchParams();
  const live = useLive();
  const toLogin = useToLogin();
  const heading = useRef<HTMLHeadingElement>(null);
  const filters = readFilters(params);
  const filterKey = writeFilters(filters).toString();
  const filtered = filterKey !== '';

  // The dates as the two fields hold them while they differ from the address:
  // a 부터 after the 까지 stays on the screen with its error and is not sent.
  const [dates, setDates] = useState<{ base: string; from: string; to: string } | null>(null);
  const [choosingActor, setChoosingActor] = useState(false);
  // Names of the accounts picked here; the address holds only the ID.
  const [actorNames, setActorNames] = useState<Record<number, string>>({});
  const [exportProblem, setExportProblem] = useState<string | null>(null);
  const [verify, setVerify] = useState<Verify>({ state: 'idle' });

  // An answer to the export's `HEAD` belongs to the filters it was asked with (S1B-AC-38).
  const filterKeyRef = useRef(filterKey);
  useLayoutEffect(() => {
    filterKeyRef.current = filterKey;
  });
  const laterReload = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(
    () => () => {
      clearTimeout(laterReload.current);
    },
    [],
  );

  const load = useLoad(
    () => call(api.GET('/api/console/audit-log', { params: { query: { page, size: PAGE_SIZE, ...queryOf(filters) } } })),
    `${String(page)}\n${filterKey}`,
  );
  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);
  const move = usePageMove(load.data, heading);

  // The role changed meanwhile: the same whole-screen state as for an agent (S1B-AC-35).
  if (load.status === 'failed' && load.failure?.kind === 'forbidden') return <NoPermission />;

  const data = load.status === 'failed' ? undefined : load.data;
  const reload = () => {
    void load.reload();
  };

  /** A changed filter starts again from page 1. */
  const setFilters = (next: Filters) => {
    setExportProblem(null);
    setParams(writeFilters(next));
  };
  const clearFilters = () => {
    setFilters(NO_FILTERS);
    // The button goes away with the state it stood in.
    heading.current?.focus();
  };

  const shownDates = dates?.base === filterKey ? dates : { from: filters.from, to: filters.to };
  const reversed = shownDates.from !== '' && shownDates.to !== '' && shownDates.from > shownDates.to;
  const setDate = (which: 'from' | 'to', value: string) => {
    const next = { ...shownDates, [which]: day(value) };
    if (next.from !== '' && next.to !== '' && next.from > next.to) {
      setDates({ base: filterKey, ...next });
      return;
    }
    setDates(null);
    setFilters({ ...filters, from: next.from, to: next.to });
  };

  const actorName = (accountId: number): string => {
    if (accountId === SYSTEM_ID) return systemWord();
    return actorNames[accountId] ?? data?.items.find((e) => e.actor.accountId === accountId)?.actor.name ?? accountWord(accountId);
  };

  const exportAs = async (format: ExportFormat) => {
    setExportProblem(null);
    const asked = filterKey;
    const current = () => live() && filterKeyRef.current === asked;
    const check = await startExport(exportHref(format, queryOf(filters)), {
      navigate: (url) => {
        if (current()) window.location.assign(url);
      },
      busyText: () => t('auditLog.exportBusy'),
    });
    if (!current()) return;
    if (!check.ok) {
      if (check.status === 401) toLogin();
      else setExportProblem(check.text);
      return;
    }
    reload();
    clearTimeout(laterReload.current);
    laterReload.current = setTimeout(reload, EXPORT_RELOAD_MS);
  };

  const runVerify = async () => {
    setVerify({ state: 'running' });
    const result = await call(api.POST('/api/console/audit-log/verify'));
    // Kept when the filters changed meanwhile, dropped when the screen has gone (S1B-AC-38).
    if (!live()) return;
    setVerify(result.ok ? { state: 'done', result: result.data } : { state: 'failed', failure: result.failure });
  };

  const columns: DataTableColumn<AuditEntry>[] = [
    // One Tab stop less in every row: the exact time is in the opened row.
    { key: 'at', header: t('auditLog.time'), cell: (e) => <RelativeTime dateTime={e.occurredAt} focusable={false} />, width: '8rem' },
    {
      key: 'actor',
      header: t('auditLog.actor'),
      cell: (e) =>
        e.actor.accountId === SYSTEM_ID ? (
          systemWord()
        ) : (
          <span className="pd-audit-actor">
            <span>{e.actor.name ?? accountWord(e.actor.accountId)}</span>
            {e.actor.email !== null && <span className="pd-muted">{e.actor.email}</span>}
          </span>
        ),
    },
    {
      key: 'action',
      header: t('auditLog.action'),
      cell: (e) => {
        const word = entryKindWord(e.action, e.changes);
        return word === undefined ? <code className="pd-code">{e.action}</code> : <span className="pd-audit-one-line">{word}</span>;
      },
    },
    {
      key: 'target',
      header: t('auditLog.target'),
      cell: (e) => {
        const target = targetOf(e.target);
        if (!target) return null;
        // Only a target that names an account or a company can be filtered by.
        if (target.accountId === undefined && target.code === undefined) return <span className="pd-audit-one-line">{targetWord(target)}</span>;
        return (
          <Button
            variant="quiet"
            size="sm"
            onClick={() => {
              setFilters({ ...filters, target });
            }}
          >
            {targetWord(target)}
          </Button>
        );
      },
    },
    {
      key: 'changes',
      header: t('auditLog.changes'),
      cell: (e) => {
        const lines = changeLines(e.action, e.changes);
        if (lines.length === 0) return null;
        return (
          <span className="pd-audit-changes">
            {lines.map((line) => (
              <span key={line}>{line}</span>
            ))}
          </span>
        );
      },
    },
  ];

  const outOfRange = data !== undefined && data.items.length === 0 && page > 1;
  const clearButton = (
    <Button variant="secondary" size="sm" onClick={clearFilters}>
      {t('auditLog.clearFilters')}
    </Button>
  );

  return (
    <div className="pd-screen pd-audit">
      <div className="pd-screen-head">
        <PageTitle titleRef={heading}>{t('auditLog.title')}</PageTitle>
      </div>
      <div className="pd-filters pd-audit-filters">
        <Select
          label={t('auditLog.kind')}
          placeholder={t('auditLog.all')}
          options={kindOptions()}
          value={filters.kind}
          onChange={(kind) => {
            setFilters({ ...filters, kind });
          }}
        />
        <span className="pd-inline">
          <Button
            variant="secondary"
            onClick={() => {
              setChoosingActor(true);
            }}
          >
            {tf('audit.filter.actor', { actor: filters.actor === null ? t('auditLog.all') : actorName(filters.actor) })}
          </Button>
          {filters.actor !== null && (
            <IconButton
              label={t('auditLog.removeActor')}
              icon={<CloseIcon size={14} />}
              size="sm"
              onClick={() => {
                setFilters({ ...filters, actor: null });
              }}
            />
          )}
        </span>
        <div className="pd-audit-dates">
          <TextField
            label={t('auditLog.from')}
            type="date"
            value={shownDates.from}
            onChange={(value) => {
              setDate('from', value);
            }}
          />
          <TextField
            label={t('auditLog.to')}
            type="date"
            value={shownDates.to}
            onChange={(value) => {
              setDate('to', value);
            }}
            {...(reversed ? { error: t('auditLog.reversed') } : {})}
          />
        </div>
        {filters.target && (
          <span className="pd-inline pd-audit-chip">
            <span>{tf('audit.filter.target', { target: targetWord(filters.target) })}</span>
            <IconButton
              label={t('auditLog.removeTarget')}
              icon={<CloseIcon size={14} />}
              size="sm"
              onClick={() => {
                setFilters({ ...filters, target: null });
              }}
            />
          </span>
        )}
      </div>
      <div className="pd-audit-actions">
        <div className="pd-audit-action">
          <span className="pd-inline">
            <Button
              variant="secondary"
              onClick={() => {
                void exportAs('CSV');
              }}
            >
              {t('auditLog.exportCsv')}
            </Button>
            <Button
              variant="secondary"
              onClick={() => {
                void exportAs('JSONL');
              }}
            >
              {t('auditLog.exportJsonl')}
            </Button>
          </span>
          <p className="pd-audit-help">{t('auditLog.exportHelp')}</p>
          {filtered && <p className="pd-audit-help">{t('auditLog.exportFilteredHelp')}</p>}
        </div>
        <div className="pd-audit-action">
          <span className="pd-inline">
            <Button
              variant="secondary"
              pending={verify.state === 'running'}
              pendingLabel={t('auditLog.verifying')}
              onClick={() => {
                void runVerify();
              }}
            >
              {t('auditLog.verify')}
            </Button>
          </span>
          <p className="pd-audit-help">{t('auditLog.verifyHelp')}</p>
        </div>
      </div>
      {exportProblem && <Alert tone="error">{exportProblem}</Alert>}
      <VerifyResult
        verify={verify}
        onShow={(occurredAt) => {
          const at = new Date(occurredAt);
          // The list cannot be asked for one entry by ID: its day is the closest filter.
          if (!Number.isNaN(at.getTime())) setFilters({ ...NO_FILTERS, from: dayOf(at), to: dayOf(at) });
        }}
      />
      {load.status === 'failed' &&
        (load.failure?.kind === 'invalid' ? (
          // A filter value from an old address that the server does not take.
          <Alert tone="error" action={clearButton}>
            {t('auditLog.badFilter')}
          </Alert>
        ) : (
          <LoadFailed onRetry={reload} />
        ))}
      {load.status !== 'failed' && (
        <DataTable
          // A new page or filter folds every opened row.
          key={`${String(page)}\n${filterKey}`}
          caption={tf('auditLog.caption', { title: t('auditLog.title'), count: String(data?.totalElements ?? 0) })}
          columns={columns}
          rows={data?.items ?? []}
          rowKey={(e) => String(e.id)}
          status={firstLoad ? 'loading' : 'ready'}
          skeletonRows={load.slow ? 8 : 0}
          density="compact"
          renderExpanded={(e) => (
            <dl className="pd-meta">
              <div>
                <dt>{t('auditLog.entryId')}</dt>
                <dd>
                  <code className="pd-code">{e.id}</code>
                </dd>
              </div>
              <div>
                <dt>{t('auditLog.time')}</dt>
                <dd>
                  <time dateTime={e.occurredAt}>{secondText(e.occurredAt)}</time>
                </dd>
              </div>
              <div>
                <dt>{t('auditLog.hash')}</dt>
                <dd>
                  <code className="pd-code">{e.hash}</code>
                </dd>
              </div>
              <div>
                <dt>{t('auditLog.prevHash')}</dt>
                <dd>
                  <code className="pd-code">{e.prevHash}</code>
                </dd>
              </div>
            </dl>
          )}
          expandLabel={(e) => tf('auditLog.expand', { id: e.id })}
          empty={
            outOfRange ? (
              <EmptyState title={t('auditLog.emptyPage')} action={<Link to={pageHref(PATH, 1)}>{t('auditLog.toFirstPage')}</Link>} />
            ) : filtered ? (
              <EmptyState title={t('auditLog.noMatch')} action={clearButton} />
            ) : (
              <EmptyState title={t('auditLog.empty')} />
            )
          }
        />
      )}
      {data && <PageNav path={PATH} page={data.page} pageCount={data.totalPages} onPageChange={move.onPageChange} />}
      <p role="status" className="pd-sr-only">
        {move.announcement}
      </p>
      {choosingActor && (
        <ActorDialog
          actor={filters.actor}
          onPick={(accountId, name) => {
            setActorNames((names) => ({ ...names, [accountId]: name }));
            setChoosingActor(false);
            setFilters({ ...filters, actor: accountId });
          }}
          onClose={() => {
            setChoosingActor(false);
          }}
        />
      )}
    </div>
  );
}
