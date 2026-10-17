// C-1 로그인 (screens.md 4절, P-1과 같다). Sign-in goes to `next` or C-3.
import { Alert, Button, PasswordField, TextField } from '@popdesk/ui';
import { useEffect, useLayoutEffect, useRef, useState, type SubmitEvent } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router';
import { api } from '../api';
import { FieldSummary, RateLimitBand, SaveFailure } from '../app/bands';
import { useCooldown, useDocumentTitle, useFieldErrors } from '../app/hooks';
import { TEXT } from '../app/messages';
import { useLiveNavigate } from '../app/navigate';
import { call, type Failure } from '../app/result';
import { safeNext, useSession, type LoginState } from '../app/sessionContext';
import { Brand } from '../app/layout';

const SIGN_IN_FAILED = '이메일 또는 비밀번호가 맞지 않습니다.';

export function C1Login() {
  const navigate = useLiveNavigate();
  const location = useLocation();
  const [params] = useSearchParams();
  const { setMe } = useSession();
  const next = safeNext(params.get('next'));
  const expired = (location.state as LoginState | null)?.expired === true;

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [wrong, setWrong] = useState(false);
  const cooldown = useCooldown();
  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const focusPassword = useRef(false);
  const fields = useFieldErrors({
    email: { id: 'login-email', limits: { format: '이메일' } },
    password: { id: 'login-password' },
  });

  useDocumentTitle('로그인');

  useLayoutEffect(() => {
    emailRef.current?.focus();
  }, []);

  // Already signed in: straight to the list (screens.md P-1 이미 로그인됨).
  useEffect(() => {
    let live = true;
    void call(api.GET('/api/console/me')).then((result) => {
      if (!live || !result.ok) return;
      setMe(result.data);
      void navigate(next, { replace: true });
    });
    return () => {
      live = false;
    };
    // Checked once when the screen opens.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // After a failed sign-in the password field takes focus in the commit that clears it.
  useLayoutEffect(() => {
    if (!focusPassword.current) return;
    focusPassword.current = false;
    passwordRef.current?.focus();
  });

  const submit = async (e: SubmitEvent) => {
    e.preventDefault();
    if (pending || cooldown.active) return;
    setPending(true);
    setFailure(null);
    setWrong(false);
    cooldown.clearEnded();
    fields.clear();
    const result = await call(api.POST('/api/public/sign-in', { body: { email, password } }));
    if (result.ok) {
      const me = await call(api.GET('/api/console/me'));
      if (me.ok) setMe(me.data);
      void navigate(next, { replace: true });
      return;
    }
    setPending(false);
    const f = result.failure;
    if (f.kind === 'unauthenticated') {
      // One text whatever the reason (AUTH-1~3); only the password is cleared.
      setWrong(true);
      setPassword('');
      focusPassword.current = true;
    } else if (f.kind === 'invalid') {
      fields.applyFields(f.fields, f.message);
    } else if (f.kind === 'rateLimited') {
      cooldown.start(f.seconds);
    } else {
      setFailure(f);
    }
  };

  return (
    <main id="main" className="pd-auth" tabIndex={-1}>
      <div className="pd-auth-panel">
        <Brand />
        <h1 className="pd-auth-title" tabIndex={-1}>
          로그인
        </h1>
        <form
          className="pd-form"
          noValidate
          onSubmit={(e) => {
            void submit(e);
          }}
        >
          {expired && !wrong && <Alert tone="info">{TEXT.sessionEnded}</Alert>}
          {wrong && <Alert tone="error">{SIGN_IN_FAILED}</Alert>}
          <FieldSummary summary={fields.summary} />
          <SaveFailure failure={failure} />
          <RateLimitBand {...cooldown} />
          <TextField
            ref={emailRef}
            id="login-email"
            label="이메일"
            type="email"
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            value={email}
            onChange={setEmail}
            {...(fields.errors.email ? { error: fields.errors.email } : {})}
          />
          <PasswordField
            ref={passwordRef}
            id="login-password"
            label="비밀번호"
            autoComplete="current-password"
            value={password}
            onChange={setPassword}
            showLabel="비밀번호 보이기"
            hideLabel="비밀번호 숨기기"
            {...(fields.errors.password ? { error: fields.errors.password } : {})}
          />
          <Button type="submit" pending={pending} pendingLabel={TEXT.sending} blocked={cooldown.active}>
            로그인
          </Button>
        </form>
        <p className="pd-auth-links">
          <Link to="/forgot-password">비밀번호를 잊었나요?</Link>
        </p>
      </div>
    </main>
  );
}
