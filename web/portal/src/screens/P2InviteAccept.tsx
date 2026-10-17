// P-2 초대 수락 (screens.md 3절). The token comes only in the
// address fragment, goes only in the request body, and leaves the address bar.
import { Button, EmptyState, LockIcon, PasswordField } from '@popdesk/ui';
import { useLayoutEffect, useRef, useState, type SubmitEvent } from 'react';
import { Link } from 'react-router';
import { api } from '../api';
import { FieldSummary, RateLimitBand, SaveFailure } from '../app/bands';
import { useCooldown, useDocumentTitle, useFieldErrors } from '../app/hooks';
import { TEXT } from '../app/messages';
import { useLiveNavigate } from '../app/navigate';
import { call, type Failure } from '../app/result';
import { useSession } from '../app/sessionContext';
import { useToast } from '../app/toastContext';
import { useBranding } from '../app/branding';
import { Brand } from '../app/layout';

const RULES = '10~72자로 정합니다. 공백만으로는 안 되고, 이메일과 같으면 안 됩니다.';
const INVALID_LINK = '이 초대 링크는 쓸 수 없습니다. 관리자에게 다시 보내 달라고 하세요.';

function tokenFromHash(): string | null {
  const params = new URLSearchParams(window.location.hash.replace(/^#/, ''));
  const token = params.get('token');
  return token === null || token === '' ? null : token;
}

export function P2InviteAccept() {
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

  useDocumentTitle('초대 수락');

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
      fields.setError('confirm', '두 칸의 비밀번호가 다릅니다.');
      return;
    }
    setPending(true);
    const result = await call(api.POST('/api/public/invitations/accept', { body: { token, password } }));
    if (result.ok) {
      const me = await call(api.GET('/api/portal/me'));
      if (me.ok) setMe(me.data);
      toast('환영합니다');
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
            title="초대 링크를 쓸 수 없습니다"
            description={INVALID_LINK}
            action={<Link to="/login">로그인 화면으로</Link>}
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
          초대 수락
        </h1>
        <p className="pd-auth-lead">{displayName} 고객 포털에 오신 것을 환영합니다. 쓸 비밀번호를 정하면 바로 시작합니다.</p>
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
            label="비밀번호"
            autoComplete="new-password"
            value={password}
            onChange={setPassword}
            helpText={RULES}
            showLabel="비밀번호 보이기"
            hideLabel="비밀번호 숨기기"
            {...(fields.errors.password ? { error: fields.errors.password } : {})}
          />
          <PasswordField
            id="invite-confirm"
            label="비밀번호 확인"
            autoComplete="new-password"
            value={confirm}
            onChange={setConfirm}
            showLabel="비밀번호 확인 보이기"
            hideLabel="비밀번호 확인 숨기기"
            {...(fields.errors.confirm ? { error: fields.errors.confirm } : {})}
          />
          <Button type="submit" pending={pending} pendingLabel={TEXT.sending} blocked={cooldown.active}>
            시작하기
          </Button>
        </form>
      </div>
    </main>
  );
}
