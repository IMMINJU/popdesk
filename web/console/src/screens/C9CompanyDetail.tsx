// C-9 고객사 상세 (관리자, screens.md 4절, 1a screens.md 2절). Info with 고치기,
// status (멈춤, 다시 사용, 보관), 소속 고객 with 더하기·빼기, and 배정 상담원 with
// 상담원 배정 and 빼기 (PROJ-1~4). Slice 2 adds the manager mark of a customer and
// the open membership requests with 승인·거절 (2-manager screens.md 2절, MGR-1, MGR-6).
import {
  Alert,
  Avatar,
  Badge,
  Button,
  ConfirmDialog,
  DataTable,
  Dialog,
  EmptyState,
  RadioGroup,
  RelativeTime,
  Skeleton,
  StatusBadge,
  TextField,
  type AccountStatus,
  type DataTableColumn,
} from '@popdesk/ui';
import { useEffect, useRef, useState, type SubmitEvent } from 'react';
import { Link, useLocation, useParams } from 'react-router';
import { api, type Schemas } from '../api';
import { AccountFinder } from '../app/AccountFinder';
import { FieldSummary, SaveFailure } from '../app/bands';
import { ClearAssignments } from '../app/ClearAssignments';
import { CompanyExports } from '../app/CompanyExports';
import { CompanyLabels } from '../app/CompanyLabels';
import { useMainBusy } from '../app/busy';
import { useFieldErrors, useFocusWhenReady, useLive, useLoad } from '../app/hooks';
import { InviteDialog } from '../app/InviteDialog';
import { LoadFailed, NotFound, PageTitle } from '../app/layout';
import { t, texts, tf } from '../app/locale';
import { TEXT } from '../app/messages';
import { useNavCountRefresh } from '../app/navCount';
import { clearedText, useOpenAssignments } from '../app/openAssignments';
import { photoUrl } from '../app/photos';
import { call, type Failure, type Result } from '../app/result';
import { useToast } from '../app/toastContext';

type Company = Schemas['Company'];
type Member = Schemas['CompanyMember'];
type Account = Schemas['Account'];
type MembershipRequest = Schemas['PendingMembership'];

const LIST_SIZE = 100;

/** C-8's 소속 요청 number links here: the screen opens on the requests. */
const REQUESTS_HASH = '#membership-requests';

const ROLE_WORDS: Readonly<Record<Account['role'], string>> = texts({
  CUSTOMER: 'companyDetail.roleCustomer',
  AGENT: 'companyDetail.roleAgent',
  ADMIN: 'companyDetail.roleAdmin',
});

const COMPANY_ERRORS: Readonly<Record<string, string>> = texts({
  COMPANY_ARCHIVED: 'companyDetail.errArchived',
  COMPANY_NOT_ACTIVE: 'companyDetail.errNotActive',
  NOT_A_CUSTOMER: 'companyDetail.errNotCustomer',
  NOT_FOUND: 'companyDetail.errNotFound',
  // The same words as C-10 (b2 screens.md 1절): another admin anonymized the account after the table was read.
  ACCOUNT_ANONYMIZED: 'companyDetail.errAnonymized',
});

/** Another tab changed the company: the screen is old, so it reads again (screens.md C-9 400). */
function isStale(failure: Failure): boolean {
  return failure.kind === 'rejected' && (failure.code === 'COMPANY_ARCHIVED' || failure.code === 'COMPANY_NOT_ACTIVE');
}

function memberCell(m: Member) {
  return (
    <span className="pd-person">
      <Avatar name={m.name} photoUrl={photoUrl(m.accountId, m.photoUpdatedAt)} size="sm" decorative />
      <span id={`member-${String(m.accountId)}`} title={m.name}>
        {m.name}
      </span>
    </span>
  );
}

function AssignDialog(props: { open: boolean; code: string; assigned: readonly Member[]; onClose: () => void; onAssigned: () => void }) {
  const toast = useToast();
  const [candidates, setCandidates] = useState<Account[] | null>(null);
  const [chosen, setChosen] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [missing, setMissing] = useState(false);
  const assignedIds = new Set(props.assigned.map((m) => m.accountId));

  useEffect(() => {
    if (!props.open) return undefined;
    let live = true;
    void call(api.GET('/api/console/accounts', { params: { query: { role: 'AGENT', size: LIST_SIZE } } })).then((result) => {
      if (!live) return;
      if (result.ok) setCandidates(result.data.items);
      else {
        setCandidates([]);
        setFailure(result.failure);
      }
    });
    return () => {
      live = false;
    };
  }, [props.open]);

  const close = () => {
    setChosen(null);
    setFailure(null);
    setMissing(false);
    setCandidates(null);
    props.onClose();
  };

  // Not yet assigned ACTIVE and INVITED agents (screens.md C-9 상담원 배정).
  const offered = (candidates ?? []).filter(
    (a) => a.role === 'AGENT' && (a.status === 'ACTIVE' || a.status === 'INVITED') && !assignedIds.has(a.id),
  );

  const assign = async () => {
    if (pending) return;
    if (chosen === null) {
      setMissing(true);
      return;
    }
    setFailure(null);
    setPending(true);
    const result = await call(
      api.PUT('/api/console/companies/{code}/agents/{accountId}', { params: { path: { code: props.code, accountId: Number(chosen) } } }),
    );
    setPending(false);
    if (result.ok) {
      toast(t('companyDetail.assignedToast'));
      close();
      props.onAssigned();
      return;
    }
    setFailure(result.failure);
  };

  return (
    <Dialog
      open={props.open}
      title={t('companyDetail.assignTitle')}
      description={t('companyDetail.assignHelp')}
      closeLabel={TEXT.close}
      pending={pending}
      onClose={close}
      footer={
        <>
          <Button variant="secondary" onClick={close}>
            {TEXT.cancel}
          </Button>
          {offered.length > 0 && (
            <Button
              pending={pending}
              pendingLabel={TEXT.sending}
              onClick={() => {
                void assign();
              }}
            >
              {t('companyDetail.assign')}
            </Button>
          )}
        </>
      }
    >
      <SaveFailure failure={failure} known={COMPANY_ERRORS} />
      {candidates === null ? (
        <div aria-hidden="true">
          <Skeleton shape="line" count={3} />
        </div>
      ) : offered.length === 0 ? (
        <p className="pd-muted">{t('companyDetail.noAgents')}</p>
      ) : (
        <RadioGroup
          legend={t('companyDetail.assignLegend')}
          options={offered.map((a) => ({
            value: String(a.id),
            label: `${a.name} · ${a.email}`,
            ...(a.status === 'INVITED' ? { description: t('companyDetail.notAccepted') } : {}),
          }))}
          value={chosen}
          onChange={(v) => {
            setChosen(v);
            setMissing(false);
          }}
          {...(missing ? { error: t('companyDetail.chooseAgent') } : {})}
        />
      )}
    </Dialog>
  );
}

/**
 * 소속 더하기 (S1A-AC-50): finds customer accounts by name or email
 * (AccountFinder). The screen mounts it anew for every opening, so an answer
 * that comes after it closed changes nothing (S1A-AC-58).
 */
function AddCustomerDialog(props: { code: string; members: readonly Member[]; onClose: () => void; onAdded: () => void; onStale: () => void }) {
  const toast = useToast();
  const live = useLive();
  const [chosen, setChosen] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [missing, setMissing] = useState(false);
  const memberIds = new Set(props.members.map((m) => m.accountId));

  const add = async () => {
    if (pending) return;
    if (chosen === null) {
      setMissing(true);
      return;
    }
    setFailure(null);
    setPending(true);
    const result = await call(
      api.PUT('/api/console/companies/{code}/customers/{accountId}', { params: { path: { code: props.code, accountId: Number(chosen) } } }),
    );
    if (result.ok) props.onAdded();
    else if (isStale(result.failure)) props.onStale();
    if (!live()) return;
    setPending(false);
    if (result.ok) {
      toast(tf('companyDetail.addedToast', { name: result.data.name }));
      props.onClose();
      return;
    }
    setFailure(result.failure);
  };

  return (
    <Dialog
      open
      title={t('companyDetail.addTitle')}
      description={t('companyDetail.addHelp')}
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
            pendingLabel={TEXT.sending}
            onClick={() => {
              void add();
            }}
          >
            {t('companyDetail.add')}
          </Button>
        </>
      }
    >
      <div className="pd-form">
        <SaveFailure failure={failure} known={COMPANY_ERRORS} />
        <AccountFinder
          legend={t('companyDetail.addLegend')}
          role="CUSTOMER"
          // An anonymized customer can never be added (S10A-AC-36).
          anonymized={false}
          value={chosen}
          onChange={(v) => {
            setChosen(v);
            setMissing(false);
          }}
          describe={(a) => {
            const member = memberIds.has(a.id);
            const note = member ? t('companyDetail.member') : a.status === 'INVITED' ? t('companyDetail.notAccepted') : a.status === 'SUSPENDED' ? t('companyDetail.suspended') : undefined;
            return { ...(note === undefined ? {} : { description: note }), ...(member ? { disabled: true } : {}) };
          }}
          idleText={t('companyDetail.findIdle')}
          emptyText={t('companyDetail.findEmpty')}
          {...(missing ? { error: t('companyDetail.chooseCustomer') } : {})}
          onFound={() => {
            setChosen(null);
          }}
          onFailure={setFailure}
        />
      </div>
    </Dialog>
  );
}

/** Another admin closed the request the other way, or its manager took it back (S2-AC-37). */
function isClosed(failure: Failure): boolean {
  return failure.kind === 'rejected' && failure.code === 'REQUEST_CLOSED';
}

/** 소속 요청 거절. Mounted anew for every opening, so a late answer finds nothing to change (S2-AC-49). */
function RejectDialog(props: { request: MembershipRequest; onClose: () => void; onChanged: () => void }) {
  const toast = useToast();
  const live = useLive();
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const { request } = props;

  const reject = async () => {
    if (pending) return;
    setPending(true);
    setFailure(null);
    const result = await call(api.POST('/api/console/membership-requests/{id}/reject', { params: { path: { id: request.id } } }));
    const closed = !result.ok && isClosed(result.failure);
    if (result.ok || closed) props.onChanged();
    if (!live()) return;
    setPending(false);
    if (result.ok || closed) {
      toast(closed ? t('companyDetail.requestClosed') : t('companyDetail.rejectedToast'), closed ? 'info' : 'success');
      props.onClose();
      return;
    }
    setFailure(result.failure);
  };

  return (
    <ConfirmDialog
      open
      tone="danger"
      title={t('companyDetail.rejectTitle')}
      message={
        <>
          <p>{t('companyDetail.rejectAsk')}</p>
          <p className="pd-muted">
            {request.name}({request.email})
          </p>
          <SaveFailure failure={failure} known={COMPANY_ERRORS} />
        </>
      }
      confirmLabel={t('companyDetail.reject')}
      cancelLabel={TEXT.cancel}
      pending={pending}
      pendingLabel={TEXT.sending}
      onConfirm={() => {
        void reject();
      }}
      onCancel={props.onClose}
    />
  );
}

/** 소속 빼기·배정 빼기 (S1A-AC-52, 55). Mounted anew for every opening (S1A-AC-58). */
function RemoveDialog(props: { code: string; kind: 'customer' | 'agent'; member: Member; onClose: () => void; onRemoved: () => void }) {
  const toast = useToast();
  const live = useLive();
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const { member, kind } = props;
  // 배정 빼기: the open issues of this company the agent is the assignee of (S3-AC-85).
  const open = useOpenAssignments(
    kind === 'agent'
      ? () => api.GET('/api/console/companies/{code}/agents/{accountId}/open-assignments', { params: { path: { code: props.code, accountId: member.accountId } } })
      : null,
  );
  const [clear, setClear] = useState(false);

  const remove = async () => {
    if (pending) return;
    setPending(true);
    setFailure(null);
    const path = { path: { code: props.code, accountId: member.accountId } };
    let failed: Failure | null = null;
    let cleared: number | undefined;
    if (kind === 'customer') {
      const result = await call(api.DELETE('/api/console/companies/{code}/customers/{accountId}', { params: path }));
      if (!result.ok) failed = result.failure;
    } else {
      const result = await call(
        api.DELETE('/api/console/companies/{code}/agents/{accountId}', { params: { ...path, ...(clear ? { query: { clearAssignments: true } } : {}) } }),
      );
      if (!result.ok) failed = result.failure;
      // A body only when the request asked to clear.
      else if (clear) cleared = (result.data as { issues: number } | undefined)?.issues;
    }
    if (failed === null) props.onRemoved();
    if (!live()) return;
    setPending(false);
    if (failed === null) {
      toast(
        kind === 'customer'
          ? tf('companyDetail.removedCustomerToast', { name: member.name })
          : `${tf('companyDetail.removedAgentToast', { name: member.name })}${clearedText(cleared)}`,
      );
      props.onClose();
      return;
    }
    setFailure(failed);
  };

  return (
    <ConfirmDialog
      open
      tone="danger"
      title={kind === 'customer' ? t('companyDetail.removeCustomerTitle') : t('companyDetail.removeAgentTitle')}
      message={
        <>
          <p>
            {tf('companyDetail.removeSees', { name: member.name })}
            {kind === 'customer' && t('companyDetail.removeOwn')}
          </p>
          {kind === 'customer' && member.manager && <p>{t('companyDetail.removeManager')}</p>}
          <ClearAssignments name={member.name} open={open} scope="company" checked={clear} onChange={setClear} />
          <SaveFailure failure={failure} known={COMPANY_ERRORS} />
        </>
      }
      confirmLabel={t('companyDetail.remove')}
      cancelLabel={TEXT.cancel}
      pending={pending}
      pendingLabel={TEXT.sending}
      onConfirm={() => {
        void remove();
      }}
      onCancel={props.onClose}
    />
  );
}

/** 보관 확인: the confirm button works only once the company code is typed (S1A-AC-44). */
function ArchiveDialog(props: { company: Company; onClose: () => void; onArchived: () => void; onStale: () => void }) {
  const toast = useToast();
  const live = useLive();
  const [typed, setTyped] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const { company } = props;
  const matches = typed === company.code;

  const archive = async () => {
    if (pending || !matches) return;
    setPending(true);
    setFailure(null);
    const result = await call(
      api.PUT('/api/console/companies/{code}/status', { params: { path: { code: company.code } }, body: { status: 'ARCHIVED' } }),
    );
    if (result.ok) props.onArchived();
    else if (isStale(result.failure)) props.onStale();
    if (!live()) return;
    setPending(false);
    if (result.ok) {
      toast(tf('companyDetail.archivedToast', { name: company.name }));
      props.onClose();
      return;
    }
    setFailure(result.failure);
  };

  return (
    <Dialog
      open
      role="alertdialog"
      title={t('companyDetail.archiveTitle')}
      closable={false}
      pending={pending}
      onClose={props.onClose}
      footer={
        <>
          <Button variant="secondary" onClick={props.onClose}>
            {TEXT.cancel}
          </Button>
          <Button
            variant="danger"
            pending={pending}
            pendingLabel={TEXT.sending}
            blocked={!matches}
            onClick={() => {
              void archive();
            }}
          >
            {t('companyDetail.archive')}
          </Button>
        </>
      }
    >
      <form
        className="pd-form"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          void archive();
        }}
      >
        <p>{t('companyDetail.archiveWarning')}</p>
        <SaveFailure failure={failure} known={COMPANY_ERRORS} />
        <TextField
          label={t('companyDetail.companyCode')}
          value={typed}
          onChange={(v) => {
            setTyped(v.toUpperCase());
          }}
          maxLength={10}
          autoComplete="off"
          autoCapitalize="characters"
          spellCheck={false}
          helpText={tf('companyDetail.archiveHelp', { code: company.code })}
        />
      </form>
    </Dialog>
  );
}

/** 고치기 (S1A-AC-43): name and legal name. The code never changes. */
function EditForm(props: { company: Company; onDone: () => void; onSaved: () => Promise<unknown>; onFailure: (failure: Failure) => void }) {
  const toast = useToast();
  const live = useLive();
  const { company } = props;
  const [name, setName] = useState(company.name);
  const [legalName, setLegalName] = useState(company.legalName);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const fields = useFieldErrors({
    name: { id: 'company-name', limits: { max: 100 } },
    legalName: { id: 'company-legal-name', limits: { max: 100 } },
  });

  const submit = async (e: SubmitEvent) => {
    e.preventDefault();
    if (pending) return;
    setFailure(null);
    fields.clear();
    setPending(true);
    const result = await call(api.PATCH('/api/console/companies/{code}', { params: { path: { code: company.code } }, body: { name, legalName } }));
    if (!live()) return;
    if (result.ok) {
      await props.onSaved();
      if (!live()) return;
      toast(t('companyDetail.editedToast'));
      props.onDone();
      return;
    }
    setPending(false);
    const f = result.failure;
    if (f.kind === 'invalid') fields.applyFields(f.fields, f.message);
    else if (isStale(f)) props.onFailure(f);
    else setFailure(f);
  };

  return (
    <form
      className="pd-form pd-detail-edit"
      aria-label={t('companyDetail.editForm')}
      noValidate
      onSubmit={(e) => {
        void submit(e);
      }}
    >
      <FieldSummary summary={fields.summary} />
      <SaveFailure failure={failure} known={COMPANY_ERRORS} />
      <TextField label={t('companyDetail.code')} value={company.code} onChange={() => undefined} readOnly helpText={t('companyDetail.codeFixed')} />
      <TextField
        id="company-name"
        label={t('companyDetail.name')}
        value={name}
        onChange={setName}
        maxLength={100}
        helpText={t('companyDetail.nameHelp')}
        {...(fields.errors.name ? { error: fields.errors.name } : {})}
      />
      <TextField
        id="company-legal-name"
        label={t('companyDetail.legalName')}
        value={legalName}
        onChange={setLegalName}
        maxLength={100}
        helpText={t('companyDetail.legalNameHelp')}
        {...(fields.errors.legalName ? { error: fields.errors.legalName } : {})}
      />
      <div className="pd-form-actions">
        <Button variant="secondary" onClick={props.onDone}>
          {TEXT.cancel}
        </Button>
        <Button type="submit" pending={pending} pendingLabel={TEXT.sending}>
          {t('companyDetail.save')}
        </Button>
      </div>
    </form>
  );
}

interface Detail {
  company: Company;
  customers: Member[];
  agents: Member[];
  /** The open membership requests of this company, oldest first. Empty when the company has none. */
  requests: MembershipRequest[];
}

/** What is open on top of the screen. */
type Opened =
  | { what: 'archive' }
  | { what: 'add-customer' }
  | { what: 'remove'; kind: 'customer' | 'agent'; member: Member }
  | { what: 'reject'; request: MembershipRequest };

export function C9CompanyDetail() {
  const code = useParams().code ?? '';
  // A new company is a new screen: nothing of the one before is kept, and its late answers are dropped (S1A-AC-58).
  return <CompanyDetail key={code} code={code} />;
}

function CompanyDetail(props: { code: string }) {
  const { code } = props;
  const toast = useToast();
  const live = useLive();
  const location = useLocation();
  const recount = useNavCountRefresh();
  const [assigning, setAssigning] = useState(false);
  const [inviting, setInviting] = useState(false);
  const [editing, setEditing] = useState(false);
  const [opened, setOpened] = useState<Opened | null>(null);
  // Counts openings: the key that makes every opening a new dialog.
  const [openings, setOpenings] = useState(0);
  const [statusPending, setStatusPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const agentsHeading = useRef<HTMLHeadingElement>(null);
  const requestsHeading = useRef<HTMLHeadingElement>(null);
  // 승인 and 담당자 지정·해제 do not ask first: one at a time.
  const sending = useRef(false);

  const load = useLoad(async (): Promise<Result<Detail>> => {
    const path = { params: { path: { code } } };
    const list = { params: { path: { code }, query: { size: LIST_SIZE } } };
    const [company, customers, agents] = await Promise.all([
      call(api.GET('/api/console/companies/{code}', path)),
      call(api.GET('/api/console/companies/{code}/customers', list)),
      call(api.GET('/api/console/companies/{code}/agents', list)),
    ]);
    if (!company.ok) return company;
    if (!customers.ok) return customers;
    if (!agents.ok) return agents;
    // A company without open requests is not asked for them (2-manager screens.md 5절).
    let requests: MembershipRequest[] = [];
    if (company.data.pendingMembershipRequests > 0) {
      const asked = await call(api.GET('/api/console/membership-requests', { params: { query: { companyCode: code, size: LIST_SIZE } } }));
      if (!asked.ok) return asked;
      requests = asked.data.items;
    }
    return {
      ok: true,
      data: { company: company.data, customers: customers.data.items, agents: agents.data.items, requests },
      response: company.response,
    };
  }, code);

  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);
  // Coming from C-8's number: focus goes to the requests, which also scrolls there.
  useFocusWhenReady(requestsHeading, location.hash === REQUESTS_HASH && (load.data?.requests.length ?? 0) > 0);

  if (load.status === 'failed' && load.failure?.kind === 'notFound') {
    return <NotFound listHref="/companies" listLabel={t('companyDetail.toList')} />;
  }
  const detail = load.data;
  if (!detail) {
    return (
      <div className="pd-screen">
        {load.status === 'failed' && (
          <LoadFailed
            onRetry={() => {
              void load.reload();
            }}
          />
        )}
        {load.slow && (
          <div className="pd-detail-skeleton" aria-hidden="true">
            <Skeleton shape="line" width="30%" height="24px" />
            <Skeleton shape="line" count={3} />
            <Skeleton shape="block" count={2} />
          </div>
        )}
      </div>
    );
  }

  const { company } = detail;
  const archived = company.status === 'ARCHIVED';

  const reload = () => {
    void load.reload();
  };

  const open = (next: Opened) => {
    setOpenings((n) => n + 1);
    setOpened(next);
  };
  const close = () => {
    setOpened(null);
  };

  // 멈춤 and 다시 사용 do not ask first (screens.md C-9).
  const changeStatus = async (status: 'ACTIVE' | 'SUSPENDED') => {
    if (statusPending) return;
    setStatusPending(true);
    setFailure(null);
    const result = await call(api.PUT('/api/console/companies/{code}/status', { params: { path: { code } }, body: { status } }));
    if (!live()) return;
    if (result.ok || isStale(result.failure)) await load.reload();
    if (!live()) return;
    setStatusPending(false);
    if (result.ok) toast(status === 'SUSPENDED' ? t('companyDetail.suspendedToast') : t('companyDetail.activeToast'));
    else setFailure(result.failure);
  };

  // 담당자로 지정 and 담당자 해제 do not ask first (2-manager screens.md C-9).
  const setManager = async (m: Member, manager: boolean) => {
    if (sending.current) return;
    sending.current = true;
    setFailure(null);
    const path = { params: { path: { code, accountId: m.accountId } } };
    const result = manager
      ? await call(api.PUT('/api/console/companies/{code}/customers/{accountId}/manager', path))
      : await call(api.DELETE('/api/console/companies/{code}/customers/{accountId}/manager', path));
    sending.current = false;
    if (!live()) return;
    // A 400 or a 404 often means the list is old.
    if (result.ok || result.failure.kind === 'rejected' || result.failure.kind === 'notFound') reload();
    if (result.ok) toast(manager ? t('companyDetail.managerSetToast') : t('companyDetail.managerUnsetToast'));
    else setFailure(result.failure);
  };

  // 승인 does not ask first. The customer table gets the account and the side menu's number falls.
  const approve = async (r: MembershipRequest) => {
    if (sending.current) return;
    sending.current = true;
    setFailure(null);
    const result = await call(api.POST('/api/console/membership-requests/{id}/approve', { params: { path: { id: r.id } } }));
    sending.current = false;
    const closed = !result.ok && isClosed(result.failure);
    if (result.ok || closed) recount();
    if (!live()) return;
    if (result.ok || result.failure.kind === 'rejected' || result.failure.kind === 'notFound') reload();
    if (result.ok) toast(t('companyDetail.approvedToast'));
    else if (closed) toast(t('companyDetail.requestClosed'), 'info');
    else setFailure(result.failure);
  };

  const removeButton = (kind: 'customer' | 'agent', m: Member) => (
    <Button
      variant="quiet"
      size="sm"
      describedBy={`member-${String(m.accountId)}`}
      onClick={() => {
        open({ what: 'remove', kind, member: m });
      }}
    >
      {t('companyDetail.remove')}
    </Button>
  );

  const customerColumns: DataTableColumn<Member>[] = [
    { key: 'name', header: t('companyDetail.name'), cell: memberCell },
    { key: 'email', header: t('companyDetail.email'), cell: (m) => m.email },
    // An anonymized account is `SUSPENDED` in the API, so `anonymized` is asked first, as C-10 does (B2-AC-04).
    {
      key: 'status',
      header: t('companyDetail.accountStatus'),
      cell: (m) => (m.anonymized ? <Badge tone="neutral">{t('companyDetail.anonymized')}</Badge> : <StatusBadge kind="account" status={m.status} />),
    },
    { key: 'manager', header: t('companyDetail.manager'), cell: (m) => (m.manager ? <Badge tone="accent">{t('companyDetail.manager')}</Badge> : null) },
    {
      key: 'actions',
      header: <span className="pd-sr-only">{t('companyDetail.actions')}</span>,
      align: 'end',
      cell: (m) => (
        <span className="pd-row-actions">
          {/* An archived company and an anonymized account take no new manager; the mark can still be taken away (S2-AC-06, B2-AC-04). */}
          {(m.manager || (!archived && !m.anonymized)) && (
            <Button
              variant="quiet"
              size="sm"
              describedBy={`member-${String(m.accountId)}`}
              onClick={() => {
                void setManager(m, !m.manager);
              }}
            >
              {m.manager ? t('companyDetail.unsetManager') : t('companyDetail.setManager')}
            </Button>
          )}
          {removeButton('customer', m)}
        </span>
      ),
    },
  ];

  /** An account whose invitation was cancelled is `SUSPENDED` in the API; the badge says 초대 취소됨 as C-10 does. */
  const accountStatus = (r: MembershipRequest): AccountStatus =>
    r.account.status === 'SUSPENDED' && r.account.invitationCancelled ? 'INVITE_CANCELLED' : r.account.status;

  const requestColumns: DataTableColumn<MembershipRequest>[] = [
    {
      key: 'requestedBy',
      header: t('companyDetail.requestedBy'),
      cell: (r) => (
        <span className="pd-two-lines">
          <span>{r.requestedBy.name}</span>
          <span className="pd-muted">{r.requestedBy.email}</span>
        </span>
      ),
    },
    {
      key: 'typed',
      header: t('companyDetail.typed'),
      cell: (r) => (
        <span className="pd-two-lines">
          <span id={`request-${String(r.id)}`}>{r.name}</span>
          <span className="pd-muted">{r.email}</span>
        </span>
      ),
    },
    {
      key: 'account',
      header: t('companyDetail.account'),
      cell: (r) => (
        <span className="pd-two-lines">
          <span className="pd-inline">
            <Link to={`/members?q=${encodeURIComponent(r.email)}`}>{r.account.name}</Link>
            <span>{ROLE_WORDS[r.account.role]}</span>
            <StatusBadge kind="account" status={accountStatus(r)} />
          </span>
          {r.account.role !== 'CUSTOMER' && <span className="pd-muted">{t('companyDetail.supportSide')}</span>}
          {r.account.role === 'CUSTOMER' && r.account.invitationCancelled && (
            <span className="pd-muted">{t('companyDetail.resendAfter')}</span>
          )}
        </span>
      ),
    },
    { key: 'requestedAt', header: t('companyDetail.requestedAt'), cell: (r) => <RelativeTime dateTime={r.requestedAt} focusable={false} /> },
    {
      key: 'actions',
      header: <span className="pd-sr-only">{t('companyDetail.actions')}</span>,
      align: 'end',
      cell: (r) => (
        <span className="pd-row-actions">
          {r.account.role === 'CUSTOMER' && !archived && (
            <Button
              variant="secondary"
              size="sm"
              describedBy={`request-${String(r.id)}`}
              onClick={() => {
                void approve(r);
              }}
            >
              {t('companyDetail.approve')}
            </Button>
          )}
          <Button
            variant="quiet"
            size="sm"
            describedBy={`request-${String(r.id)}`}
            onClick={() => {
              open({ what: 'reject', request: r });
            }}
          >
            {t('companyDetail.reject')}
          </Button>
        </span>
      ),
    },
  ];

  const agentColumns: DataTableColumn<Member>[] = [
    { key: 'name', header: t('companyDetail.name'), cell: memberCell },
    { key: 'email', header: t('companyDetail.email'), cell: (m) => m.email },
    { key: 'actions', header: <span className="pd-sr-only">{t('companyDetail.actions')}</span>, align: 'end', cell: (m) => removeButton('agent', m) },
  ];

  return (
    <div className="pd-screen pd-detail">
      <div className="pd-screen-head">
        <PageTitle titleRef={heading}>{company.name}</PageTitle>
        {!archived && (
          <div className="pd-inline">
            {!editing && (
              <Button
                variant="secondary"
                onClick={() => {
                  setFailure(null);
                  setEditing(true);
                }}
              >
                {t('companyDetail.edit')}
              </Button>
            )}
            <Button
              variant="secondary"
              pending={statusPending}
              pendingLabel={TEXT.sending}
              onClick={() => {
                void changeStatus(company.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE');
              }}
            >
              {company.status === 'ACTIVE' ? t('companyDetail.suspend') : t('companyDetail.reactivate')}
            </Button>
            <Button
              variant="secondary"
              onClick={() => {
                open({ what: 'archive' });
              }}
            >
              {t('companyDetail.archive')}
            </Button>
          </div>
        )}
      </div>
      {load.status === 'failed' && <LoadFailed onRetry={reload} />}
      {company.status === 'SUSPENDED' && (
        <Alert tone="warning" live="status">
          {t('companyDetail.suspendedBand')}
        </Alert>
      )}
      {archived && <Alert tone="info">{t('companyDetail.archivedBand')}</Alert>}
      <SaveFailure failure={failure} known={COMPANY_ERRORS} />
      {editing && !archived ? (
        <EditForm
          company={company}
          onDone={() => {
            setEditing(false);
            heading.current?.focus();
          }}
          onSaved={() => load.reload()}
          onFailure={(f) => {
            // Archived in another tab: the form goes and the screen shows what the company is now.
            setFailure(f);
            setEditing(false);
            reload();
          }}
        />
      ) : (
        <dl className="pd-meta">
          <div>
            <dt>{t('companyDetail.code')}</dt>
            <dd className="pd-key">{company.code}</dd>
          </div>
          <div>
            <dt>{t('companyDetail.name')}</dt>
            <dd>{company.name}</dd>
          </div>
          <div>
            <dt>{t('companyDetail.legalName')}</dt>
            <dd>{company.legalName}</dd>
          </div>
          <div>
            <dt>{t('companyDetail.status')}</dt>
            <dd>
              <StatusBadge kind="project" status={company.status} />
            </dd>
          </div>
        </dl>
      )}

      <section className="pd-section pd-company-members" aria-labelledby="customers-title">
        <div className="pd-section-head">
          <h2 id="customers-title" className="pd-section-title">
            {t('companyDetail.customers')}
          </h2>
          {!archived && (
            <div className="pd-inline">
              <Button
                variant="secondary"
                size="sm"
                onClick={() => {
                  open({ what: 'add-customer' });
                }}
              >
                {t('companyDetail.addCustomer')}
              </Button>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => {
                  setInviting(true);
                }}
              >
                {t('companyDetail.inviteCustomer')}
              </Button>
            </div>
          )}
        </div>
        <DataTable
          caption={tf('companyDetail.customersCaption', { count: detail.customers.length })}
          columns={customerColumns}
          rows={detail.customers}
          rowKey={(m) => String(m.accountId)}
          density="compact"
          empty={<EmptyState title={t('companyDetail.noCustomers')} headingLevel={3} />}
        />
      </section>

      {/* Drawn only while a request is open (2-manager screens.md C-9). */}
      {detail.requests.length > 0 && (
        <section className="pd-section pd-company-requests" aria-labelledby="membership-requests-title">
          <h2 id="membership-requests-title" ref={requestsHeading} className="pd-section-title" tabIndex={-1}>
            {t('companyDetail.requests')}
          </h2>
          <DataTable
            caption={tf('companyDetail.requestsCaption', { count: detail.requests.length })}
            columns={requestColumns}
            rows={detail.requests}
            rowKey={(r) => String(r.id)}
            density="compact"
          />
        </section>
      )}

      <section className="pd-section pd-company-members" aria-labelledby="agents-title">
        <div className="pd-section-head">
          <h2 id="agents-title" ref={agentsHeading} className="pd-section-title" tabIndex={-1}>
            {t('companyDetail.agents')}
          </h2>
          {!archived && (
            <Button
              variant="secondary"
              size="sm"
              onClick={() => {
                setAssigning(true);
              }}
            >
              {t('companyDetail.assignTitle')}
            </Button>
          )}
        </div>
        <DataTable
          caption={tf('companyDetail.agentsCaption', { count: detail.agents.length })}
          columns={agentColumns}
          rows={detail.agents}
          rowKey={(m) => String(m.accountId)}
          density="compact"
          empty={<EmptyState title={t('companyDetail.noAssigned')} headingLevel={3} />}
        />
      </section>

      <CompanyLabels code={company.code} archived={archived} />
      <CompanyExports key={company.code} code={company.code} name={company.name} />

      <AssignDialog
        open={assigning}
        code={company.code}
        assigned={detail.agents}
        onClose={() => {
          setAssigning(false);
        }}
        onAssigned={() => {
          // Back to the list heading once the list is in again (screens.md C-9).
          void load.reload().then(() => {
            agentsHeading.current?.focus();
          });
        }}
      />
      <InviteDialog
        open={inviting}
        preset={{ role: 'CUSTOMER', companyCodes: [company.code] }}
        onClose={() => {
          setInviting(false);
        }}
        onInvited={reload}
      />
      {opened?.what === 'archive' && <ArchiveDialog key={openings} company={company} onClose={close} onArchived={reload} onStale={reload} />}
      {opened?.what === 'add-customer' && (
        <AddCustomerDialog key={openings} code={company.code} members={detail.customers} onClose={close} onAdded={reload} onStale={reload} />
      )}
      {opened?.what === 'remove' && (
        <RemoveDialog key={openings} code={company.code} kind={opened.kind} member={opened.member} onClose={close} onRemoved={reload} />
      )}
      {opened?.what === 'reject' && (
        <RejectDialog
          key={openings}
          request={opened.request}
          onClose={close}
          onChanged={() => {
            recount();
            reload();
          }}
        />
      )}
    </div>
  );
}
