import { useId, useState, type Ref } from 'react';
import { EyeIcon, EyeOffIcon } from '../icons';
import { describedBy } from '../aria';
import { FieldError, FieldHelp } from './fieldParts';
import { IconButton } from './IconButton';

export interface PasswordFieldProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
  /** `current-password` on login, `new-password` on invite accept. */
  autoComplete: 'current-password' | 'new-password';
  id?: string;
  name?: string;
  /** 보이기·숨기기. Controlled when given; otherwise the field keeps it. */
  revealed?: boolean;
  onRevealedChange?: (revealed: boolean) => void;
  /** Toggle button name when hidden, e.g. "비밀번호 보이기". */
  showLabel?: string;
  /** Toggle button name when shown, e.g. "비밀번호 숨기기". */
  hideLabel?: string;
  /** 도움말, e.g. the password rules on P-2 (joined with `aria-describedby`). */
  helpText?: string;
  error?: string;
  disabled?: boolean;
  readOnly?: boolean;
  required?: boolean;
  ref?: Ref<HTMLInputElement>;
}

export function PasswordField({ ref, ...props }: PasswordFieldProps) {
  const autoId = useId();
  const id = props.id ?? autoId;
  const helpId = `${id}-help`;
  const errorId = `${id}-error`;
  const [ownRevealed, setOwnRevealed] = useState(false);
  const revealed = props.revealed ?? ownRevealed;
  const toggle = () => {
    const next = !revealed;
    if (props.revealed === undefined) setOwnRevealed(next);
    props.onRevealedChange?.(next);
  };
  return (
    <div className="pd-field" data-pd="PasswordField" data-invalid={props.error ? 'true' : undefined}>
      <label className="pd-label" htmlFor={id}>
        {props.label}
      </label>
      <div className="pd-input-wrap" data-has-end="true">
        <input
          ref={ref}
          id={id}
          className="pd-input"
          name={props.name}
          type={revealed ? 'text' : 'password'}
          value={props.value}
          onChange={(e) => {
            props.onChange(e.target.value);
          }}
          autoComplete={props.autoComplete}
          autoCapitalize="none"
          spellCheck={false}
          disabled={props.disabled}
          readOnly={props.readOnly}
          required={props.required}
          aria-invalid={props.error ? true : undefined}
          aria-describedby={describedBy(props.helpText && helpId, props.error && errorId)}
        />
        <span className="pd-input-end">
          <IconButton
            label={revealed ? (props.hideLabel ?? 'Hide password') : (props.showLabel ?? 'Show password')}
            icon={revealed ? <EyeOffIcon /> : <EyeIcon />}
            size="sm"
            tooltip={false}
            disabled={props.disabled ?? false}
            onClick={toggle}
          />
        </span>
      </div>
      <FieldHelp id={helpId} text={props.helpText} />
      <FieldError id={errorId} text={props.error} />
    </div>
  );
}
