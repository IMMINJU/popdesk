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

const ROLE_WORDS: Record<Account['role'], string> = { CUSTOMER: '고객', AGENT: '상담원', ADMIN: '관리자' };
const REQUEST_CLOSED_TEXT = '이미 처리된 요청입니다';

const SUSPENDED_BAND = '멈춘 고객사입니다. 새 문의를 받지 않습니다.';
const ARCHIVED_BAND = '보관된 고객사입니다. 되돌릴 수 없습니다.';

const COMPANY_ERRORS: Record<string, string> = {
  COMPANY_ARCHIVED: '보관된 고객사라 바꿀 수 없습니다.',
  COMPANY_NOT_ACTIVE: '보관된 고객사에는 더할 수 없습니다.',
  NOT_A_CUSTOMER: '상담 쪽 계정은 고객사에 소속시킬 수 없습니다.',
  NOT_FOUND: '고객사나 계정을 찾을 수 없습니다.',
  // The same words as C-10 (b2 screens.md 1절): another admin anonymized the account after the table was read.
  ACCOUNT_ANONYMIZED: '이미 익명화한 계정입니다.',
};

/** Another tab changed the company: the screen is old, so it reads again (screens.md C-9 400). */
function isStale(failure: Failure): boolean {
  return failure.kind === 'rejected' && (failure.code === 'COMPANY_ARCHIVED' || failure.code === 'COMPANY_NOT_ACTIVE');
}

function memberCell(m: Member) {
  return (
    <span className="pd-person">
      <Avatar name={m.name} photoUrl={photoUrl(m.accountId, m.photoUpdatedAt)} size="sm" decorative />
      <span id={`member-${String(m.accountId)}`}>{m.name}</span>
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
      toast('상담원을 배정했습니다');
      close();
      props.onAssigned();
      return;
    }
    setFailure(result.failure);
  };

  return (
    <Dialog
      open={props.open}
      title="상담원 배정"
      description="배정된 상담원은 이 고객사의 문의를 보고 답합니다."
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
              배정
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
        <p className="pd-muted">배정할 수 있는 상담원이 없습니다. 상담원을 먼저 초대하세요.</p>
      ) : (
        <RadioGroup
          legend="배정할 상담원"
          options={offered.map((a) => ({
            value: String(a.id),
            label: `${a.name} · ${a.email}`,
            ...(a.status === 'INVITED' ? { description: '수락 전' } : {}),
          }))}
          value={chosen}
          onChange={(v) => {
            setChosen(v);
            setMissing(false);
          }}
          {...(missing ? { error: '배정할 상담원을 고르세요.' } : {})}
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
      toast(`${result.data.name} 님을 소속 고객에 더했습니다`);
      props.onClose();
      return;
    }
    setFailure(result.failure);
  };

  return (
    <Dialog
      open
      title="소속 고객 더하기"
      description="이미 있는 고객 계정을 이 고객사에 더합니다. 계정이 없으면 초대하세요."
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
            더하기
          </Button>
        </>
      }
    >
      <div className="pd-form">
        <SaveFailure failure={failure} known={COMPANY_ERRORS} />
        <AccountFinder
          legend="더할 고객"
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
            const note = member ? '소속됨' : a.status === 'INVITED' ? '수락 전' : a.status === 'SUSPENDED' ? '정지' : undefined;
            return { ...(note === undefined ? {} : { description: note }), ...(member ? { disabled: true } : {}) };
          }}
          idleText="이름이나 이메일의 일부를 넣으면 고객 계정을 찾습니다."
          emptyText="맞는 고객 계정이 없습니다."
          {...(missing ? { error: '더할 고객을 고르세요.' } : {})}
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
      toast(closed ? REQUEST_CLOSED_TEXT : '요청을 거절했습니다', closed ? 'info' : 'success');
      props.onClose();
      return;
    }
    setFailure(result.failure);
  };

  return (
    <ConfirmDialog
      open
      tone="danger"
      title="소속 요청 거절"
      message={
        <>
          <p>이 요청을 거절할까요? 요청한 담당자의 목록에서 이 사람이 사라집니다.</p>
          <p className="pd-muted">
            {request.name}({request.email})
          </p>
          <SaveFailure failure={failure} known={COMPANY_ERRORS} />
        </>
      }
      confirmLabel="거절"
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
      toast(kind === 'customer' ? `${member.name} 님을 소속 고객에서 뺐습니다` : `${member.name} 님을 배정에서 뺐습니다${clearedText(cleared)}`);
      props.onClose();
      return;
    }
    setFailure(failed);
  };

  return (
    <ConfirmDialog
      open
      tone="danger"
      title={kind === 'customer' ? '소속 고객 빼기' : '배정 빼기'}
      message={
        <>
          <p>
            {member.name} 님은 이 고객사의 문의를 더 보지 못합니다.{kind === 'customer' && ' 자기가 올린 문의는 계속 봅니다.'}
          </p>
          {kind === 'customer' && member.manager && <p>이 사람은 담당자입니다. 빼면 담당자 표시도 사라집니다.</p>}
          <ClearAssignments name={member.name} open={open} scope="company" checked={clear} onChange={setClear} />
          <SaveFailure failure={failure} known={COMPANY_ERRORS} />
        </>
      }
      confirmLabel="빼기"
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
      toast(`고객사를 보관했습니다(${company.name})`);
      props.onClose();
      return;
    }
    setFailure(result.failure);
  };

  return (
    <Dialog
      open
      role="alertdialog"
      title="고객사 보관"
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
            보관
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
        <p>보관하면 되돌릴 수 없습니다. 새 문의, 소속·배정 더하기, 이름 고치기가 막힙니다.</p>
        <SaveFailure failure={failure} known={COMPANY_ERRORS} />
        <TextField
          label="고객사 코드"
          value={typed}
          onChange={(v) => {
            setTyped(v.toUpperCase());
          }}
          maxLength={10}
          autoComplete="off"
          autoCapitalize="characters"
          spellCheck={false}
          helpText={`보관하려면 이 고객사의 코드 ${company.code}를 넣으세요.`}
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
      toast('고객사 정보를 고쳤습니다');
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
      aria-label="고객사 정보 고치기"
      noValidate
      onSubmit={(e) => {
        void submit(e);
      }}
    >
      <FieldSummary summary={fields.summary} />
      <SaveFailure failure={failure} known={COMPANY_ERRORS} />
      <TextField label="코드" value={company.code} onChange={() => undefined} readOnly helpText="코드는 바꿀 수 없습니다" />
      <TextField
        id="company-name"
        label="이름"
        value={name}
        onChange={setName}
        maxLength={100}
        helpText="콘솔과 포털에 보이는 이름입니다."
        {...(fields.errors.name ? { error: fields.errors.name } : {})}
      />
      <TextField
        id="company-legal-name"
        label="회사명"
        value={legalName}
        onChange={setLegalName}
        maxLength={100}
        helpText="계약서에 쓰는 회사 이름입니다."
        {...(fields.errors.legalName ? { error: fields.errors.legalName } : {})}
      />
      <div className="pd-form-actions">
        <Button variant="secondary" onClick={props.onDone}>
          {TEXT.cancel}
        </Button>
        <Button type="submit" pending={pending} pendingLabel={TEXT.sending}>
          저장
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
    return <NotFound listHref="/companies" listLabel="고객사 목록으로" />;
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
    if (result.ok) toast(status === 'SUSPENDED' ? '고객사를 멈췄습니다' : '고객사를 다시 사용합니다');
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
    if (result.ok) toast(manager ? '담당자로 지정했습니다' : '담당자에서 해제했습니다');
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
    if (result.ok) toast('소속시켰습니다');
    else if (closed) toast(REQUEST_CLOSED_TEXT, 'info');
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
      빼기
    </Button>
  );

  const customerColumns: DataTableColumn<Member>[] = [
    { key: 'name', header: '이름', cell: memberCell },
    { key: 'email', header: '이메일', cell: (m) => m.email },
    // An anonymized account is `SUSPENDED` in the API, so `anonymized` is asked first, as C-10 does (B2-AC-04).
    {
      key: 'status',
      header: '계정 상태',
      cell: (m) => (m.anonymized ? <Badge tone="neutral">익명화됨</Badge> : <StatusBadge kind="account" status={m.status} />),
    },
    { key: 'manager', header: '담당자', cell: (m) => (m.manager ? <Badge tone="accent">담당자</Badge> : null) },
    {
      key: 'actions',
      header: <span className="pd-sr-only">동작</span>,
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
              {m.manager ? '담당자 해제' : '담당자로 지정'}
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
      header: '요청한 사람',
      cell: (r) => (
        <span className="pd-two-lines">
          <span>{r.requestedBy.name}</span>
          <span className="pd-muted">{r.requestedBy.email}</span>
        </span>
      ),
    },
    {
      key: 'typed',
      header: '넣은 이름과 이메일',
      cell: (r) => (
        <span className="pd-two-lines">
          <span id={`request-${String(r.id)}`}>{r.name}</span>
          <span className="pd-muted">{r.email}</span>
        </span>
      ),
    },
    {
      key: 'account',
      header: '계정',
      cell: (r) => (
        <span className="pd-two-lines">
          <span className="pd-inline">
            <Link to={`/members?q=${encodeURIComponent(r.email)}`}>{r.account.name}</Link>
            <span>{ROLE_WORDS[r.account.role]}</span>
            <StatusBadge kind="account" status={accountStatus(r)} />
          </span>
          {r.account.role !== 'CUSTOMER' && <span className="pd-muted">상담 쪽 계정은 고객사에 소속시킬 수 없습니다</span>}
          {r.account.role === 'CUSTOMER' && r.account.invitationCancelled && (
            <span className="pd-muted">승인한 뒤 구성원 목록에서 초대를 다시 보내세요</span>
          )}
        </span>
      ),
    },
    { key: 'requestedAt', header: '요청한 시각', cell: (r) => <RelativeTime dateTime={r.requestedAt} focusable={false} /> },
    {
      key: 'actions',
      header: <span className="pd-sr-only">동작</span>,
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
              승인
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
            거절
          </Button>
        </span>
      ),
    },
  ];

  const agentColumns: DataTableColumn<Member>[] = [
    { key: 'name', header: '이름', cell: memberCell },
    { key: 'email', header: '이메일', cell: (m) => m.email },
    { key: 'actions', header: <span className="pd-sr-only">동작</span>, align: 'end', cell: (m) => removeButton('agent', m) },
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
                고치기
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
              {company.status === 'ACTIVE' ? '멈춤' : '다시 사용'}
            </Button>
            <Button
              variant="secondary"
              onClick={() => {
                open({ what: 'archive' });
              }}
            >
              보관
            </Button>
          </div>
        )}
      </div>
      {load.status === 'failed' && <LoadFailed onRetry={reload} />}
      {company.status === 'SUSPENDED' && (
        <Alert tone="warning" live="status">
          {SUSPENDED_BAND}
        </Alert>
      )}
      {archived && <Alert tone="info">{ARCHIVED_BAND}</Alert>}
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
            <dt>코드</dt>
            <dd className="pd-key">{company.code}</dd>
          </div>
          <div>
            <dt>이름</dt>
            <dd>{company.name}</dd>
          </div>
          <div>
            <dt>회사명</dt>
            <dd>{company.legalName}</dd>
          </div>
          <div>
            <dt>상태</dt>
            <dd>
              <StatusBadge kind="project" status={company.status} />
            </dd>
          </div>
        </dl>
      )}

      <section className="pd-section" aria-labelledby="customers-title">
        <div className="pd-section-head">
          <h2 id="customers-title" className="pd-section-title">
            소속 고객
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
                고객 더하기
              </Button>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => {
                  setInviting(true);
                }}
              >
                이 고객사로 고객 초대
              </Button>
            </div>
          )}
        </div>
        <DataTable
          caption={`소속 고객, ${String(detail.customers.length)}명`}
          columns={customerColumns}
          rows={detail.customers}
          rowKey={(m) => String(m.accountId)}
          density="compact"
          empty={<EmptyState title="아직 고객이 없습니다." headingLevel={3} />}
        />
      </section>

      {/* Drawn only while a request is open (2-manager screens.md C-9). */}
      {detail.requests.length > 0 && (
        <section className="pd-section" aria-labelledby="membership-requests-title">
          <h2 id="membership-requests-title" ref={requestsHeading} className="pd-section-title" tabIndex={-1}>
            소속 요청
          </h2>
          <DataTable
            caption={`소속 요청, ${String(detail.requests.length)}건`}
            columns={requestColumns}
            rows={detail.requests}
            rowKey={(r) => String(r.id)}
            density="compact"
          />
        </section>
      )}

      <section className="pd-section" aria-labelledby="agents-title">
        <div className="pd-section-head">
          <h2 id="agents-title" ref={agentsHeading} className="pd-section-title" tabIndex={-1}>
            배정 상담원
          </h2>
          {!archived && (
            <Button
              variant="secondary"
              size="sm"
              onClick={() => {
                setAssigning(true);
              }}
            >
              상담원 배정
            </Button>
          )}
        </div>
        <DataTable
          caption={`배정 상담원, ${String(detail.agents.length)}명`}
          columns={agentColumns}
          rows={detail.agents}
          rowKey={(m) => String(m.accountId)}
          density="compact"
          empty={<EmptyState title="배정된 상담원이 없습니다. 관리자만 이 고객사의 문의를 봅니다." headingLevel={3} />}
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
