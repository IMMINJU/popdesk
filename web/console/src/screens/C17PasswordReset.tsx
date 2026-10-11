// C-17 비밀번호 재설정 (1a screens.md 1절, P-10과 같다). The token comes only in
// the address fragment, goes only in the request body, and leaves the address
// bar. Success does not sign in (S1A-AC-09).
import { Button, EmptyState, LockIcon, PasswordField } from '@popdesk/ui';
import { useLayoutEffect, useRef, useState, type SubmitEvent } from 'react';
import { Link } from 'react-router';
import { api } from '../api';
import { FieldSummary, RateLimitBand, SaveFailure } from '../app/bands';
import { useCooldown, useDocumentTitle, useFieldErrors, useLive } from '../app/hooks';
import { useT } from '../app/locale';
import { TEXT } from '../app/messages';
import { useLiveNavigate } from '../app/navigate';
import { call, type Failure } from '../app/result';
import { useToast } from '../app/toastContext';

function tokenFromHash(): string | null {
  const params = new URLSearchParams(window.location.hash.replace(/^#/, ''));
  const token = params.get('token');
  return token === null || token === '' ? null : token;
}

export function C17PasswordReset() {
  const t = useT();
  const live = useLive();
  const navigate = useLiveNavigate();
  const toast = useToast();
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
    password: { id: 'reset-password', limits: { min: 10, max: 72 } },
    confirm: { id: 'reset-confirm' },
  });

  useDocumentTitle(t('passwordReset.title'));

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
    const result = await call(api.POST('/api/public/password-reset/confirm', { body: { token, password } }));
    if (!live()) return;
    if (result.ok) {
      toast(t('passwordReset.done'));
      void navigate('/login', { replace: true });
      return;
    }
    setPending(false);
    const f = result.failure;
    // A rule error keeps the token, so the form stays (S1A-AC-11); a bad token ends the screen.
    if (f.kind === 'rejected' && f.code === 'PASSWORD_RESET_INVALID') setInvalid(true);
    else if (f.kind === 'invalid' && f.fields.some((p) => p.field === 'token')) setInvalid(true);
    else if (f.kind === 'invalid') fields.applyFields(f.fields, f.message);
    else if (f.kind === 'rateLimited') cooldown.start(f.seconds);
    else setFailure(f);
  };

  if (invalid) {
    return (
      <main id="main" className="pd-auth" tabIndex={-1}>
        <div className="pd-auth-panel">
          <p className="pd-auth-brand">{TEXT.displayName}</p>
          <EmptyState
            headingLevel={1}
            icon={<LockIcon size={20} />}
            title={t('passwordReset.invalidTitle')}
            description={t('passwordReset.invalid')}
            action={<Link to="/forgot-password">{t('passwordReset.retry')}</Link>}
          />
        </div>
      </main>
    );
  }

  return (
    <main id="main" className="pd-auth" tabIndex={-1}>
      <div className="pd-auth-panel">
        <p className="pd-auth-brand">{TEXT.displayName}</p>
        <h1 className="pd-auth-title" tabIndex={-1}>
          {t('passwordReset.title')}
        </h1>
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
            id="reset-password"
            label={t('passwordReset.password')}
            autoComplete="new-password"
            value={password}
            onChange={setPassword}
            helpText={t('passwordReset.rules')}
            showLabel={t('passwordReset.passwordShow')}
            hideLabel={t('passwordReset.passwordHide')}
            {...(fields.errors.password ? { error: fields.errors.password } : {})}
          />
          <PasswordField
            id="reset-confirm"
            label={t('passwordReset.confirm')}
            autoComplete="new-password"
            value={confirm}
            onChange={setConfirm}
            showLabel={t('passwordReset.confirmShow')}
            hideLabel={t('passwordReset.confirmHide')}
            {...(fields.errors.confirm ? { error: fields.errors.confirm } : {})}
          />
          <Button type="submit" pending={pending} pendingLabel={TEXT.sending} blocked={cooldown.active}>
            {t('passwordReset.submit')}
          </Button>
        </form>
      </div>
    </main>
  );
}
