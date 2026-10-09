// C-10 구성원 목록 (관리자, screens.md 4절). List, 초대 (INVITE-1·2), and 초대
// 링크 복사 on accounts not accepted yet when SMTP is off (INVITE-6, S0-AC-38).
import { Button, ConfirmDialog, DataTable, Dialog, EmptyState, RelativeTime, StatusBadge, type DataTableColumn } from '@popdesk/ui';
import { useLayoutEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { api, type Schemas } from '../api';
import { SaveFailure } from '../app/bands';
import { useMainBusy } from '../app/busy';
import { useLoad } from '../app/hooks';
import { InviteDialog } from '../app/InviteDialog';
import { InviteLink } from '../app/InviteLink';
import { LoadFailed, PageTitle } from '../app/layout';
import { TEXT } from '../app/messages';
import { PageNav } from '../app/PageNav';
import { PAGE_SIZE, usePageMove, usePageParam } from '../app/paging';
import { call, type Failure, type Result } from '../app/result';

type Account = Schemas['Account'];

const ROLE_WORDS: Record<Account['role'], string> = { CUSTOMER: '고객', AGENT: '상담원', ADMIN: '관리자' };

interface ListData {
  accounts: Schemas['PagedAccount'];
  /** SMTP is set up (screens.md 7절: `system/status` `mail.enabled`). Unknown counts as set up. */
  mailEnabled: boolean;
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

export function C10MemberList() {
  const page = usePageParam();
  const heading = useRef<HTMLHeadingElement>(null);
  const [inviting, setInviting] = useState(false);
  const [linkFor, setLinkFor] = useState<Account | null>(null);

  const load = useLoad(async (): Promise<Result<ListData>> => {
    const [accounts, status] = await Promise.all([
      call(api.GET('/api/console/accounts', { params: { query: { page, size: PAGE_SIZE } } })),
      call(api.GET('/api/console/system/status')),
    ]);
    if (!accounts.ok) return accounts;
    return { ok: true, data: { accounts: accounts.data, mailEnabled: status.ok ? status.data.mail.enabled : true }, response: accounts.response };
  }, String(page));

  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);
  const move = usePageMove(load.data?.accounts, heading);
  const data = load.data;
  const mailEnabled = data?.mailEnabled ?? true;

  const columns: DataTableColumn<Account>[] = [
    { key: 'name', header: '이름', cell: (a) => a.name, sticky: true, width: '9rem' },
    { key: 'email', header: '이메일', cell: (a) => a.email },
    { key: 'role', header: '역할', cell: (a) => ROLE_WORDS[a.role] },
    { key: 'status', header: '상태', cell: (a) => <StatusBadge kind="account" status={a.status} /> },
    { key: 'created', header: '만든 시각', cell: (a) => <RelativeTime dateTime={a.createdAt} focusable={false} /> },
    {
      key: 'actions',
      header: <span className="pd-sr-only">동작</span>,
      align: 'end',
      cell: (a) =>
        !mailEnabled && a.status === 'INVITED' ? (
          <Button
            variant="secondary"
            size="sm"
            onClick={() => {
              setLinkFor(a);
            }}
          >
            초대 링크 복사
          </Button>
        ) : null,
    },
  ];

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
      {load.status === 'failed' && (
        <LoadFailed
          onRetry={() => {
            void load.reload();
          }}
        />
      )}
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
        onInvited={() => {
          void load.reload();
        }}
      />
      <NewLinkDialog
        account={linkFor}
        onClose={() => {
          setLinkFor(null);
        }}
      />
    </div>
  );
}
