// P-6 내 정보 (1a screens.md 1절). Three parts, each with its own
// request: 프로필, 사진, 비밀번호. The account comes from the session the frame loaded.
import { Button, PasswordField, PhotoField, Select, TextField } from '@popdesk/ui';
import { useLayoutEffect, useRef, useState, type SubmitEvent } from 'react';
import { api } from '../api';
import { FieldSummary, RateLimitBand, SaveFailure } from '../app/bands';
import { useCooldown, useFieldErrors, useLive } from '../app/hooks';
import { PageTitle } from '../app/layout';
import { textIn, useT, type Locale, type MessageKey } from '../app/locale';
import { failureText, TEXT } from '../app/messages';
import { photoUrl } from '../app/photos';
import { call, type Failure } from '../app/result';
import { useSession, type Me } from '../app/sessionContext';
import { useToast } from '../app/toastContext';

const PHOTO_TYPES = 'image/png,image/jpeg,image/webp';

type T = (key: MessageKey) => string;

function Profile(props: { me: Me; t: T }) {
  const { me, t } = props;
  const { setMe } = useSession();
  const toast = useToast();
  const live = useLive();
  const [name, setName] = useState(me.name);
  const [locale, setLocale] = useState<Locale>(me.locale);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const cooldown = useCooldown();
  const fields = useFieldErrors({
    name: { id: 'me-name', limits: { min: 1, max: 50 } },
    locale: { id: 'me-locale' },
  });

  const submit = async (e: SubmitEvent) => {
    e.preventDefault();
    if (pending || cooldown.active) return;
    setFailure(null);
    cooldown.clearEnded();
    fields.clear();
    setPending(true);
    const result = await call(api.PATCH('/api/portal/me', { body: { name, locale } }));
    if (!live()) return;
    setPending(false);
    if (result.ok) {
      // The head shows the new name and the screens the new language at once.
      setMe(result.data);
      setName(result.data.name);
      toast(textIn(result.data.locale, 'myInfo.saved'));
      return;
    }
    const f = result.failure;
    if (f.kind === 'invalid') fields.applyFields(f.fields, f.message);
    else if (f.kind === 'rateLimited') cooldown.start(f.seconds);
    else setFailure(f);
  };

  return (
    <section className="pd-section" aria-labelledby="me-profile-title">
      <h2 id="me-profile-title" className="pd-section-title">
        {t('myInfo.profile')}
      </h2>
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
          id="me-name"
          label={t('myInfo.name')}
          autoComplete="name"
          maxLength={50}
          value={name}
          onChange={setName}
          {...(fields.errors.name ? { error: fields.errors.name } : {})}
        />
        <TextField id="me-email" label={t('myInfo.email')} type="email" value={me.email} onChange={() => undefined} readOnly helpText={t('myInfo.emailHelp')} />
        <Select
          id="me-locale"
          label={t('myInfo.language')}
          options={[
            { value: 'ko', label: t('myInfo.languageKo') },
            { value: 'en', label: t('myInfo.languageEn') },
          ]}
          value={locale}
          onChange={(value) => {
            setLocale(value === 'en' ? 'en' : 'ko');
          }}
          {...(fields.errors.locale ? { error: fields.errors.locale } : {})}
        />
        <div className="pd-form-actions">
          <Button type="submit" pending={pending} pendingLabel={TEXT.sending} blocked={cooldown.active}>
            {t('myInfo.save')}
          </Button>
        </div>
      </form>
      {/* Every company the customer belongs to, whatever its status (S1A-AC-19). */}
      <dl className="pd-meta">
        <div>
          <dt>{t('myInfo.memberCompanies')}</dt>
          <dd>
            {me.companies.length === 0 ? (
              <span className="pd-muted">{t('myInfo.noMemberCompanies')}</span>
            ) : (
              <ul className="pd-me-companies">
                {me.companies.map((c) => (
                  <li key={c.code}>
                    {c.name} <span className="pd-key">{c.code}</span>
                  </li>
                ))}
              </ul>
            )}
          </dd>
        </div>
      </dl>
    </section>
  );
}

function Photo(props: { me: Me; t: T }) {
  const { me, t } = props;
  const { setMe } = useSession();
  const live = useLive();
  const [busy, setBusy] = useState(false);
  // Kept as the failure, so its text follows a language change.
  const [error, setError] = useState<Failure | null>(null);
  // The account as it is when an answer comes, not as it was when the request left.
  const latest = useRef(me);
  useLayoutEffect(() => {
    latest.current = me;
  });

  const reason = (failure: Failure): string => {
    if (failure.kind === 'tooLarge') return t('myInfo.photoTooLarge');
    return failureText(failure, {
      UNSUPPORTED_IMAGE: t('myInfo.photoUnsupported'),
      IMAGE_TOO_LARGE: t('myInfo.photoTooManyPixels'),
    });
  };

  const upload = async (file: File) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    const form = new FormData();
    form.append('file', file, file.name);
    const result = await call(api.POST('/api/portal/me/photo', { body: { file: file.name }, bodySerializer: () => form }));
    if (!live()) return;
    setBusy(false);
    if (result.ok) setMe(result.data);
    else setError(result.failure);
  };

  const remove = async () => {
    if (busy) return;
    setBusy(true);
    setError(null);
    const result = await call(api.DELETE('/api/portal/me/photo'));
    if (!live()) return;
    setBusy(false);
    if (result.ok) setMe({ ...latest.current, photoUpdatedAt: null });
    else setError(result.failure);
  };

  return (
    <section className="pd-section" aria-labelledby="me-photo-title">
      <h2 id="me-photo-title" className="pd-section-title">
        {t('myInfo.photo')}
      </h2>
      <PhotoField
        name={me.name}
        photoUrl={photoUrl(me.id, me.photoUpdatedAt)}
        uploading={busy}
        helpText={t('myInfo.photoHelp')}
        accept={PHOTO_TYPES}
        onUpload={(file) => {
          void upload(file);
        }}
        onRemove={() => {
          void remove();
        }}
        uploadLabel={t('myInfo.photoUpload')}
        changeLabel={t('myInfo.photoChange')}
        removeLabel={t('myInfo.photoRemove')}
        {...(error === null ? {} : { error: reason(error) })}
      />
    </section>
  );
}

function Password(props: { t: T }) {
  const { t } = props;
  const toast = useToast();
  const live = useLive();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const cooldown = useCooldown();
  const fields = useFieldErrors({
    currentPassword: { id: 'me-current-password' },
    newPassword: { id: 'me-new-password', limits: { min: 10, max: 72 } },
    confirm: { id: 'me-confirm-password' },
  });

  const submit = async (e: SubmitEvent) => {
    e.preventDefault();
    if (pending || cooldown.active) return;
    setFailure(null);
    cooldown.clearEnded();
    fields.clear();
    if (next !== confirm) {
      fields.setError('confirm', t('passwordReset.mismatch'));
      return;
    }
    setPending(true);
    const result = await call(api.PUT('/api/portal/me/password', { body: { currentPassword: current, newPassword: next } }));
    if (!live()) return;
    setPending(false);
    if (result.ok) {
      setCurrent('');
      setNext('');
      setConfirm('');
      toast(t('myInfo.passwordChanged'));
      return;
    }
    const f = result.failure;
    // Only the wrong field is marked; what was typed stays (S1A-AC-18).
    if (f.kind === 'rejected' && f.code === 'CURRENT_PASSWORD_WRONG') fields.setError('currentPassword', t('myInfo.currentPasswordWrong'));
    else if (f.kind === 'invalid') fields.applyFields(f.fields, f.message);
    else if (f.kind === 'rateLimited') cooldown.start(f.seconds);
    else setFailure(f);
  };

  return (
    <section className="pd-section" aria-labelledby="me-password-title">
      <h2 id="me-password-title" className="pd-section-title">
        {t('myInfo.password')}
      </h2>
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
          id="me-current-password"
          label={t('myInfo.currentPassword')}
          autoComplete="current-password"
          value={current}
          onChange={setCurrent}
          showLabel={t('myInfo.currentPasswordShow')}
          hideLabel={t('myInfo.currentPasswordHide')}
          {...(fields.errors.currentPassword ? { error: fields.errors.currentPassword } : {})}
        />
        <PasswordField
          id="me-new-password"
          label={t('passwordReset.password')}
          autoComplete="new-password"
          value={next}
          onChange={setNext}
          helpText={t('passwordReset.rules')}
          showLabel={t('passwordReset.passwordShow')}
          hideLabel={t('passwordReset.passwordHide')}
          {...(fields.errors.newPassword ? { error: fields.errors.newPassword } : {})}
        />
        <PasswordField
          id="me-confirm-password"
          label={t('passwordReset.confirm')}
          autoComplete="new-password"
          value={confirm}
          onChange={setConfirm}
          showLabel={t('passwordReset.confirmShow')}
          hideLabel={t('passwordReset.confirmHide')}
          {...(fields.errors.confirm ? { error: fields.errors.confirm } : {})}
        />
        <div className="pd-form-actions">
          <Button type="submit" pending={pending} pendingLabel={TEXT.sending} blocked={cooldown.active}>
            {t('passwordReset.submit')}
          </Button>
        </div>
      </form>
    </section>
  );
}

export function P6MyInfo() {
  const t = useT();
  const { me } = useSession();
  // The frame draws its screens only with a session.
  if (!me) return null;
  return (
    <div className="pd-screen pd-me">
      <div className="pd-screen-head">
        <PageTitle>{t('myInfo.title')}</PageTitle>
      </div>
      <Profile me={me} t={t} />
      <Photo me={me} t={t} />
      <Password t={t} />
    </div>
  );
}
