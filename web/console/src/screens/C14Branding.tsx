// C-14 브랜딩 (관리자, 9-branding screens.md 2절). The display name and the accent are saved together
// with the version that was read (a 409 means another admin saved first); each logo is uploaded or
// removed at once, apart from the save button. A save or logo answer also changes the branding of
// the whole app, even after the screen is gone (screens.md 6절).
import { Alert, Button, Checkbox, LogoField, TextField } from '@popdesk/ui';
import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { api, type Schemas } from '../api';
import { FieldSummary, SaveFailure } from '../app/bands';
import { useBranding } from '../app/branding';
import { useMainBusy } from '../app/busy';
import { useFieldErrors, useLive, useLoad, type FieldSpec } from '../app/hooks';
import { LoadFailed, PageTitle } from '../app/layout';
import { t, texts, tf } from '../app/locale';
import { failureText, TEXT } from '../app/messages';
import { call, type Failure } from '../app/result';

type Branding = Schemas['Branding'];
type BrandingLogo = Schemas['BrandingLogo'];
type AccentColors = Schemas['AccentColors'];
type Theme = 'light' | 'dark';

const THEMES: readonly Theme[] = ['light', 'dark'];
// Read each time they are used, so they follow the screen language.
const THEME_NAMES = texts<Theme>({ light: 'branding.lightTheme', dark: 'branding.darkTheme' });
const LOGO_LABELS = texts<Theme>({ light: 'branding.lightLogo', dark: 'branding.darkLogo' });
const WORDS = texts({
  nameHelp: 'branding.nameHelp',
  logoHelp: 'branding.logoHelp',
  lowContrast: 'branding.lowContrast',
  badFormat: 'branding.badFormat',
  previewFailed: 'branding.previewFailed',
  defaultColor: 'branding.defaultColor',
  nothingChanged: 'branding.nothingChanged',
  saved: 'branding.saved',
  readFailed: 'branding.readFailed',
  conflict: 'branding.conflict',
  logoTooBig: 'branding.logoTooBig',
  logoUnsupported: 'branding.logoUnsupported',
  logoTooManyPixels: 'branding.logoTooManyPixels',
});
const LOGO_TYPES = 'image/png,image/jpeg,image/webp';
/** The color input's value while the text field holds no right color yet: the default emerald. */
const DEFAULT_PICK = '#047857';
const PREVIEW_DELAY_MS = 300;
/** The least contrast of a theme the server takes (D-61). */
const ENOUGH = 4.5;

const HEX = /^#[0-9A-Fa-f]{6}$/;

type FieldName = 'displayName' | 'accent';
const SPECS: Record<FieldName, FieldSpec> = {
  displayName: {
    id: 'branding-name',
    text: texts({ REQUIRED: 'branding.nameRequired', BLANK: 'branding.nameRequired', TOO_LONG: 'branding.nameTooLong', INVALID_FORMAT: 'branding.nameBadChars' }),
  },
  accent: { id: 'branding-accent', text: texts({ INVALID_FORMAT: 'branding.badFormat', LOW_CONTRAST: 'branding.lowContrast' }) },
};

interface Draft {
  name: string;
  /** The accent text as typed; it stays while "기본 색 쓰기" is on, and comes back when it is turned off. */
  accent: string;
  useDefault: boolean;
}

/** What the two preview panes show. */
type Preview =
  | { kind: 'default' }
  | { kind: 'colors'; color: string; colors: AccentColors; contrast: { light: number; dark: number } }
  | { kind: 'failed' };

function draftOf(b: Branding): Draft {
  return { name: b.displayName, accent: b.accent ?? '', useDefault: b.accent === null };
}

function previewOf(b: Branding): Preview {
  return b.accent !== null && b.colors !== null && b.contrast !== null
    ? { kind: 'colors', color: b.accent, colors: b.colors, contrast: b.contrast }
    : { kind: 'default' };
}

/** The name as the server compares it: trimmed and in NFC (design.md 9절). */
function cleanName(name: string): string {
  return name.trim().normalize('NFC');
}

function isRefused(preview: Preview, accent: string): boolean {
  return preview.kind === 'colors' && preview.color === accent && THEMES.some((t) => preview.contrast[t] < ENOUGH);
}

function logoFailureText(failure: Failure): string {
  if (failure.kind === 'tooLarge') return WORDS.logoTooBig;
  return failureText(failure, { UNSUPPORTED_IMAGE: WORDS.logoUnsupported, IMAGE_TOO_LARGE: WORDS.logoTooManyPixels });
}

// Optional props are left out, not given as undefined (exactOptionalPropertyTypes).
const errorProp = (text: string | undefined) => (text === undefined ? {} : { error: text });
const helpProp = (text: string | undefined) => (text === undefined ? {} : { helpText: text });

/** One theme's pane: the samples on that theme's own bg, with the colors given to the pane only. */
function Pane(props: { theme: Theme; preview: Preview }) {
  const { theme, preview } = props;
  const name = THEME_NAMES[theme];
  if (preview.kind === 'default') {
    return (
      <div className="pd-brand-pane" data-pane-theme={theme}>
        <p className="pd-brand-pane-title">{name}</p>
        <Samples />
        <p className="pd-brand-pane-line">{WORDS.defaultColor}</p>
      </div>
    );
  }
  if (preview.kind === 'failed') return null;
  const colors = preview.colors[theme];
  const contrast = preview.contrast[theme];
  const style = {
    '--pv-accent': colors.accent,
    '--pv-on-accent': colors.onAccent,
    '--pv-accent-subtle': colors.accentSubtle,
  } as CSSProperties;
  return (
    <div className="pd-brand-pane" data-pane-theme={theme} style={style}>
      <p className="pd-brand-pane-title">{name}</p>
      {contrast >= ENOUGH ? (
        <Samples />
      ) : (
        // Text that would not read is not drawn: the colors only.
        <div className="pd-brand-swatches" aria-hidden="true">
          <span className="pd-brand-swatch" />
          <span className="pd-brand-swatch" data-subtle="true" />
        </div>
      )}
      <p className="pd-brand-pane-line">
        {tf('branding.paneLine', { accent: colors.accent, contrast: contrast.toFixed(2) })}
      </p>
    </div>
  );
}

function Samples() {
  return (
    <div className="pd-brand-samples" aria-hidden="true">
      <span className="pd-brand-sample-button">{t('branding.sampleButton')}</span>
      <span className="pd-brand-sample-link">{t('branding.sampleLink')}</span>
      <span className="pd-brand-sample-row">{t('branding.sampleRow')}</span>
    </div>
  );
}

export function C14Branding() {
  const live = useLive();
  const app = useBranding();
  const load = useLoad(() => call(api.GET('/api/console/branding')), 'branding');
  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);
  const fields = useFieldErrors(SPECS);
  const [branding, setBranding] = useState<Branding | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [preview, setPreview] = useState<Preview>({ kind: 'default' });
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [uploading, setUploading] = useState<Record<Theme, boolean>>({ light: false, dark: false });
  const [logoErrors, setLogoErrors] = useState<Record<Theme, string | null>>({ light: null, dark: null });
  // The last right color typed, which the color input keeps while the text is not one.
  const [picked, setPicked] = useState(DEFAULT_PICK);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  // Each preview request takes a number; an answer that is not the latest is dropped.
  const asked = useRef(0);

  useEffect(
    () => () => {
      clearTimeout(timer.current);
    },
    [],
  );

  // Each read (the first, and 다시 불러오기) replaces what was being changed.
  const [read, setRead] = useState<Branding | undefined>(undefined);
  if (load.data !== undefined && load.data !== read) {
    setRead(load.data);
    setBranding(load.data);
    setDraft(draftOf(load.data));
    setPreview(previewOf(load.data));
    setPicked(load.data.accent ?? DEFAULT_PICK);
    setConflict(false);
    fields.clear();
  }

  const stopPreview = () => {
    clearTimeout(timer.current);
    asked.current += 1;
  };

  const askPreview = (color: string) => {
    stopPreview();
    timer.current = setTimeout(() => {
      asked.current += 1;
      const mine = asked.current;
      void call(api.GET('/api/console/branding/accent', { params: { query: { color } } })).then((result) => {
        if (!live() || mine !== asked.current) return;
        setPreview(result.ok ? { kind: 'colors', color, colors: result.data.colors, contrast: result.data.contrast } : { kind: 'failed' });
      });
    }, PREVIEW_DELAY_MS);
  };

  const editName = (name: string) => {
    setNotice(null);
    setDraft((d) => (d === null ? d : { ...d, name }));
  };

  const editAccent = (typed: string) => {
    setNotice(null);
    fields.clear();
    setDraft((d) => (d === null ? d : { ...d, accent: typed }));
    if (HEX.test(typed)) {
      const color = typed.toUpperCase();
      setPicked(color);
      askPreview(color);
    } else {
      stopPreview();
    }
  };

  const toggleDefault = (useDefault: boolean) => {
    if (draft === null) return;
    setNotice(null);
    fields.clear();
    setDraft({ ...draft, useDefault });
    if (useDefault) {
      stopPreview();
      setPreview({ kind: 'default' });
      return;
    }
    const color = draft.accent.toUpperCase();
    if (!HEX.test(color)) return;
    if (branding !== null && color === branding.accent) {
      stopPreview();
      setPreview(previewOf(branding));
    } else {
      askPreview(color);
    }
  };

  const reload = () => {
    setConflict(false);
    setFailure(null);
    setNotice(null);
    stopPreview();
    void load.reload();
  };

  const save = async () => {
    if (branding === null || draft === null || saving) return;
    setConflict(false);
    setFailure(null);
    const displayName = cleanName(draft.name);
    const accent = draft.useDefault ? null : draft.accent.toUpperCase();
    if (accent !== null && !HEX.test(accent)) {
      setNotice(null);
      fields.setError('accent', WORDS.badFormat);
      return;
    }
    if (accent !== null && isRefused(preview, accent)) {
      setNotice(null);
      fields.setError('accent', WORDS.lowContrast);
      return;
    }
    if (displayName === branding.displayName && accent === branding.accent) {
      fields.clear();
      setNotice(WORDS.nothingChanged);
      return;
    }
    setNotice(null);
    setSaving(true);
    const result = await call(api.PUT('/api/console/branding', { body: { version: branding.version, displayName: draft.name, accent } }));
    // The server saved it: the whole app shows it, even when this screen is gone.
    if (result.ok) app.setNameAndColors({ displayName: result.data.displayName, colors: result.data.colors });
    if (!live()) return;
    setSaving(false);
    if (result.ok) {
      fields.clear();
      // The logos are the logo answers' own: a late save answer does not put an old one back.
      setBranding((b) => ({ ...result.data, logos: b?.logos ?? result.data.logos }));
      setDraft(draftOf(result.data));
      setPreview(previewOf(result.data));
      setNotice(WORDS.saved);
    } else if (result.failure.kind === 'invalid') {
      fields.applyFields(result.failure.fields, result.failure.message);
    } else if (result.failure.kind === 'rejected' && result.failure.code === 'VERSION_CONFLICT') {
      fields.clear();
      setConflict(true);
    } else {
      setFailure(result.failure);
    }
  };

  const setLogoHere = (theme: Theme, logo: BrandingLogo | null) => {
    setBranding((b) => (b === null ? b : { ...b, logos: { ...b.logos, [theme]: logo } }));
  };

  const upload = async (theme: Theme, file: File) => {
    if (uploading[theme]) return;
    setUploading((u) => ({ ...u, [theme]: true }));
    setLogoErrors((e) => ({ ...e, [theme]: null }));
    const form = new FormData();
    form.append('file', file, file.name);
    const result = await call(
      api.PUT('/api/console/branding/logos/{theme}', { params: { path: { theme } }, body: { file: file.name }, bodySerializer: () => form }),
    );
    if (result.ok) app.setLogo(theme, result.data.url);
    if (!live()) return;
    setUploading((u) => ({ ...u, [theme]: false }));
    if (result.ok) setLogoHere(theme, result.data);
    else setLogoErrors((e) => ({ ...e, [theme]: logoFailureText(result.failure) }));
  };

  const remove = async (theme: Theme) => {
    if (uploading[theme]) return;
    setLogoErrors((e) => ({ ...e, [theme]: null }));
    const result = await call(api.DELETE('/api/console/branding/logos/{theme}', { params: { path: { theme } } }));
    if (result.ok) app.setLogo(theme, null);
    if (!live()) return;
    if (result.ok) setLogoHere(theme, null);
    else setLogoErrors((e) => ({ ...e, [theme]: logoFailureText(result.failure) }));
  };

  const accentNow = draft === null || draft.useDefault ? null : draft.accent.toUpperCase();
  const formatBad = accentNow !== null && accentNow !== '' && !HEX.test(accentNow);
  const refused = accentNow !== null && isRefused(preview, accentNow);
  const accentError = draft?.useDefault ? undefined : fields.errors.accent;
  const accentHelp = accentError !== undefined ? undefined : formatBad ? WORDS.badFormat : refused ? WORDS.lowContrast : undefined;

  return (
    <div className="pd-screen pd-branding">
      <PageTitle>{t('branding.title')}</PageTitle>
      {load.status === 'failed' && <LoadFailed text={WORDS.readFailed} onRetry={reload} />}
      {branding && draft && (
        <>
          <FieldSummary summary={fields.summary} />
          {conflict && (
            <Alert
              tone="error"
              action={
                <Button variant="secondary" size="sm" onClick={reload}>
                  {TEXT.retryLoad}
                </Button>
              }
            >
              {WORDS.conflict}
            </Alert>
          )}
          <SaveFailure failure={failure} />

          <section className="pd-section" aria-labelledby="branding-name-title">
            <h2 id="branding-name-title" className="pd-section-title">
              {t('branding.displayName')}
            </h2>
            <div className="pd-branding-name">
              <TextField
                id="branding-name"
                label={t('branding.displayName')}
                value={draft.name}
                helpText={WORDS.nameHelp}
                {...errorProp(fields.errors.displayName)}
                onChange={editName}
              />
            </div>
          </section>

          <section className="pd-section" aria-labelledby="branding-accent-title">
            <h2 id="branding-accent-title" className="pd-section-title">
              {t('branding.accent')}
            </h2>
            <div className="pd-branding-accent">
              <TextField
                id="branding-accent"
                label={t('branding.accent')}
                value={draft.accent}
                placeholder="#047857"
                autoComplete="off"
                spellCheck={false}
                disabled={draft.useDefault}
                {...helpProp(draft.useDefault ? undefined : accentHelp)}
                {...errorProp(accentError)}
                onChange={editAccent}
              />
              <input
                className="pd-color-input"
                type="color"
                aria-label={t('branding.pickAccent')}
                value={picked.toLowerCase()}
                disabled={draft.useDefault}
                onChange={(e) => {
                  editAccent(e.target.value.toUpperCase());
                }}
              />
            </div>
            <Checkbox label={t('branding.useDefault')} checked={draft.useDefault} onChange={toggleDefault} />
            {preview.kind === 'failed' ? (
              <p className="pd-muted">{WORDS.previewFailed}</p>
            ) : (
              <div className="pd-brand-panes">
                {THEMES.map((theme) => (
                  <Pane key={theme} theme={theme} preview={preview} />
                ))}
              </div>
            )}
          </section>

          <section className="pd-section" aria-labelledby="branding-logo-title">
            <h2 id="branding-logo-title" className="pd-section-title">
              {t('branding.logo')}
            </h2>
            <p className="pd-muted">{WORDS.logoHelp}</p>
            <div className="pd-branding-logos">
              {THEMES.map((theme) => {
                const other: Theme = theme === 'light' ? 'dark' : 'light';
                const logo = branding.logos[theme];
                const otherLogo = branding.logos[other];
                const error = logoErrors[theme];
                return (
                  <LogoField
                    key={theme}
                    theme={theme}
                    label={LOGO_LABELS[theme]}
                    logo={logo}
                    fallback={logo === null && otherLogo !== null ? { url: otherLogo.url, from: other } : null}
                    uploading={uploading[theme]}
                    accept={LOGO_TYPES}
                    {...(error === null ? {} : { error })}
                    onUpload={(file) => {
                      void upload(theme, file);
                    }}
                    onRemove={() => {
                      void remove(theme);
                    }}
                    uploadLabel={t('branding.upload')}
                    changeLabel={t('branding.change')}
                    removeLabel={t('branding.remove')}
                  />
                );
              })}
            </div>
          </section>

          {notice !== null && <Alert tone={notice === WORDS.saved ? 'success' : 'info'}>{notice}</Alert>}
          <div className="pd-form-actions">
            <Button
              pending={saving}
              pendingLabel={TEXT.sending}
              onClick={() => {
                void save();
              }}
            >
              {t('branding.save')}
            </Button>
          </div>
        </>
      )}
    </div>
  );
}
