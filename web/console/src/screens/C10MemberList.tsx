// C-10 구성원 목록 (관리자, screens.md 4절, 1a screens.md 2절). List with role,
// status and search filters, 초대 (INVITE-1·2), the actions of a row (ACCT-3,
// INVITE-3), and 초대 링크 복사 on accounts not accepted yet when SMTP is off
// (INVITE-6, S0-AC-38). Slice 3: the filters live in the address (S3-AC-86), and
// 정지 of a staff member offers to clear the open issues they are the assignee of (S3-AC-85).
import {
  ACCOUNT_STATUS_WORDS,
  Alert,
  Avatar,
  Button,
  ConfirmDialog,
  DataTable,
  Dialog,
  EmptyState,
  RelativeTime,
  SearchField,
  Select,
  StatusBadge,
  TextField,
  type AccountStatus,
  type DataTableColumn,
} from '@popdesk/ui';
import { useLayoutEffect, useRef, useState, type SubmitEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api, type Schemas } from '../api';
import { FieldSummary, SaveFailure } from '../app/bands';
import { useMainBusy } from '../app/busy';
import { ClearAssignments } from '../app/ClearAssignments';
import { useFieldErrors, useLive, useLoad } from '../app/hooks';
import { InviteDialog } from '../app/InviteDialog';
import { InviteLink } from '../app/InviteLink';
import { LoadFailed, PageTitle } from '../app/layout';
import { rateLimitedText, TEXT } from '../app/messages';
import { clearedText, useOpenAssignments } from '../app/openAssignments';
import { PageNav } from '../app/PageNav';
import { PAGE_SIZE, usePageMove, usePageParam } from '../app/paging';
import { photoUrl } from '../app/photos';
import { call, type Failure, type Result } from '../app/result';
import { RowMenu, type RowMenuItem } from '../app/RowMenu';
import { useSession } from '../app/sessionContext';
import { useToast } from '../app/toastContext';

type Account = Schemas['Account'];
type Role = Account['role'];

const ROLE_WORDS: Record<Role, string> = { CUSTOMER: '고객', AGENT: '상담원', ADMIN: '관리자' };
// The role a staff member is changed to, with its particle.
const ROLE_TO: Record<'AGENT' | 'ADMIN', string> = { AGENT: '상담원으로', ADMIN: '관리자로' };
const ROLE_OPTIONS = (['CUSTOMER', 'AGENT', 'ADMIN'] as const).map((value) => ({ value, label: ROLE_WORDS[value] }));
const STATUS_OPTIONS = (['ACTIVE', 'INVITED', 'SUSPENDED'] as const).map((value) => ({ value, label: ACCOUNT_STATUS_WORDS[value] }));

const ACCOUNT_ERRORS: Record<string, string> = {
  OWN_ACCOUNT: '자기 계정은 정지하거나 역할을 바꿀 수 없습니다.',
  CUSTOMER_ROLE_FIXED: '고객 계정의 역할은 바꿀 수 없습니다.',
  LAST_ADMIN: '사용 중인 관리자가 한 명은 남아야 합니다.',
  ACCOUNT_NOT_ACTIVE: '사용 중인 계정에만 할 수 있습니다.',
  INVITATION_NOT_ACCEPTED: '초대를 수락한 적이 없는 계정입니다. 초대를 다시 보내세요.',
  INVITATION_ALREADY_ACCEPTED: '이미 초대를 수락한 계정입니다.',
  NOT_FOUND: '계정을 찾을 수 없습니다.',
};

interface ListData {
  accounts: Schemas['PagedAccount'];
  /** SMTP is set up (screens.md 7절: `system/status` `mail.enabled`). Unknown counts as set up. */
  mailEnabled: boolean;
  /** Accounts whose invitation an admin cancelled. The account list shows them as `SUSPENDED` (S1A-AC-39, 42). */
  cancelled: number[];
}

/** A failed row action. 429 has no countdown here: the text says when to try again. */
function ActionFailure(props: { failure: Failure | null }) {
  if (props.failure?.kind === 'rateLimited') return <Alert tone="error">{rateLimitedText(props.failure.seconds)}</Alert>;
  return <SaveFailure failure={props.failure} known={ACCOUNT_ERRORS} />;
}

/** The new link of one account, shown once in a dialog. */
function NewLinkDialog(props: { account: Account | null; onClose: () => void }) {
  const [asked, setAsked] = useState(true);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [link, setLink] = useState<string | null>(null);
  // An answer that comes after the dialog closed, or after it moved to another
  // account, is dropped: its link must not show for the next account (MAIL-3, V-2).
  const request = useRef(0);
  const accountId = useRef(props.account?.id);
  useLayoutEffect(() => {
    accountId.current = props.account?.id;
  });

  const close = () => {
    request.current += 1;
    setPending(false);
    setAsked(true);
    setFailure(null);
    setLink(null);
    props.onClose();
  };

  const make = async () => {
    if (!props.account || pending) return;
    const mine = (request.current += 1);
    const id = props.account.id;
    setPending(true);
    setFailure(null);
    const result = await call(api.POST('/api/console/invitations/{accountId}/link', { params: { path: { accountId: id } } }));
    if (request.current !== mine || accountId.current !== id) return;
    setPending(false);
    if (result.ok) {
      setLink(result.data.inviteLink);
      setAsked(false);
    } else {
      setFailure(result.failure);
    }
  };

  const open = props.account !== null;
  return (
    <>
      <ConfirmDialog
        open={open && asked}
        title="초대 링크 다시 만들기"
        message={
          <>
            <p>새 링크를 만들면 이전 링크는 쓸 수 없게 됩니다.</p>
            <SaveFailure failure={failure} />
          </>
        }
        confirmLabel="새 링크 만들기"
        cancelLabel={TEXT.cancel}
        pending={pending}
        pendingLabel={TEXT.sending}
        onConfirm={() => {
          void make();
        }}
        onCancel={close}
      />
      <Dialog
        open={open && !asked && link !== null}
        title="초대 링크"
        description={props.account ? `${props.account.name}(${props.account.email})에게 보낼 링크입니다.` : ''}
        closeLabel={TEXT.close}
        onClose={close}
        footer={<Button onClick={close}>{TEXT.close}</Button>}
      >
        {link !== null && <InviteLink link={link} intro="메일 설정이 없어 초대 메일을 보낼 수 없습니다. 아래 링크를 복사해 직접 전하세요." />}
      </Dialog>
    </>
  );
}

/** A row action that asks first. */
type AskedAction = 'suspend' | 'role' | 'reset' | 'cancel';

interface ActionDialogProps {
  account: Account;
  onClose: () => void;
  /** The server took the change: the list is read again. Also called for an answer that came after closing. */
  onChanged: () => void;
}

/**
 * The confirm dialog of one row action. The screen mounts it anew for every
 * opening, so an answer that comes after it closed finds nothing to change: the
 * next dialog starts without the error or the link of the one before (S1A-AC-58).
 */
function AskDialog(props: ActionDialogProps & { action: AskedAction; mailEnabled: boolean; onLink: (link: string) => void }) {
  const toast = useToast();
  const live = useLive();
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const { account, action } = props;
  const nextRole = account.role === 'ADMIN' ? 'AGENT' : 'ADMIN';
  // 정지 of staff: the open issues of every company they are the assignee of. A customer is nobody's assignee.
  const open = useOpenAssignments(
    action === 'suspend' && account.role !== 'CUSTOMER'
      ? () => api.GET('/api/console/accounts/{accountId}/open-assignments', { params: { path: { accountId: account.id } } })
      : null,
  );
  const [clear, setClear] = useState(false);

  const confirm = async () => {
    if (pending) return;
    setPending(true);
    setFailure(null);
    const path = { params: { path: { accountId: account.id } } };
    let failed: Failure | null = null;
    let link: string | null = null;
    let cleared: number | undefined;
    if (action === 'suspend') {
      const result = await call(
        api.POST('/api/console/accounts/{accountId}/suspend', { params: { ...path.params, ...(clear ? { query: { clearAssignments: true } } : {}) } }),
      );
      if (!result.ok) failed = result.failure;
      else if (clear) cleared = result.data.cleared.issues;
    } else if (action === 'role') {
      const result = await call(api.PUT('/api/console/accounts/{accountId}/role', { ...path, body: { role: nextRole } }));
      if (!result.ok) failed = result.failure;
    } else if (action === 'reset') {
      const result = await call(api.POST('/api/console/accounts/{accountId}/password-reset', path));
      if (result.ok) link = result.data.resetLink;
      else failed = result.failure;
    } else {
      const result = await call(api.POST('/api/console/invitations/{accountId}/cancel', path));
      if (!result.ok) failed = result.failure;
    }
    // The row may have changed on the server either way (a 400 often means the list is old).
    if (failed === null || failed.kind === 'rejected' || failed.kind === 'notFound') props.onChanged();
    if (!live()) return;
    setPending(false);
    if (failed !== null) {
      setFailure(failed);
      return;
    }
    if (action === 'suspend') toast(`${account.name} 님을 정지했습니다${clearedText(cleared)}`);
    else if (action === 'role') toast(`${account.name} 님의 역할을 ${ROLE_TO[nextRole]} 바꿨습니다`);
    else if (action === 'cancel') toast('초대를 취소했습니다');
    else if (link === null) toast('재설정 메일을 보냈습니다.');
    else props.onLink(link);
    props.onClose();
  };

  const asks = {
    suspend: {
      title: '계정 정지',
      text: `${account.name} 님을 정지합니다. 로그인이 끊기고 다시 로그인할 수 없습니다.`,
      confirm: '정지',
      danger: true,
    },
    role: {
      title: '역할 바꾸기',
      text: `역할을 ${ROLE_WORDS[account.role]}에서 ${ROLE_TO[nextRole]} 바꿉니다. ${account.name} 님은 다시 로그인해야 합니다.`,
      confirm: '역할 바꾸기',
      danger: false,
    },
    reset: {
      title: '비밀번호 초기화',
      text: '지금 비밀번호를 못 쓰게 하고 재설정 메일을 보냅니다.',
      confirm: '초기화',
      danger: true,
    },
    cancel: {
      title: '초대 취소',
      text: '보낸 초대 링크를 못 쓰게 합니다.',
      confirm: '초대 취소',
      danger: true,
    },
  }[action];

  return (
    <ConfirmDialog
      open
      title={asks.title}
      message={
        <>
          <p>{asks.text}</p>
          {action === 'reset' && !props.mailEnabled && <p>메일 설정이 없으면 메일 대신 재설정 링크를 이 화면에 한 번 보입니다.</p>}
          {action === 'suspend' && <ClearAssignments name={account.name} open={open} scope="all" checked={clear} onChange={setClear} />}
          {action !== 'suspend' && action !== 'role' && (
            <p className="pd-muted">
              {account.name}({account.email})
            </p>
          )}
          <ActionFailure failure={failure} />
        </>
      }
      confirmLabel={asks.confirm}
      cancelLabel={TEXT.cancel}
      tone={asks.danger ? 'danger' : 'default'}
      pending={pending}
      pendingLabel={TEXT.sending}
      onConfirm={() => {
        void confirm();
      }}
      onCancel={props.onClose}
    />
  );
}

const RENAME_FORM_ID = 'rename-account';

/** 이름 고치기 (S1A-AC-30). Mounted anew for every opening, like AskDialog. */
function RenameDialog(props: ActionDialogProps) {
  const toast = useToast();
  const live = useLive();
  const { me, setMe } = useSession();
  const { account } = props;
  const [name, setName] = useState(account.name);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const fields = useFieldErrors({ name: { id: 'account-name', limits: { max: 50 } } });

  const submit = async (e: SubmitEvent) => {
    e.preventDefault();
    if (pending) return;
    setFailure(null);
    fields.clear();
    if (name === account.name) {
      props.onClose();
      return;
    }
    setPending(true);
    const result = await call(api.PATCH('/api/console/accounts/{accountId}', { params: { path: { accountId: account.id } }, body: { name } }));
    if (result.ok) props.onChanged();
    if (!live()) return;
    setPending(false);
    if (result.ok) {
      // The menu shows my own name.
      if (me?.id === account.id) setMe({ ...me, name: result.data.name });
      toast('이름을 고쳤습니다');
      props.onClose();
      return;
    }
    if (result.failure.kind === 'invalid') fields.applyFields(result.failure.fields, result.failure.message);
    else setFailure(result.failure);
  };

  return (
    <Dialog
      open
      title="이름 고치기"
      description={account.email}
      closeLabel={TEXT.close}
      pending={pending}
      onClose={props.onClose}
      footer={
        <>
          <Button variant="secondary" onClick={props.onClose}>
            {TEXT.cancel}
          </Button>
          <Button type="submit" form={RENAME_FORM_ID} pending={pending} pendingLabel={TEXT.sending}>
            저장
          </Button>
        </>
      }
    >
      <form
        id={RENAME_FORM_ID}
        className="pd-form"
        noValidate
        onSubmit={(e) => {
          void submit(e);
        }}
      >
        <FieldSummary summary={fields.summary} />
        <ActionFailure failure={failure} />
        <TextField
          id="account-name"
          label="이름"
          value={name}
          onChange={setName}
          maxLength={50}
          {...(fields.errors.name ? { error: fields.errors.name } : {})}
        />
      </form>
    </Dialog>
  );
}

/** A link the server gave once: a new invitation link or a password reset link. */
interface ShownLink {
  kind: 'invite' | 'reset';
  account: Account;
  link: string;
}

export function C10MemberList() {
  const page = usePageParam();
  const [searchParams, setParams] = useSearchParams();
  const { me } = useSession();
  const toast = useToast();
  const live = useLive();
  const heading = useRef<HTMLHeadingElement>(null);
  const searchBox = useRef<HTMLInputElement>(null);
  const [inviting, setInviting] = useState(false);
  const [linkFor, setLinkFor] = useState<Account | null>(null);

  // Filters: the address holds them, under the names the API takes (S3-AC-86). A value the list cannot take is left out.
  const role = ROLE_OPTIONS.find((o) => o.value === searchParams.get('role'))?.value ?? '';
  const status = STATUS_OPTIONS.find((o) => o.value === searchParams.get('status'))?.value ?? '';
  const q = (searchParams.get('q') ?? '').trim().slice(0, 100);
  /** A new filter starts on the first page. */
  const setFilters = (next: { role: string; status: string; q: string }, options: { replace?: boolean } = {}) => {
    const params = new URLSearchParams();
    if (next.role !== '') params.set('role', next.role);
    if (next.status !== '') params.set('status', next.status);
    if (next.q !== '') params.set('q', next.q);
    setParams(params, options);
  };

  // Row actions
  const [opened, setOpened] = useState<{ action: AskedAction | 'rename'; account: Account } | null>(null);
  // Counts openings: the key that makes every opening a new dialog.
  const [openings, setOpenings] = useState(0);
  const [shownLink, setShownLink] = useState<ShownLink | null>(null);
  const [rowFailure, setRowFailure] = useState<Failure | null>(null);
  const sending = useRef(false);

  const load = useLoad(
    async (): Promise<Result<ListData>> => {
      const filters = {
        ...(role === '' ? {} : { role }),
        ...(status === '' ? {} : { status }),
        ...(q === '' ? {} : { q }),
      };
      const [accounts, system, invitations] = await Promise.all([
        call(api.GET('/api/console/accounts', { params: { query: { page, size: PAGE_SIZE, ...filters } } })),
        call(api.GET('/api/console/system/status')),
        call(api.GET('/api/console/invitations', { params: { query: { size: 100 } } })),
      ]);
      if (!accounts.ok) return accounts;
      return {
        ok: true,
        data: {
          accounts: accounts.data,
          mailEnabled: system.ok ? system.data.mail.enabled : true,
          cancelled: invitations.ok ? invitations.data.items.filter((i) => i.cancelled).map((i) => i.accountId) : [],
        },
        response: accounts.response,
      };
    },
    [page, role, status, q].join('\n'),
  );

  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);
  const move = usePageMove(load.data?.accounts, heading);
  const data = load.data;
  const mailEnabled = data?.mailEnabled ?? true;
  const cancelled = new Set(data?.cancelled ?? []);
  const filtered = role !== '' || status !== '' || q !== '';

  /** What the row shows: an account whose invitation was cancelled is `SUSPENDED` in the API. */
  const shownStatus = (a: Account): AccountStatus => (a.status === 'SUSPENDED' && cancelled.has(a.id) ? 'INVITE_CANCELLED' : a.status);

  const reload = () => {
    void load.reload();
  };

  const open = (action: AskedAction | 'rename', account: Account) => {
    setOpenings((n) => n + 1);
    setRowFailure(null);
    setOpened({ action, account });
  };

  // 정지 풀기 and 초대 다시 보내기 do not ask first (screens.md C-10).
  const reactivate = async (account: Account) => {
    if (sending.current) return;
    sending.current = true;
    setRowFailure(null);
    const result = await call(api.POST('/api/console/accounts/{accountId}/reactivate', { params: { path: { accountId: account.id } } }));
    sending.current = false;
    if (!live()) return;
    reload();
    if (result.ok) toast(`${account.name} 님의 정지를 풀었습니다`);
    else setRowFailure(result.failure);
  };

  const resend = async (account: Account) => {
    if (sending.current) return;
    sending.current = true;
    setRowFailure(null);
    const result = await call(api.POST('/api/console/invitations/{accountId}/resend', { params: { path: { accountId: account.id } } }));
    sending.current = false;
    if (!live()) return;
    reload();
    if (!result.ok) setRowFailure(result.failure);
    else if (result.data.inviteLink === null) toast('초대 메일을 다시 보냈습니다');
    else setShownLink({ kind: 'invite', account, link: result.data.inviteLink });
  };

  const actionsOf = (a: Account): RowMenuItem[] => {
    const shown = shownStatus(a);
    const items: RowMenuItem[] = [
      {
        label: '이름 고치기',
        onSelect: () => {
          open('rename', a);
        },
      },
    ];
    if (shown === 'ACTIVE') {
      const mine = a.id === me?.id;
      // Only staff roles change into each other, and never my own (S1A-AC-32, 33).
      if (!mine && a.role !== 'CUSTOMER') {
        items.push({
          label: '역할 바꾸기',
          onSelect: () => {
            open('role', a);
          },
        });
      }
      items.push({
        label: '비밀번호 초기화',
        onSelect: () => {
          open('reset', a);
        },
      });
      if (!mine) {
        items.push({
          label: '정지',
          tone: 'danger',
          onSelect: () => {
            open('suspend', a);
          },
        });
      }
    } else if (shown === 'SUSPENDED') {
      items.push({
        label: '정지 풀기',
        onSelect: () => {
          void reactivate(a);
        },
      });
    } else {
      items.push({
        label: '초대 다시 보내기',
        onSelect: () => {
          void resend(a);
        },
      });
      if (shown === 'INVITED') {
        items.push({
          label: '초대 취소',
          tone: 'danger',
          onSelect: () => {
            open('cancel', a);
          },
        });
      }
    }
    return items;
  };

  const columns: DataTableColumn<Account>[] = [
    {
      key: 'name',
      header: '이름',
      cell: (a) => (
        <span className="pd-person">
          <Avatar name={a.name} photoUrl={photoUrl(a.id, a.photoUpdatedAt)} size="sm" decorative />
          <span>{a.name}</span>
        </span>
      ),
      sticky: true,
      width: '11rem',
    },
    { key: 'email', header: '이메일', cell: (a) => a.email },
    { key: 'role', header: '역할', cell: (a) => ROLE_WORDS[a.role] },
    { key: 'status', header: '상태', cell: (a) => <StatusBadge kind="account" status={shownStatus(a)} /> },
    { key: 'created', header: '만든 시각', cell: (a) => <RelativeTime dateTime={a.createdAt} focusable={false} /> },
    {
      key: 'actions',
      header: <span className="pd-sr-only">동작</span>,
      align: 'end',
      cell: (a) => (
        <span className="pd-row-actions">
          {!mailEnabled && a.status === 'INVITED' && (
            <Button
              variant="secondary"
              size="sm"
              onClick={() => {
                setLinkFor(a);
              }}
            >
              초대 링크 복사
            </Button>
          )}
          <RowMenu label={`${a.name} 동작`} items={actionsOf(a)} />
        </span>
      ),
    },
  ];

  const clearFilters = () => {
    setFilters({ role: '', status: '', q: '' });
    // The button goes away with the empty state.
    searchBox.current?.focus();
  };

  const closeLink = () => {
    setShownLink(null);
  };

  const outOfRange = data !== undefined && data.accounts.items.length === 0 && page > 1;

  return (
    <div className="pd-screen">
      <div className="pd-screen-head">
        <PageTitle titleRef={heading}>구성원 목록</PageTitle>
        <Button
          onClick={() => {
            setInviting(true);
          }}
        >
          초대
        </Button>
      </div>
      <div className="pd-filters">
        <Select
          label="역할"
          placeholder="전체"
          options={ROLE_OPTIONS}
          value={role}
          onChange={(value) => {
            setFilters({ role: value, status, q });
          }}
        />
        <Select
          label="상태"
          placeholder="전체"
          options={STATUS_OPTIONS}
          value={status}
          onChange={(value) => {
            setFilters({ role, status: value, q });
          }}
        />
        <SearchField
          ref={searchBox}
          label="이름·이메일 검색"
          value={q}
          onSearch={(value) => {
            const next = value.trim();
            // Typing replaces the entry: 뒤로 가기 does not walk through every pause.
            if (next !== q) setFilters({ role, status, q: next }, { replace: true });
          }}
        />
      </div>
      {load.status === 'failed' && <LoadFailed onRetry={reload} />}
      <ActionFailure failure={rowFailure} />
      {(load.status !== 'failed' || data) && (
        <DataTable
          caption={`구성원 목록, ${String(data?.accounts.totalElements ?? 0)}명`}
          columns={columns}
          rows={data?.accounts.items ?? []}
          rowKey={(a) => String(a.id)}
          status={firstLoad ? 'loading' : 'ready'}
          skeletonRows={load.slow ? 8 : 0}
          density="compact"
          empty={
            outOfRange ? (
              <EmptyState title="이 쪽에는 구성원이 없습니다" action={<Link to="/members">1쪽으로</Link>} />
            ) : filtered ? (
              <EmptyState
                title="조건에 맞는 구성원이 없습니다"
                action={
                  <Button variant="secondary" size="sm" onClick={clearFilters}>
                    조건 지우기
                  </Button>
                }
              />
            ) : (
              <EmptyState title="구성원이 없습니다." />
            )
          }
        />
      )}
      {data && <PageNav path="/members" page={data.accounts.page} pageCount={data.accounts.totalPages} onPageChange={move.onPageChange} />}
      <p role="status" className="pd-sr-only">
        {move.announcement}
      </p>
      <InviteDialog
        open={inviting}
        onClose={() => {
          setInviting(false);
        }}
        onInvited={reload}
      />
      <NewLinkDialog
        account={linkFor}
        onClose={() => {
          setLinkFor(null);
        }}
      />
      {opened?.action === 'rename' && (
        <RenameDialog
          key={openings}
          account={opened.account}
          onClose={() => {
            setOpened(null);
          }}
          onChanged={reload}
        />
      )}
      {opened && opened.action !== 'rename' && (
        <AskDialog
          key={openings}
          action={opened.action}
          account={opened.account}
          mailEnabled={mailEnabled}
          onClose={() => {
            setOpened(null);
          }}
          onChanged={reload}
          onLink={(link) => {
            setShownLink({ kind: 'reset', account: opened.account, link });
          }}
        />
      )}
      <Dialog
        open={shownLink !== null}
        title={shownLink?.kind === 'reset' ? '재설정 링크' : '초대 링크'}
        description={shownLink ? `${shownLink.account.name}(${shownLink.account.email})에게 보낼 링크입니다.` : ''}
        closeLabel={TEXT.close}
        onClose={closeLink}
        footer={<Button onClick={closeLink}>{TEXT.close}</Button>}
      >
        {shownLink?.kind === 'reset' && (
          <InviteLink
            link={shownLink.link}
            label="재설정 링크"
            intro="메일 설정이 없어 재설정 메일을 보낼 수 없습니다. 아래 링크를 복사해 직접 전하세요. 링크는 1시간 동안 쓸 수 있습니다."
          />
        )}
        {shownLink?.kind === 'invite' && (
          <InviteLink link={shownLink.link} intro="메일 설정이 없어 초대 메일을 보낼 수 없습니다. 아래 링크를 복사해 직접 전하세요." />
        )}
      </Dialog>
    </div>
  );
}
