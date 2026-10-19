// C-10 구성원 목록 (관리자, screens.md 4절, 1a screens.md 2절). List with role,
// status and search filters, 초대 (INVITE-1·2), the actions of a row (ACCT-3,
// INVITE-3), and 초대 링크 복사 on accounts not accepted yet when SMTP is off
// (INVITE-6, S0-AC-38). Slice 3: the filters live in the address (S3-AC-86), and
// 정지 of a staff member offers to clear the open issues they are the assignee of (S3-AC-85).
// Slice 10a: 정보 내보내기 and 익명화 of a row, and the 익명화됨 badge (10a screens.md).
import {
  ACCOUNT_STATUS_WORDS,
  Alert,
  Avatar,
  Badge,
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
import { startExport } from '../app/exportDownload';
import { useFieldErrors, useLive, useLoad } from '../app/hooks';
import { InviteDialog } from '../app/InviteDialog';
import { InviteLink } from '../app/InviteLink';
import { t, texts, tf } from '../app/locale';
import { LoadFailed, PageTitle } from '../app/layout';
import { matchesEmail } from '../app/matchesEmail';
import { rateLimitedText, TEXT } from '../app/messages';
import { clearedText, useOpenAssignments } from '../app/openAssignments';
import { PageNav } from '../app/PageNav';
import { PAGE_SIZE, usePageMove, usePageParam } from '../app/paging';
import { photoUrl } from '../app/photos';
import { call, type Failure, type Result } from '../app/result';
import { RowMenu, type RowMenuItem } from '../app/RowMenu';
import { useSession, useToLogin } from '../app/sessionContext';
import { useToast } from '../app/toastContext';

type Account = Schemas['Account'];
type Role = Account['role'];

const roleWord = (role: Role) => t(`members.role.${role}`);
// The role a staff member is changed to, with its particle in Korean.
const roleTo = (role: 'AGENT' | 'ADMIN') => t(`members.roleTo.${role}`);
const ROLES = ['CUSTOMER', 'AGENT', 'ADMIN'] as const;
const STATUSES = ['ACTIVE', 'INVITED', 'SUSPENDED'] as const;
// Made while drawing, so the labels follow the screen language.
const roleOptions = () => ROLES.map((value) => ({ value, label: roleWord(value) }));
const statusOptions = () => STATUSES.map((value) => ({ value, label: ACCOUNT_STATUS_WORDS[value] }));

const ACCOUNT_ERRORS: Readonly<Record<string, string>> = texts({
  OWN_ACCOUNT: 'members.error.ownAccount',
  CUSTOMER_ROLE_FIXED: 'members.error.customerRoleFixed',
  LAST_ADMIN: 'members.error.lastAdmin',
  ACCOUNT_NOT_ACTIVE: 'members.error.accountNotActive',
  INVITATION_NOT_ACCEPTED: 'members.error.invitationNotAccepted',
  INVITATION_ALREADY_ACCEPTED: 'members.error.invitationAlreadyAccepted',
  NOT_FOUND: 'members.error.notFound',
  ACCOUNT_ANONYMIZED: 'members.error.accountAnonymized',
  ANONYMIZATION_RUNNING: 'members.error.anonymizationRunning',
});

/**
 * The 익명화 dialog says OWN_ACCOUNT in its own words: the shared text is about 정지 and 역할.
 * AUDIT_CHAIN_BROKEN comes only from 익명화; its `brokenSeq` is not shown (S10A-AC-37).
 */
// 연결 풀기 (11 screens.md C-10): a 404 means the link is gone already.
const unlinkErrors = (): Record<string, string> => ({ ...ACCOUNT_ERRORS, NOT_FOUND: t('members.error.alreadyUnlinked') });

const anonymizeErrors = (): Record<string, string> => ({
  ...ACCOUNT_ERRORS,
  OWN_ACCOUNT: t('members.error.anonymizeOwn'),
  AUDIT_CHAIN_BROKEN: t('members.error.auditChainBroken'),
});

/** The 503 of 정보 내보내기, with the seconds of `Retry-After`. */
function exportBusyText(seconds: number | null): string {
  return seconds === null
    ? t('members.exportBusy')
    : tf('members.exportBusyFor', { seconds });
}

interface ListData {
  accounts: Schemas['PagedAccount'];
  /** SMTP is set up (screens.md 7절: `system/status` `mail.enabled`). Unknown counts as set up. */
  mailEnabled: boolean;
  /** Accounts whose invitation an admin cancelled. The account list shows them as `SUSPENDED` (S1A-AC-39, 42). */
  cancelled: number[];
}

/** A failed row action. 429 has no countdown here: the text says when to try again. */
function ActionFailure(props: { failure: Failure | null; known?: Record<string, string> }) {
  if (props.failure?.kind === 'rateLimited') return <Alert tone="error">{rateLimitedText(props.failure.seconds)}</Alert>;
  return <SaveFailure failure={props.failure} known={props.known ?? ACCOUNT_ERRORS} />;
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
        title={t('members.newLinkTitle')}
        message={
          <>
            <p>{t('members.newLinkText')}</p>
            <SaveFailure failure={failure} />
          </>
        }
        confirmLabel={t('members.newLinkConfirm')}
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
        title={t('members.inviteLinkTitle')}
        description={props.account ? tf('members.linkFor', { name: props.account.name, email: props.account.email }) : ''}
        closeLabel={TEXT.close}
        onClose={close}
        footer={<Button onClick={close}>{TEXT.close}</Button>}
      >
        {link !== null && <InviteLink link={link} intro={t('members.noMailInvite')} />}
      </Dialog>
    </>
  );
}

/** A row action that asks first. */
type AskedAction = 'suspend' | 'role' | 'reset' | 'cancel' | 'unlink';
/** A row action with a dialog. */
type OpenedAction = AskedAction | 'rename' | 'anonymize';

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
function AskDialog(
  props: ActionDialogProps & { action: AskedAction; mailEnabled: boolean; onLink: (link: string) => void; onUnlinked: (accountId: number) => void },
) {
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
    } else if (action === 'cancel') {
      const result = await call(api.POST('/api/console/invitations/{accountId}/cancel', path));
      if (!result.ok) failed = result.failure;
    } else {
      const result = await call(api.DELETE('/api/console/accounts/{accountId}/oidc-link', path));
      if (!result.ok) failed = result.failure;
      // The badge goes before the list comes back; a late answer leaves it to the list (C-7 웹 점검 D2).
      else if (live()) props.onUnlinked(account.id);
    }
    // The row may have changed on the server either way (a 400 often means the list is old).
    // 연결 풀기 reads the list again after any answer, like 익명화.
    if (failed === null || failed.kind === 'rejected' || failed.kind === 'notFound' || action === 'unlink') props.onChanged();
    if (!live()) return;
    setPending(false);
    if (failed !== null) {
      setFailure(failed);
      return;
    }
    if (action === 'suspend') toast(`${tf('members.suspendedToast', { name: account.name })}${clearedText(cleared)}`);
    else if (action === 'role') toast(tf('members.roleChangedToast', { name: account.name, to: roleTo(nextRole) }));
    else if (action === 'cancel') toast(t('members.inviteCancelledToast'));
    else if (action === 'unlink') toast(t('members.unlinkedToast'));
    else if (link === null) toast(t('members.resetSentToast'));
    else props.onLink(link);
    props.onClose();
  };

  const asks = {
    suspend: {
      title: t('members.suspendTitle'),
      text: tf('members.suspendText', { name: account.name }),
      confirm: t('members.suspend'),
      danger: true,
    },
    role: {
      title: t('members.roleTitle'),
      text: tf('members.roleText', { from: roleWord(account.role), to: roleTo(nextRole), name: account.name }),
      confirm: t('members.roleTitle'),
      danger: false,
    },
    reset: {
      title: t('members.resetTitle'),
      text: t('members.resetText'),
      confirm: t('members.resetConfirm'),
      danger: true,
    },
    cancel: {
      title: t('members.cancelTitle'),
      text: t('members.cancelText'),
      confirm: t('members.cancelTitle'),
      danger: true,
    },
    unlink: {
      title: t('members.unlinkTitle'),
      text: tf('members.unlinkText', { name: account.name }),
      confirm: t('members.unlinkConfirm'),
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
          {action === 'reset' && !props.mailEnabled && <p>{t('members.resetNoMail')}</p>}
          {action === 'suspend' && <ClearAssignments name={account.name} open={open} scope="all" checked={clear} onChange={setClear} />}
          {action !== 'suspend' && action !== 'role' && action !== 'unlink' && (
            <p className="pd-muted">
              {account.name}({account.email})
            </p>
          )}
          <ActionFailure failure={failure} {...(action === 'unlink' ? { known: unlinkErrors() } : {})} />
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
      toast(t('members.renamedToast'));
      props.onClose();
      return;
    }
    if (result.failure.kind === 'invalid') fields.applyFields(result.failure.fields, result.failure.message);
    else setFailure(result.failure);
  };

  return (
    <Dialog
      open
      title={t('members.renameTitle')}
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
            {t('members.save')}
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
          label={t('members.name')}
          value={name}
          onChange={setName}
          maxLength={50}
          {...(fields.errors.name ? { error: fields.errors.name } : {})}
        />
      </form>
    </Dialog>
  );
}

const ANONYMIZE_FORM_ID = 'anonymize-account';

/**
 * 익명화 (S10A-AC-30, 31). Mounted anew for every opening, like AskDialog: an answer
 * that comes after closing reads the list again and shows nothing, and the next
 * dialog starts with an empty field. The button is inside the form, so Enter sends.
 */
function AnonymizeDialog(props: ActionDialogProps) {
  const toast = useToast();
  const live = useLive();
  const { account } = props;
  const field = useRef<HTMLInputElement>(null);
  const [typed, setTyped] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  // Like 정지: a customer is nobody's assignee, so nothing is asked.
  const open = useOpenAssignments(
    account.role === 'CUSTOMER'
      ? null
      : () => api.GET('/api/console/accounts/{accountId}/open-assignments', { params: { path: { accountId: account.id } } }),
  );
  const [clear, setClear] = useState(false);
  const matches = matchesEmail(typed, account.email);

  const submit = async (e: SubmitEvent) => {
    e.preventDefault();
    if (pending || !matches) return;
    setPending(true);
    setFailure(null);
    const result = await call(
      api.POST('/api/console/accounts/{accountId}/anonymize', {
        params: { path: { accountId: account.id }, ...(clear ? { query: { clearAssignments: true } } : {}) },
      }),
    );
    // The row may have changed on the server either way.
    props.onChanged();
    if (!live()) return;
    setPending(false);
    if (!result.ok) {
      setFailure(result.failure);
      return;
    }
    toast(`${t('members.anonymizedToast')}${clearedText(clear ? result.data.cleared.issues : undefined)}`);
    props.onClose();
  };

  return (
    <Dialog open title={tf('members.anonymizeTitle', { name: account.name })} closeLabel={TEXT.close} pending={pending} initialFocus={field} onClose={props.onClose}>
      <form
        id={ANONYMIZE_FORM_ID}
        className="pd-form"
        noValidate
        onSubmit={(e) => {
          void submit(e);
        }}
      >
        <p>
          <strong>{t('members.cannotUndo')}</strong>
        </p>
        <ul className="pd-anonymize-changes">
          <li>{tf('members.anonymizeChangesName', { id: account.id })}</li>
          <li>{t('members.anonymizeChangesPhoto')}</li>
          <li>{t('members.anonymizeChangesWritings')}</li>
          <li>{t('members.anonymizeChangesAudit')}</li>
        </ul>
        <ClearAssignments name={account.name} open={open} scope="all" checked={clear} onChange={setClear} />
        <TextField
          ref={field}
          id="anonymize-email"
          label={tf('members.anonymizeTypeEmail', { email: account.email })}
          value={typed}
          onChange={setTyped}
        />
        <ActionFailure failure={failure} known={anonymizeErrors()} />
        <div className="pd-form-actions">
          <Button variant="secondary" onClick={props.onClose}>
            {TEXT.cancel}
          </Button>
          <Button type="submit" variant="danger" blocked={!matches} pending={pending} pendingLabel={t('members.anonymizing')}>
            {t('members.anonymize')}
          </Button>
        </div>
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
  const toLogin = useToLogin();
  const heading = useRef<HTMLHeadingElement>(null);
  const searchBox = useRef<HTMLInputElement>(null);
  const [inviting, setInviting] = useState(false);
  const [linkFor, setLinkFor] = useState<Account | null>(null);

  // Filters: the address holds them, under the names the API takes (S3-AC-86). A value the list cannot take is left out.
  const role = ROLES.find((value) => value === searchParams.get('role')) ?? '';
  const status = STATUSES.find((value) => value === searchParams.get('status')) ?? '';
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
  const [opened, setOpened] = useState<{ action: OpenedAction; account: Account } | null>(null);
  // Counts openings: the key that makes every opening a new dialog.
  const [openings, setOpenings] = useState(0);
  const [shownLink, setShownLink] = useState<ShownLink | null>(null);
  const [rowFailure, setRowFailure] = useState<Failure | null>(null);
  // The band of 정보 내보내기, in the place of a failed row action.
  const [exportProblem, setExportProblem] = useState<string | null>(null);
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

  // 연결 풀기 took: those rows lose the badge until the list read again comes back (C-7 웹 점검 D2).
  const [unlinked, setUnlinked] = useState<{ of: ListData | undefined; ids: readonly number[] }>({ of: undefined, ids: [] });
  const linked = (a: Account) => a.oidcLinked && !(unlinked.of === data && unlinked.ids.includes(a.id));

  /** What the row shows: an account whose invitation was cancelled is `SUSPENDED` in the API. */
  const shownStatus = (a: Account): AccountStatus => (a.status === 'SUSPENDED' && cancelled.has(a.id) ? 'INVITE_CANCELLED' : a.status);

  const reload = () => {
    void load.reload();
  };

  const open = (action: OpenedAction, account: Account) => {
    setOpenings((n) => n + 1);
    setRowFailure(null);
    setExportProblem(null);
    setOpened({ action, account });
  };

  // 정보 내보내기 asks first, like the audit log export. No toast: the browser shows the download (S10A-AC-29).
  const exportOf = async (account: Account) => {
    setRowFailure(null);
    setExportProblem(null);
    const check = await startExport(`/api/console/accounts/${String(account.id)}/export`, {
      navigate: (url) => {
        if (live()) window.location.assign(url);
      },
      busyText: exportBusyText,
      known: { NOT_FOUND: t('members.error.exportNotFound') },
    });
    if (!live() || check.ok) return;
    if (check.status === 401) toLogin();
    else setExportProblem(check.text);
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
    if (result.ok) toast(tf('members.reactivatedToast', { name: account.name }));
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
    else if (result.data.inviteLink === null) toast(t('members.resentToast'));
    else setShownLink({ kind: 'invite', account, link: result.data.inviteLink });
  };

  /**
   * The menu of a row (10a screens.md C-10): the usual items, then 정보 내보내기, then 정지 or
   * 초대 취소, then 익명화. An anonymized account is `SUSPENDED` in the API, so it is asked
   * first, or it would offer 정지 풀기.
   */
  const actionsOf = (a: Account): RowMenuItem[] => {
    const exportItem: RowMenuItem = {
      label: t('members.exportInfo'),
      onSelect: () => {
        void exportOf(a);
      },
    };
    if (a.anonymized) return [exportItem];
    const shown = shownStatus(a);
    const mine = a.id === me?.id;
    const items: RowMenuItem[] = [
      {
        label: t('members.renameTitle'),
        onSelect: () => {
          open('rename', a);
        },
      },
    ];
    if (shown === 'ACTIVE') {
      // Only staff roles change into each other, and never my own (S1A-AC-32, 33).
      if (!mine && a.role !== 'CUSTOMER') {
        items.push({
          label: t('members.roleTitle'),
          onSelect: () => {
            open('role', a);
          },
        });
      }
      items.push({
        label: t('members.resetTitle'),
        onSelect: () => {
          open('reset', a);
        },
      });
      items.push(exportItem);
      if (!mine) {
        items.push({
          label: t('members.suspend'),
          tone: 'danger',
          onSelect: () => {
            open('suspend', a);
          },
        });
      }
    } else if (shown === 'SUSPENDED') {
      items.push({
        label: t('members.reactivate'),
        onSelect: () => {
          void reactivate(a);
        },
      });
      items.push(exportItem);
    } else {
      items.push({
        label: t('members.resend'),
        onSelect: () => {
          void resend(a);
        },
      });
      items.push(exportItem);
      if (shown === 'INVITED') {
        items.push({
          label: t('members.cancelTitle'),
          tone: 'danger',
          onSelect: () => {
            open('cancel', a);
          },
        });
      }
    }
    // 11 screens.md C-10: only a linked account, whatever its status.
    if (linked(a)) {
      items.push({
        label: t('members.unlink'),
        onSelect: () => {
          open('unlink', a);
        },
      });
    }
    if (!mine) {
      items.push({
        label: t('members.anonymize'),
        tone: 'danger',
        onSelect: () => {
          open('anonymize', a);
        },
      });
    }
    return items;
  };

  const columns: DataTableColumn<Account>[] = [
    {
      key: 'name',
      header: t('members.name'),
      cell: (a) => (
        <span className="pd-person">
          <Avatar name={a.name} photoUrl={photoUrl(a.id, a.photoUpdatedAt)} size="sm" decorative />
          <span>{a.name}</span>
          {linked(a) && <Badge tone="neutral">{t('members.oidcLinked')}</Badge>}
        </span>
      ),
      sticky: true,
      width: '11rem',
    },
    { key: 'email', header: t('members.email'), cell: (a) => a.email },
    { key: 'role', header: t('members.roleHeader'), cell: (a) => roleWord(a.role) },
    {
      key: 'status',
      header: t('members.status'),
      // Neither 정지 nor 초대 취소됨: the account is gone for good (S10A-AC-32).
      cell: (a) => (a.anonymized ? <Badge tone="neutral">{t('members.anonymized')}</Badge> : <StatusBadge kind="account" status={shownStatus(a)} />),
    },
    { key: 'created', header: t('members.created'), cell: (a) => <RelativeTime dateTime={a.createdAt} focusable={false} /> },
    {
      key: 'actions',
      header: <span className="pd-sr-only">{t('members.actions')}</span>,
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
              {t('members.copyInviteLink')}
            </Button>
          )}
          <RowMenu label={tf('members.rowActions', { name: a.name })} items={actionsOf(a)} />
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
        <PageTitle titleRef={heading}>{t('members.title')}</PageTitle>
        <Button
          onClick={() => {
            setInviting(true);
          }}
        >
          {t('members.invite')}
        </Button>
      </div>
      <div className="pd-filters">
        <Select
          label={t('members.roleHeader')}
          placeholder={t('members.all')}
          options={roleOptions()}
          value={role}
          onChange={(value) => {
            setFilters({ role: value, status, q });
          }}
        />
        <Select
          label={t('members.status')}
          placeholder={t('members.all')}
          options={statusOptions()}
          value={status}
          onChange={(value) => {
            setFilters({ role, status: value, q });
          }}
        />
        <SearchField
          ref={searchBox}
          label={t('members.search')}
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
      {exportProblem !== null && <Alert tone="error">{exportProblem}</Alert>}
      {(load.status !== 'failed' || data) && (
        <DataTable
          caption={tf('members.caption', { count: data?.accounts.totalElements ?? 0 })}
          columns={columns}
          rows={data?.accounts.items ?? []}
          rowKey={(a) => String(a.id)}
          status={firstLoad ? 'loading' : 'ready'}
          skeletonRows={load.slow ? 8 : 0}
          density="compact"
          empty={
            outOfRange ? (
              <EmptyState title={t('members.emptyPage')} action={<Link to="/members">{t('members.toFirstPage')}</Link>} />
            ) : filtered ? (
              <EmptyState
                title={t('members.noMatch')}
                action={
                  <Button variant="secondary" size="sm" onClick={clearFilters}>
                    {t('members.clearFilters')}
                  </Button>
                }
              />
            ) : (
              <EmptyState title={t('members.empty')} />
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
      {opened?.action === 'anonymize' && (
        <AnonymizeDialog
          key={openings}
          account={opened.account}
          onClose={() => {
            setOpened(null);
          }}
          onChanged={reload}
        />
      )}
      {opened && opened.action !== 'rename' && opened.action !== 'anonymize' && (
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
          onUnlinked={(accountId) => {
            setUnlinked((now) => ({ of: data, ids: [...(now.of === data ? now.ids : []), accountId] }));
          }}
        />
      )}
      <Dialog
        open={shownLink !== null}
        title={shownLink?.kind === 'reset' ? t('members.resetLinkTitle') : t('members.inviteLinkTitle')}
        description={shownLink ? tf('members.linkFor', { name: shownLink.account.name, email: shownLink.account.email }) : ''}
        closeLabel={TEXT.close}
        onClose={closeLink}
        footer={<Button onClick={closeLink}>{TEXT.close}</Button>}
      >
        {shownLink?.kind === 'reset' && (
          <InviteLink
            link={shownLink.link}
            label={t('members.resetLinkTitle')}
            intro={t('members.noMailReset')}
          />
        )}
        {shownLink?.kind === 'invite' && (
          <InviteLink link={shownLink.link} intro={t('members.noMailInvite')} />
        )}
      </Dialog>
    </div>
  );
}
