// C-16 비밀번호 찾기 (1a screens.md 1절, P-9와 같다). Signed out. The answer is
// the same whether the address has an account or not (S1A-AC-59).
import { Button, TextField } from '@popdesk/ui';
import { useLayoutEffect, useRef, useState, type SubmitEvent } from 'react';
import { Link } from 'react-router';
import { api } from '../api';
import { FieldSummary, RateLimitBand, SaveFailure } from '../app/bands';
import { useCooldown, useDocumentTitle, useFieldErrors, useLive } from '../app/hooks';
import { t } from '../app/locale';
import { fieldText, TEXT } from '../app/messages';
import { call, type Failure } from '../app/result';

const EMAIL_SHAPE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function C16PasswordForgot() {
  const live = useLive();
  const [email, setEmail] = useState('');
  const [pending, setPending] = useState(false);
  const [sent, setSent] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const cooldown = useCooldown();
  const emailRef = useRef<HTMLInputElement>(null);
  const notice = useRef<HTMLParagraphElement>(null);
  const fields = useFieldErrors({
    email: { id: 'forgot-email', limits: { format: '이메일' } },
  });

  useDocumentTitle(t('passwordForgot.title'));

  useLayoutEffect(() => {
    emailRef.current?.focus();
  }, []);

  // The notice takes focus in the commit that replaces the form with it.
  useLayoutEffect(() => {
    if (sent) notice.current?.focus();
  }, [sent]);

  const submit = async (e: SubmitEvent) => {
    e.preventDefault();
    if (pending || cooldown.active) return;
    setFailure(null);
    cooldown.clearEnded();
    fields.clear();
    const address = email.trim();
    if (!EMAIL_SHAPE.test(address)) {
      fields.setError('email', fieldText(address === '' ? 'REQUIRED' : 'INVALID_FORMAT', { format: '이메일' }));
      return;
    }
    setPending(true);
    const result = await call(api.POST('/api/public/password-reset/request', { body: { email: address } }));
    if (!live()) return;
    setPending(false);
    if (result.ok) {
      setSent(true);
      return;
    }
    const f = result.failure;
    if (f.kind === 'invalid') fields.applyFields(f.fields, f.message);
    else if (f.kind === 'rateLimited') cooldown.start(f.seconds);
    else setFailure(f);
  };

  return (
    <main id="main" className="pd-auth" tabIndex={-1}>
      <div className="pd-auth-panel">
        <p className="pd-auth-brand">{TEXT.displayName}</p>
        <h1 className="pd-auth-title" tabIndex={-1}>
          {t('passwordForgot.title')}
        </h1>
        {sent ? (
          <p ref={notice} className="pd-auth-lead" tabIndex={-1}>
            {t('passwordForgot.sent')}
          </p>
        ) : (
          <>
            <p className="pd-auth-lead">{t('passwordForgot.lead')}</p>
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
              <TextField
                ref={emailRef}
                id="forgot-email"
                label={t('passwordForgot.email')}
                type="email"
                autoComplete="username"
                autoCapitalize="none"
                spellCheck={false}
                value={email}
                onChange={setEmail}
                {...(fields.errors.email ? { error: fields.errors.email } : {})}
              />
              <Button type="submit" pending={pending} pendingLabel={TEXT.sending} blocked={cooldown.active}>
                {t('passwordForgot.submit')}
              </Button>
            </form>
          </>
        )}
        <p className="pd-auth-links">
          <Link to="/login">{t('passwordForgot.toLogin')}</Link>
        </p>
      </div>
    </main>
  );
}
