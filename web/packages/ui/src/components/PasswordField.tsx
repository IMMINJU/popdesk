import { useId, type Ref } from 'react';

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
  return (
    <div data-pd="PasswordField">
      <label htmlFor={id}>{props.label}</label>
      <input
        ref={ref}
        id={id}
        name={props.name}
        type={props.revealed ? 'text' : 'password'}
        value={props.value}
        onChange={(e) => {
          props.onChange(e.target.value);
        }}
        autoComplete={props.autoComplete}
        disabled={props.disabled}
        readOnly={props.readOnly}
        required={props.required}
        aria-invalid={props.error ? true : undefined}
      />
      {props.helpText && <p>{props.helpText}</p>}
      {props.error && <p>{props.error}</p>}
    </div>
  );
}
