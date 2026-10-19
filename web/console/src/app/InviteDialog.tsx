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
import { t } from './locale';

type Role = Schemas['CreateInvitationRequest']['role'];
type Company = Schemas['Company'];

const ROLES: readonly Role[] = ['CUSTOMER', 'AGENT', 'ADMIN'];

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
  // Each send gets a number; closing moves it on, so an answer to a send made
  // before the dialog closed is dropped even when the dialog is open again (V-2).
  const request = useRef(0);
  const open = useRef(props.open);
  useLayoutEffect(() => {
    open.current = props.open;
  });
  const fields = useFieldErrors({
    email: { id: 'invite-email', limits: { max: 100, format: t('field.email') } },
    name: { id: 'invite-name', limits: { max: 50 } },
    role: { id: 'invite-role' },
    companyCodes: { id: 'invite-companies', text: { REQUIRED: t('inviteDialog.companiesRequired'), UNKNOWN_VALUE: t('inviteDialog.companiesUnknown') } },
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
    request.current += 1;
    setPending(false);
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
      fields.setError('role', t('inviteDialog.roleRequired'));
      return;
    }
    const mine = (request.current += 1);
    setPending(true);
    const body = role === 'CUSTOMER' ? { email, name, role, companyCodes } : { email, name, role };
    const result = await call(api.POST('/api/console/invitations', { body }));
    if (result.ok) props.onInvited();
    if (request.current !== mine) return;
    setPending(false);
    if (!open.current) return;
    if (result.ok) {
      if (result.data.inviteLink === null) {
        toast(t('inviteDialog.sentToast'));
        close();
      } else {
        setLink(result.data.inviteLink);
      }
      return;
    }
    const f = result.failure;
    if (f.kind === 'invalid') fields.applyFields(f.fields, f.message);
    else if (f.kind === 'rejected' && f.code === 'EMAIL_TAKEN') fields.setError('email', t('inviteDialog.emailTaken'));
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
          {t('inviteDialog.send')}
        </Button>
      </>
    ) : (
      <Button onClick={close}>{TEXT.close}</Button>
    );

  return (
    <>
      <Dialog open={props.open} title={t('inviteDialog.title')} closeLabel={TEXT.close} pending={pending} onClose={requestClose} footer={footer} size="lg">
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
              label={t('inviteDialog.email')}
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
              label={t('inviteDialog.name')}
              value={name}
              onChange={setName}
              maxLength={50}
              {...(fields.errors.name ? { error: fields.errors.name } : {})}
            />
            <RadioGroup
              id="invite-role"
              name="invite-role"
              legend={t('inviteDialog.role')}
              options={ROLES.map((value) => ({ value, label: t(`members.role.${value}`) }))}
              value={role}
              onChange={setRole}
              orientation="horizontal"
              {...(fields.errors.role ? { error: fields.errors.role } : {})}
            />
            {role === 'CUSTOMER' &&
              (companies !== null && companies.length === 0 ? (
                <Alert tone="info">
                  {t('inviteDialog.noCompanies')} <Link to="/companies">{t('inviteDialog.toCompanies')}</Link>
                </Alert>
              ) : (
                <CheckboxGroup
                  id="invite-companies"
                  legend={t('inviteDialog.companies')}
                  name="invite-companies"
                  options={(companies ?? []).map((c) => ({ value: c.code, label: `${c.name} (${c.code})` }))}
                  value={companyCodes}
                  onChange={setCompanyCodes}
                  helpText={t('inviteDialog.companiesHelp')}
                  {...(fields.errors.companyCodes ? { error: fields.errors.companyCodes } : {})}
                />
              ))}
          </form>
        ) : (
          <InviteLink link={link} intro={t('inviteDialog.noMail')} />
        )}
      </Dialog>
      <ConfirmDialog
        open={askDiscard}
        title={t('inviteDialog.discardTitle')}
        message={t('inviteDialog.discardMessage')}
        confirmLabel={t('inviteDialog.discard')}
        cancelLabel={t('inviteDialog.keepWriting')}
        tone="danger"
        onConfirm={close}
        onCancel={() => {
          setAskDiscard(false);
        }}
      />
    </>
  );
}
