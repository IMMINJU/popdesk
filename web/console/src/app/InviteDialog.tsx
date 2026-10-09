// 초대 대화상자 (screens.md C-10 초대, C-9 "이 고객사로 고객 초대"). Without SMTP
// the dialog stays open and shows the link once (INVITE-6, S0-AC-38); the link
// lives only in this component's state and goes when the dialog closes (MAIL-3).
import { Alert, Button, CheckboxGroup, ConfirmDialog, Dialog, RadioGroup, TextField } from '@popdesk/ui';
import { useEffect, useLayoutEffect, useRef, useState, type SubmitEvent } from 'react';
import { Link } from 'react-router';
import { api, type Schemas } from '../api';
import { FieldSummary, RateLimitBand, SaveFailure } from './bands';
import { InviteLink } from './InviteLink';
import { useCooldown, useFieldErrors } from './hooks';
import { TEXT } from './messages';
import { call, type Failure } from './result';
import { useToast } from './toastContext';

type Role = Schemas['CreateInvitationRequest']['role'];
type Company = Schemas['Company'];

const ROLES: { value: Role; label: string }[] = [
  { value: 'CUSTOMER', label: '고객' },
  { value: 'AGENT', label: '상담원' },
  { value: 'ADMIN', label: '관리자' },
];

const FORM_ID = 'invite-form';

export interface InviteDialogProps {
  open: boolean;
  onClose: () => void;
  /** Called after an invitation was made, so the screen reloads its list. */
  onInvited: () => void;
  /** Opens with this role and these companies chosen (C-9). */
  preset?: { role: Role; companyCodes: string[] };
}

export function InviteDialog(props: InviteDialogProps) {
  const toast = useToast();
  const [email, setEmail] = useState('');
  const [name, setName] = useState('');
  const [role, setRole] = useState<Role | null>(props.preset?.role ?? null);
  const [companyCodes, setCompanyCodes] = useState<string[]>(props.preset?.companyCodes ?? []);
  const [companies, setCompanies] = useState<Company[] | null>(null);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [link, setLink] = useState<string | null>(null);
  const [askDiscard, setAskDiscard] = useState(false);
  const cooldown = useCooldown();
  const open = useRef(props.open);
  useLayoutEffect(() => {
    open.current = props.open;
  });
  const fields = useFieldErrors({
    email: { id: 'invite-email', limits: { max: 100, format: '이메일' } },
    name: { id: 'invite-name', limits: { max: 50 } },
    role: { id: 'invite-role' },
    companyCodes: { id: 'invite-companies', text: { REQUIRED: '고객사를 하나 이상 고르세요.', UNKNOWN_VALUE: '고를 수 없는 고객사가 있습니다.' } },
  });

  useEffect(() => {
    if (!props.open) return undefined;
    let live = true;
    void call(api.GET('/api/console/companies', { params: { query: { size: 100 } } })).then((result) => {
      if (live) setCompanies(result.ok ? result.data.items.filter((c) => c.status === 'ACTIVE') : []);
    });
    return () => {
      live = false;
    };
  }, [props.open]);

  const reset = () => {
    setEmail('');
    setName('');
    setRole(props.preset?.role ?? null);
    setCompanyCodes(props.preset?.companyCodes ?? []);
    setFailure(null);
    setLink(null);
    setAskDiscard(false);
    fields.clear();
  };

  const close = () => {
    reset();
    props.onClose();
  };

  const dirty = link === null && (email !== '' || name !== '');
  const requestClose = () => {
    if (dirty && !pending) setAskDiscard(true);
    else close();
  };

  const submit = async (e: SubmitEvent) => {
    e.preventDefault();
    if (pending || cooldown.active) return;
    setFailure(null);
    cooldown.clearEnded();
    fields.clear();
    if (role === null) {
      fields.setError('role', '역할을 고르세요.');
      return;
    }
    setPending(true);
    const body = role === 'CUSTOMER' ? { email, name, role, companyCodes } : { email, name, role };
    const result = await call(api.POST('/api/console/invitations', { body }));
    setPending(false);
    if (result.ok) {
      props.onInvited();
      if (!open.current) return;
      if (result.data.inviteLink === null) {
        toast('초대 메일을 보냈습니다');
        close();
      } else {
        setLink(result.data.inviteLink);
      }
      return;
    }
    const f = result.failure;
    if (f.kind === 'invalid') fields.applyFields(f.fields, f.message);
    else if (f.kind === 'rejected' && f.code === 'EMAIL_TAKEN') fields.setError('email', '이미 등록된 이메일입니다.');
    else if (f.kind === 'rateLimited') cooldown.start(f.seconds);
    else setFailure(f);
  };

  const footer =
    link === null ? (
      <>
        <Button variant="secondary" onClick={requestClose}>
          {TEXT.cancel}
        </Button>
        <Button type="submit" form={FORM_ID} pending={pending} pendingLabel={TEXT.sending} blocked={cooldown.active}>
          초대 보내기
        </Button>
      </>
    ) : (
      <Button onClick={close}>{TEXT.close}</Button>
    );

  return (
    <>
      <Dialog open={props.open} title="구성원 초대" closeLabel={TEXT.close} pending={pending} onClose={requestClose} footer={footer} size="lg">
        {link === null ? (
          <form
            id={FORM_ID}
            className="pd-form"
            noValidate
            onSubmit={(e) => {
              void submit(e);
            }}
          >
            <FieldSummary summary={fields.summary} />
            <SaveFailure failure={failure} />
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
              value={name}
              onChange={setName}
              maxLength={50}
              {...(fields.errors.name ? { error: fields.errors.name } : {})}
            />
            <RadioGroup
              id="invite-role"
              name="invite-role"
              legend="역할"
              options={ROLES}
              value={role}
              onChange={setRole}
              orientation="horizontal"
              {...(fields.errors.role ? { error: fields.errors.role } : {})}
            />
            {role === 'CUSTOMER' &&
              (companies !== null && companies.length === 0 ? (
                <Alert tone="info">
                  먼저 고객사를 만드세요. 고객은 고객사에 속해야 문의를 올립니다. <Link to="/companies">고객사 목록으로</Link>
                </Alert>
              ) : (
                <CheckboxGroup
                  id="invite-companies"
                  legend="소속 고객사"
                  name="invite-companies"
                  options={(companies ?? []).map((c) => ({ value: c.code, label: `${c.name} (${c.code})` }))}
                  value={companyCodes}
                  onChange={setCompanyCodes}
                  helpText="고객은 고른 고객사의 문의를 봅니다."
                  {...(fields.errors.companyCodes ? { error: fields.errors.companyCodes } : {})}
                />
              ))}
          </form>
        ) : (
          <InviteLink link={link} intro="메일 설정이 없어 초대 메일이 나가지 않았습니다. 아래 링크를 복사해 직접 전하세요." />
        )}
      </Dialog>
      <ConfirmDialog
        open={askDiscard}
        title="쓰던 내용을 버릴까요?"
        message="닫으면 이 대화상자에 쓴 값이 사라집니다."
        confirmLabel="버리고 닫기"
        cancelLabel="계속 쓰기"
        tone="danger"
        onConfirm={close}
        onCancel={() => {
          setAskDiscard(false);
        }}
      />
    </>
  );
}
