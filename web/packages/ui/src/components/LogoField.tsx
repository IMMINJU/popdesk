import { useEffect, useId, useRef, type ChangeEvent } from 'react';
import { Spinner } from '../icons';
import { Button } from './Button';
import { FieldError, FieldHelp } from './fieldParts';

export interface LogoFieldProps {
  /** The theme this field holds the logo of. The preview sits on that theme's `bg` (`#FFFFFF`, `#191817`). */
  theme: 'light' | 'dark';
  /** e.g. "밝은 테마 로고". */
  label: string;
  /** This theme's logo, with the size the server stored. `null`: none. */
  logo: { url: string; width: number; height: number } | null;
  /**
   * Without a logo of its own: the other theme's logo, which this theme shows too. It is drawn faded
   * (opacity 0.5) with "없음. 밝은 테마 로고를 같이 씁니다", so an admin sees a logo that does not
   * read on this background. Absent or `null`: neither theme has one.
   */
  fallback?: { url: string; from: 'light' | 'dark' } | null;
  /** 올리는 중: `aria-busy` on the field; the file input is blocked with `aria-disabled`, not `disabled`, so focus stays. */
  uploading?: boolean;
  /** Reason text of the last failed upload. The logo before it stays. */
  error?: string;
  /** Formats, size and what is done with the image. */
  helpText?: string;
  /** `accept` of the file input, e.g. "image/png,image/jpeg,image/webp". */
  accept?: string;
  /** The file picked. The screen uploads it at once (no save button). */
  onUpload: (file: File) => void;
  /** 지우기, not asked twice (the logo can be uploaded again). */
  onRemove: () => void;
  /** Without a logo, e.g. "올리기". */
  uploadLabel: string;
  /** With a logo, e.g. "바꾸기". Focus goes here after an upload. */
  changeLabel: string;
  /** e.g. "지우기". Shown only with a logo; focus goes to the upload label after it. */
  removeLabel: string;
}

const THEME_WORDS: Readonly<Record<'light' | 'dark', string>> = { light: '밝은', dark: '어두운' };

/**
 * One theme's logo on C-14 (slice 9 screens.md 2.2): 없음, 다른 테마 것을 씀, 있음, 올리는 중, 실패.
 * "올리기"·"바꾸기" is the `label` of a hidden file input, as in `PhotoField`. The preview sits on
 * the theme's own `bg` whatever the page theme is. After an upload focus goes to the file input
 * (named "바꾸기"), after a removal to the same input (named "올리기"), when focus was in the field
 * or had fallen to `body`.
 */
export function LogoField(props: LogoFieldProps) {
  const inputId = useId();
  const helpId = `${inputId}-help`;
  const errorId = `${inputId}-error`;
  const nameId = `${inputId}-name`;
  const box = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const uploading = props.uploading ?? false;
  const shown = props.logo?.url ?? null;
  const before = useRef(shown);
  useEffect(() => {
    if (before.current === shown) return;
    before.current = shown;
    const active = document.activeElement;
    if (active === null || active === document.body || box.current?.contains(active)) input.current?.focus();
  }, [shown]);
  const describedBy = [props.helpText ? helpId : null, props.error ? errorId : null].filter(Boolean).join(' ');
  const pick = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    // Cleared so that picking the same file again, after a failure, is a change.
    e.target.value = '';
    if (file && !uploading) props.onUpload(file);
  };
  const fallback = props.logo ? null : (props.fallback ?? null);
  return (
    <div
      ref={box}
      className="pd-logo-field"
      data-pd="LogoField"
      data-logo-theme={props.theme}
      role="group"
      aria-labelledby={nameId}
      aria-busy={uploading ? true : undefined}
    >
      <p id={nameId} className="pd-label">
        {props.label}
      </p>
      <div className="pd-logo-preview" data-logo-theme={props.theme} data-empty={props.logo || fallback ? undefined : 'true'}>
        {props.logo ? (
          <img className="pd-logo-image" src={props.logo.url} alt="" />
        ) : fallback ? (
          <img className="pd-logo-image" data-fallback="true" src={fallback.url} alt="" />
        ) : (
          <span className="pd-logo-none">로고 없음</span>
        )}
      </div>
      {props.logo && (
        <p className="pd-logo-size">
          {props.logo.width} × {props.logo.height} px
        </p>
      )}
      {fallback && <p className="pd-logo-size">없음. {THEME_WORDS[fallback.from]} 테마 로고를 같이 씁니다</p>}
      <div className="pd-logo-actions">
        <input
          ref={input}
          id={inputId}
          className="pd-upload-input"
          type="file"
          accept={props.accept}
          aria-disabled={uploading ? true : undefined}
          aria-describedby={describedBy || undefined}
          onClick={(e) => {
            // Blocked, not disabled: focus stays here while the upload runs.
            if (uploading) e.preventDefault();
          }}
          onChange={pick}
        />
        <label className="pd-upload-label" htmlFor={inputId}>
          {props.logo ? props.changeLabel : props.uploadLabel}
        </label>
        {props.logo && (
          <Button variant="quiet" size="sm" blocked={uploading} onClick={props.onRemove}>
            {props.removeLabel}
          </Button>
        )}
        {uploading && (
          <span className="pd-logo-busy">
            <Spinner />
            올리는 중…
          </span>
        )}
      </div>
      <FieldHelp id={helpId} text={props.helpText} />
      <FieldError id={errorId} text={props.error} />
    </div>
  );
}
