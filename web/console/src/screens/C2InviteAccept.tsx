// C-2 초대 수락 (screens.md 4절, P-2와 같다). The token comes only in the
// address fragment, goes only in the request body, and leaves the address bar.
import { Button, EmptyState, LockIcon, PasswordField } from '@popdesk/ui';
import { useLayoutEffect, useRef, useState, type SubmitEvent } from 'react';
import { Link } from 'react-router';
import { api } from '../api';
import { FieldSummary, RateLimitBand, SaveFailure } from '../app/bands';
import { useCooldown, useDocumentTitle, useFieldErrors } from '../app/hooks';
import { t, tf } from '../app/locale';
import { TEXT } from '../app/messages';
import { useLiveNavigate } from '../app/navigate';
import { call, type Failure } from '../app/result';
import { useSession } from '../app/sessionContext';
import { useToast } from '../app/toastContext';
import { useBranding } from '../app/branding';
import { Brand } from '../app/layout';


function tokenFromHash(): string | null {
  const params = new URLSearchParams(window.location.hash.replace(/^#/, ''));
  const token = params.get('token');
  return token === null || token === '' ? null : token;
}

export function C2InviteAccept() {
  const { displayName } = useBranding();
  const navigate = useLiveNavigate();
  const toast = useToast();
  const { setMe } = useSession();
  // Read once: the fragment is cleared right after.
  const [token] = useState(tokenFromHash);
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [invalid, setInvalid] = useState(token === null);
  const cooldown = useCooldown();
  const passwordRef = useRef<HTMLInputElement>(null);
  const fields = useFieldErrors({
    password: { id: 'invite-password', limits: { min: 10, max: 72 } },
    confirm: { id: 'invite-confirm' },
  });

  useDocumentTitle(t('invite.title'));

  useLayoutEffect(() => {
    // Keep the token out of the address bar and of later Referer headers (UX-8).
    if (window.location.hash !== '') window.history.replaceState(window.history.state, '', window.location.pathname);
    passwordRef.current?.focus();
  }, []);

  const submit = async (e: SubmitEvent) => {
    e.preventDefault();
    if (pending || cooldown.active || token === null) return;
    setFailure(null);
    cooldown.clearEnded();
    fields.clear();
    if (password !== confirm) {
      fields.setError('confirm', t('passwordReset.mismatch'));
      return;
    }
    setPending(true);
    const result = await call(api.POST('/api/public/invitations/accept', { body: { token, password } }));
    if (result.ok) {
      const me = await call(api.GET('/api/console/me'));
      if (me.ok) setMe(me.data);
      toast(t('invite.welcome'));
      void navigate('/issues', { replace: true });
      return;
    }
    setPending(false);
    const f = result.failure;
    if (f.kind === 'rejected' && f.code === 'INVITATION_INVALID') setInvalid(true);
    else if (f.kind === 'invalid') fields.applyFields(f.fields, f.message);
    else if (f.kind === 'rateLimited') cooldown.start(f.seconds);
    else setFailure(f);
  };

  if (invalid) {
    return (
      <main id="main" className="pd-auth" tabIndex={-1}>
        <div className="pd-auth-panel">
          <Brand />
          <EmptyState
            headingLevel={1}
            icon={<LockIcon size={20} />}
            title={t('invite.invalidTitle')}
            description={t('invite.invalid')}
            action={<Link to="/login">{t('invite.toLogin')}</Link>}
          />
        </div>
      </main>
    );
  }

  return (
    <main id="main" className="pd-auth" tabIndex={-1}>
      <div className="pd-auth-panel">
        <Brand />
        <h1 className="pd-auth-title" tabIndex={-1}>
          {t('invite.title')}
        </h1>
        <p className="pd-auth-lead">{tf('invite.leadConsole', { name: displayName })}</p>
        <form
          className="pd-form"
          noValidate
          onSubmit={(e) => {
            void submit(e);
          }}
        >
          <FieldSummary summary={fields.summary} />
          <SaveFailure failure={failure} />
          <RateLimitBand {...cooldown} />
          <PasswordField
            ref={passwordRef}
            id="invite-password"
            label={t('login.password')}
            autoComplete="new-password"
            value={password}
            onChange={setPassword}
            helpText={t('passwordReset.rules')}
            showLabel={t('login.passwordShow')}
            hideLabel={t('login.passwordHide')}
            {...(fields.errors.password ? { error: fields.errors.password } : {})}
          />
          <PasswordField
            id="invite-confirm"
            label={t('invite.confirm')}
            autoComplete="new-password"
            value={confirm}
            onChange={setConfirm}
            showLabel={t('invite.confirmShow')}
            hideLabel={t('invite.confirmHide')}
            {...(fields.errors.confirm ? { error: fields.errors.confirm } : {})}
          />
          <Button type="submit" pending={pending} pendingLabel={TEXT.sending} blocked={cooldown.active}>
            {t('invite.submit')}
          </Button>
        </form>
      </div>
    </main>
  );
}
