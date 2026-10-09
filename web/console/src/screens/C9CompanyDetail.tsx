// C-9 고객사 상세 (관리자, screens.md 4절). Info, 소속 고객 (read only), 배정
// 상담원 with 상담원 배정 (PROJ-4). 배정 빼기 comes in slice 1.
import { Alert, Button, DataTable, Dialog, EmptyState, RadioGroup, Skeleton, StatusBadge, type DataTableColumn } from '@popdesk/ui';
import { useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router';
import { api, type Schemas } from '../api';
import { SaveFailure } from '../app/bands';
import { useMainBusy } from '../app/busy';
import { useLoad } from '../app/hooks';
import { InviteDialog } from '../app/InviteDialog';
import { LoadFailed, NotFound, PageTitle } from '../app/layout';
import { TEXT } from '../app/messages';
import { call, type Failure, type Result } from '../app/result';
import { useToast } from '../app/toastContext';

type Company = Schemas['Company'];
type Member = Schemas['CompanyMember'];
type Account = Schemas['Account'];

const LIST_SIZE = 100;

const customerColumns: DataTableColumn<Member>[] = [
  { key: 'name', header: '이름', cell: (m) => m.name },
  { key: 'email', header: '이메일', cell: (m) => m.email },
  { key: 'status', header: '계정 상태', cell: (m) => <StatusBadge kind="account" status={m.status} /> },
];

const agentColumns: DataTableColumn<Member>[] = [
  { key: 'name', header: '이름', cell: (m) => m.name },
  { key: 'email', header: '이메일', cell: (m) => m.email },
];

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
      <SaveFailure failure={failure} />
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

interface Detail {
  company: Company;
  customers: Member[];
  agents: Member[];
}

export function C9CompanyDetail() {
  const code = useParams().code ?? '';
  const [assigning, setAssigning] = useState(false);
  const [inviting, setInviting] = useState(false);
  const agentsHeading = useRef<HTMLHeadingElement>(null);

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
    return { ok: true, data: { company: company.data, customers: customers.data.items, agents: agents.data.items }, response: company.response };
  }, code);

  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);

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

  return (
    <div className="pd-screen pd-detail">
      <div className="pd-screen-head">
        <PageTitle>{company.name}</PageTitle>
      </div>
      {load.status === 'failed' && (
        <LoadFailed
          onRetry={() => {
            void load.reload();
          }}
        />
      )}
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
      {archived && <Alert tone="info">보관한 고객사입니다. 고객을 초대하거나 상담원을 배정할 수 없습니다.</Alert>}

      <section className="pd-section" aria-labelledby="customers-title">
        <div className="pd-section-head">
          <h2 id="customers-title" className="pd-section-title">
            소속 고객
          </h2>
          {!archived && (
            <Button
              variant="secondary"
              size="sm"
              onClick={() => {
                setInviting(true);
              }}
            >
              이 고객사로 고객 초대
            </Button>
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
        onInvited={() => {
          void load.reload();
        }}
      />
    </div>
  );
}
