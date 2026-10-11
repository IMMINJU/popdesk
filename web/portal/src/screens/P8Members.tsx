// P-8 우리 회사 사람 (2-manager screens.md 1절). A company manager sees the people of
// the company, invites more, sends an invitation again and takes a person out (MGR-2~4).
// Whether the caller is a manager is the server's answer: a 403 on any request turns
// the whole screen into 권한이 없습니다 (S2-AC-11, S2-AC-45). The session's own
// account is read again on every opening: the manager mark may have changed since
// the portal was opened (screens.md 6절).
import {
  Alert,
  Badge,
  Button,
  ConfirmDialog,
  DataTable,
  Dialog,
  EmptyState,
  RowMenu,
  Select,
  StatusBadge,
  TextField,
  type DataTableColumn,
  type RowMenuItem,
} from '@popdesk/ui';
import { useEffect, useRef, useState, type RefObject, type SubmitEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api, type Schemas } from '../api';
import { FieldSummary, RateLimitBand, SaveFailure } from '../app/bands';
import { useMainBusy } from '../app/busy';
import { useCooldown, useFieldErrors, useLive, useLoad } from '../app/hooks';
import { LoadFailed, NoPermission, NotFound, PageTitle } from '../app/layout';
import { t } from '../app/locale';
import { rateLimitedText, TEXT } from '../app/messages';
import { PageNav } from '../app/PageNav';
import { PAGE_SIZE, pageHref, usePageMove, usePageParam } from '../app/paging';
import { call, type Failure } from '../app/result';
import { useSession } from '../app/sessionContext';
import { useToast } from '../app/toastContext';

type Company = Schemas['Company'];
type Member = Schemas['Member'];

const PATH = '/members';

const MEMBER_ERRORS: Record<string, string> = {
  TOO_MANY_PENDING: '수락을 기다리는 초대가 너무 많습니다. 기다리는 초대를 정리한 뒤 다시 해 주세요',
  COMPANY_NOT_ACTIVE: '보관된 고객사에는 초대할 수 없습니다',
  OWN_ACCOUNT: '자기 자신은 뺄 수 없습니다.',
};

const ALREADY_GONE = '이미 목록에 없는 사람입니다';

/** The whole screen in place of the list: not a manager (403), or not the caller's company (404). */
type Gone = 'forbidden' | 'notFound';

/** The manager mark was taken away: every member request answers 403 from then on (S2-AC-11). A stale CSRF token is not that. */
function isDenied(failure: Failure): boolean {
  return failure.kind === 'forbidden' && failure.code !== 'CSRF_TOKEN_INVALID';
}

/** A failed row action. 429 has no countdown here: the text says when to try again. */
function ActionFailure(props: { failure: Failure | null }) {
  if (props.failure?.kind === 'rateLimited') return <Alert tone="error">{rateLimitedText(props.failure.seconds)}</Alert>;
  return <SaveFailure failure={props.failure} known={MEMBER_ERRORS} />;
}

const INVITE_FORM_ID = 'invite-member';

/**
 * 초대 (S2-AC-22, 46). Only an email and a name: the account is a customer of this
 * company. The screen mounts it anew for every opening, so an answer that comes
 * after it closed finds nothing to change (S2-AC-49).
 */
function InviteDialog(props: { company: Company; onClose: () => void; onInvited: () => void; onDenied: () => void }) {
  const toast = useToast();
  const live = useLive();
  const [email, setEmail] = useState('');
  const [name, setName] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [askDiscard, setAskDiscard] = useState(false);
  const cooldown = useCooldown();
  const fields = useFieldErrors({
    email: { id: 'invite-email', limits: { max: 100, format: '이메일' } },
    name: { id: 'invite-name', limits: { max: 50 } },
  });

  const requestClose = () => {
    if ((email !== '' || name !== '') && !pending) setAskDiscard(true);
    else props.onClose();
  };

  const submit = async (e: SubmitEvent) => {
    e.preventDefault();
    if (pending || cooldown.active) return;
    setFailure(null);
    cooldown.clearEnded();
    fields.clear();
    const blank = (['email', 'name'] as const).filter((field) => (field === 'email' ? email : name).trim() === '');
    if (blank.length > 0) {
      fields.applyFields(
        blank.map((field) => ({ field, code: 'REQUIRED' })),
        '',
      );
      return;
    }
    setPending(true);
    const result = await call(api.POST('/api/portal/companies/{code}/members', { params: { path: { code: props.company.code } }, body: { email, name } }));
    if (result.ok) props.onInvited();
    else if (isDenied(result.failure)) props.onDenied();
    if (!live()) return;
    setPending(false);
    if (result.ok) {
      // The same words for a new email and for one that has an account already (S2-AC-46).
      toast('요청을 처리했습니다');
      props.onClose();
      return;
    }
    const f = result.failure;
    if (f.kind === 'invalid') fields.applyFields(f.fields, f.message);
    else if (f.kind === 'rejected' && f.code === 'ALREADY_LISTED') fields.setError('email', '이미 목록에 있는 이메일입니다');
    else if (f.kind === 'rateLimited') cooldown.start(f.seconds);
    else setFailure(f);
  };

  return (
    <>
      <Dialog
        open
        title={`${props.company.name}에 초대`}
        closeLabel={TEXT.close}
        pending={pending}
        onClose={requestClose}
        footer={
          <>
            <Button variant="secondary" onClick={requestClose}>
              {TEXT.cancel}
            </Button>
            <Button type="submit" form={INVITE_FORM_ID} pending={pending} pendingLabel={TEXT.sending} blocked={cooldown.active}>
              초대
            </Button>
          </>
        }
      >
        <form
          id={INVITE_FORM_ID}
          className="pd-form"
          noValidate
          onSubmit={(e) => {
            void submit(e);
          }}
        >
          <FieldSummary summary={fields.summary} />
          <SaveFailure failure={failure} known={MEMBER_ERRORS} />
          <RateLimitBand {...cooldown} />
          <TextField
            id="invite-email"
            label="이메일"
            type="email"
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            value={email}
            onChange={setEmail}
            maxLength={100}
            {...(fields.errors.email ? { error: fields.errors.email } : {})}
          />
          <TextField
            id="invite-name"
            label="이름"
            autoComplete="off"
            value={name}
            onChange={setName}
            maxLength={50}
            {...(fields.errors.name ? { error: fields.errors.name } : {})}
          />
          {/* Both outcomes are told ahead, and never which one happened (S2-AC-15). */}
          <p className="pd-muted">초대 메일이 가거나, 이미 계정이 있는 분이면 지원팀이 확인한 뒤 추가됩니다.</p>
        </form>
      </Dialog>
      <ConfirmDialog
        open={askDiscard}
        title="쓰던 내용을 버릴까요?"
        message="닫으면 이 대화상자에 쓴 값이 사라집니다."
        confirmLabel="버리고 닫기"
        cancelLabel="계속 쓰기"
        tone="danger"
        onConfirm={props.onClose}
        onCancel={() => {
          setAskDiscard(false);
        }}
      />
    </>
  );
}

/**
 * 초대 취소 and 소속에서 빼기: the same request, with other words (acceptance.md 5절 1).
 * Mounted anew for every opening (S2-AC-49).
 */
function RemoveDialog(props: { company: Company; member: Member; onClose: () => void; onChanged: () => void; onDenied: () => void }) {
  const toast = useToast();
  const live = useLive();
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const { company, member } = props;
  const invited = member.status === 'INVITED';

  const remove = async () => {
    if (pending) return;
    setPending(true);
    setFailure(null);
    const result = await call(api.DELETE('/api/portal/companies/{code}/members/{id}', { params: { path: { code: company.code, id: member.id } } }));
    // Someone else took the row out first, or made the person a manager meanwhile: the list is old.
    const gone = !result.ok && result.failure.kind === 'notFound';
    const nowManager = !result.ok && result.failure.kind === 'rejected' && result.failure.code === 'MEMBER_IS_MANAGER';
    if (result.ok || gone || nowManager) props.onChanged();
    else if (isDenied(result.failure)) props.onDenied();
    if (!live()) return;
    setPending(false);
    if (result.ok) toast(invited ? '초대를 취소했습니다' : '소속에서 뺐습니다');
    else if (gone) toast(ALREADY_GONE, 'info');
    else if (nowManager) toast('담당자는 뺄 수 없습니다', 'info');
    else {
      setFailure(result.failure);
      return;
    }
    props.onClose();
  };

  return (
    <ConfirmDialog
      open
      tone="danger"
      title={invited ? '초대 취소' : '소속에서 빼기'}
      message={
        <>
          <p>
            {invited
              ? `${member.name} 님의 초대를 취소할까요?`
              : `${member.name} 님을 ${company.name}에서 뺄까요? 이 사람은 자기가 올린 문의만 계속 볼 수 있습니다.`}
          </p>
          <ActionFailure failure={failure} />
        </>
      }
      confirmLabel={invited ? '초대 취소' : '빼기'}
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

interface CompanyMembersProps {
  company: Company;
  /** The screen's `h1`: paging moves focus to it. */
  heading: RefObject<HTMLHeadingElement | null>;
  inviting: boolean;
  onInviteClose: () => void;
  onGone: (state: Gone) => void;
}

/**
 * The list of one company with its dialogs. The screen mounts it anew for each
 * company, so nothing of the company before is kept and its late answers are dropped (S2-AC-49).
 */
function CompanyMembers(props: CompanyMembersProps) {
  const { company, onGone } = props;
  const code = company.code;
  const { me } = useSession();
  const toast = useToast();
  const live = useLive();
  const page = usePageParam();
  const [removing, setRemoving] = useState<Member | null>(null);
  // Counts openings: the key that makes every opening a new dialog.
  const [openings, setOpenings] = useState(0);
  const [rowFailure, setRowFailure] = useState<Failure | null>(null);
  const sending = useRef(false);

  const load = useLoad(() => call(api.GET('/api/portal/companies/{code}/members', { params: { path: { code }, query: { page, size: PAGE_SIZE } } })), String(page));

  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);
  const move = usePageMove(load.data, props.heading);

  const listGone: Gone | null = load.failure?.kind === 'forbidden' ? 'forbidden' : load.failure?.kind === 'notFound' ? 'notFound' : null;
  useEffect(() => {
    if (listGone !== null) onGone(listGone);
  }, [listGone, onGone]);

  // The dialogs call these also for an answer that came after they closed; after the company changed, nothing happens.
  const reload = () => {
    if (live()) void load.reload();
  };
  const denied = () => {
    if (live()) onGone('forbidden');
  };

  // 초대 다시 보내기 does not ask first (screens.md P-8 줄 메뉴).
  const resend = async (m: Member) => {
    if (sending.current) return;
    sending.current = true;
    setRowFailure(null);
    const result = await call(api.POST('/api/portal/companies/{code}/members/{id}/resend', { params: { path: { code, id: m.id } } }));
    sending.current = false;
    if (!live()) return;
    if (result.ok) toast('초대를 다시 보냈습니다');
    else if (isDenied(result.failure)) onGone('forbidden');
    // One text whatever the reason: the reason would tell about the account (S2-AC-31).
    else if (result.failure.kind === 'rejected' && result.failure.code === 'INVITATION_NOT_RESENDABLE') toast('이 초대는 다시 보낼 수 없습니다. 지원팀에 문의하세요', 'info');
    else if (result.failure.kind === 'notFound') {
      toast(ALREADY_GONE, 'info');
      void load.reload();
    } else setRowFailure(result.failure);
  };

  const actionsOf = (m: Member): RowMenuItem[] => {
    const remove = () => {
      setOpenings((n) => n + 1);
      setRowFailure(null);
      setRemoving(m);
    };
    if (m.status !== 'INVITED') return [{ label: '소속에서 빼기', tone: 'danger', onSelect: remove }];
    return [
      {
        label: '초대 다시 보내기',
        onSelect: () => {
          void resend(m);
        },
      },
      { label: '초대 취소', tone: 'danger', onSelect: remove },
    ];
  };

  // No photo and no time: a row tells nothing about the person beyond this company (S2-AC-10).
  const columns: DataTableColumn<Member>[] = [
    {
      key: 'name',
      header: '이름',
      cell: (m) => (
        <>
          {m.name}
          {m.email === me?.email && <span className="pd-muted"> (나)</span>}
        </>
      ),
    },
    { key: 'email', header: '이메일', cell: (m) => m.email },
    { key: 'status', header: '상태', cell: (m) => <StatusBadge kind="account" status={m.status} /> },
    { key: 'manager', header: '담당자', cell: (m) => (m.manager ? <Badge tone="accent">담당자</Badge> : null) },
    {
      key: 'actions',
      header: <span className="pd-sr-only">동작</span>,
      align: 'end',
      // My own row and another manager's row have nothing to do (S2-AC-47).
      cell: (m) => (m.manager || m.email === me?.email ? null : <RowMenu label={`${m.name} 동작`} items={actionsOf(m)} />),
    },
  ];

  const data = load.data;
  const outOfRange = data !== undefined && data.items.length === 0 && page > 1;
  // The whole screen changes in the next draw; the list does not show a band first.
  const failedLoad = load.status === 'failed' && listGone === null;

  return (
    <>
      {company.status === 'SUSPENDED' && (
        <Alert tone="warning" live="status">
          이 고객사는 멈춰 있습니다
        </Alert>
      )}
      {company.status === 'ARCHIVED' && <Alert tone="info">이 고객사는 보관되어 초대할 수 없습니다</Alert>}
      {failedLoad && <LoadFailed onRetry={reload} />}
      <ActionFailure failure={rowFailure} />
      {(load.status !== 'failed' || data) && (
        <DataTable
          caption={`${company.name} 사람, ${String(data?.totalElements ?? 0)}명`}
          columns={columns}
          rows={data?.items ?? []}
          rowKey={(m) => m.id}
          status={firstLoad ? 'loading' : 'ready'}
          skeletonRows={load.slow ? 5 : 0}
          empty={<EmptyState title="이 쪽에는 항목이 없습니다" {...(outOfRange ? { action: <Link to={pageHref(PATH, 1)}>1쪽으로</Link> } : {})} />}
        />
      )}
      {data && <PageNav path={PATH} page={data.page} pageCount={data.totalPages} onPageChange={move.onPageChange} />}
      <p role="status" className="pd-sr-only">
        {move.announcement}
      </p>
      {props.inviting && <InviteDialog company={company} onClose={props.onInviteClose} onInvited={reload} onDenied={denied} />}
      {removing && (
        <RemoveDialog
          key={openings}
          company={company}
          member={removing}
          onClose={() => {
            setRemoving(null);
          }}
          onChanged={reload}
          onDenied={denied}
        />
      )}
    </>
  );
}

/**
 * Reads 내 정보 again before drawing: the menu and this screen follow what the
 * server says now, not what it said when the portal was opened (screens.md 6절).
 */
export function P8Members() {
  const { setMe } = useSession();
  const live = useLive();
  const fresh = useLoad(async () => {
    const result = await call(api.GET('/api/portal/me'));
    if (result.ok && live()) setMe(result.data);
    return result;
  }, 'me');
  useMainBusy(fresh.status === 'loading');

  if (fresh.status === 'loading') return null;
  if (fresh.status === 'failed') {
    return (
      <LoadFailed
        onRetry={() => {
          void fresh.reload();
        }}
      />
    );
  }
  return <ManagedCompanies />;
}

function ManagedCompanies() {
  const { me } = useSession();
  const [params, setParams] = useSearchParams();
  const heading = useRef<HTMLHeadingElement>(null);
  const [inviting, setInviting] = useState(false);
  // What the server said about one company. Another company is asked anew.
  const [gone, setGone] = useState<{ code: string; state: Gone } | null>(null);

  const managed = (me?.companies ?? []).filter((c) => c.manager);
  // Without a company in the address: the first one I manage, in the order of 내 정보 (screens.md 5절).
  const code = params.get('company') ?? managed[0]?.code;

  // The frame draws its screens only with a session. No company to ask for: nothing is sent (S2-AC-45).
  if (!me) return null;
  if (code === undefined) return <NoPermission />;
  if (gone?.code === code) {
    return gone.state === 'forbidden' ? <NoPermission /> : <NotFound listHref="/issues" listLabel="문의 목록으로" />;
  }

  // A company from the address that the session does not know is still asked for: the server decides.
  const company: Company = me.companies.find((c) => c.code === code) ?? { code, name: code, status: 'ACTIVE', manager: false };

  const choose = (next: string) => {
    setInviting(false);
    setParams((current) => {
      current.set('company', next);
      current.delete('page');
      return current;
    });
  };

  return (
    <div className="pd-screen">
      <div className="pd-screen-head">
        <div className="pd-members-title">
          <PageTitle titleRef={heading}>{t('companyPeople.title')}</PageTitle>
          {managed.length === 1 && company.manager && <span className="pd-muted">{company.name}</span>}
        </div>
      </div>
      {company.manager && (managed.length > 1 || company.status !== 'ARCHIVED') && (
        <div className="pd-members-bar">
          {managed.length > 1 && <Select label="고객사" options={managed.map((c) => ({ value: c.code, label: c.name }))} value={code} onChange={choose} />}
          {company.status !== 'ARCHIVED' && (
            <Button
              onClick={() => {
                setInviting(true);
              }}
            >
              초대
            </Button>
          )}
        </div>
      )}
      <CompanyMembers
        key={code}
        company={company}
        heading={heading}
        inviting={inviting}
        onInviteClose={() => {
          setInviting(false);
        }}
        onGone={(state) => {
          setInviting(false);
          setGone({ code, state });
        }}
      />
    </div>
  );
}
