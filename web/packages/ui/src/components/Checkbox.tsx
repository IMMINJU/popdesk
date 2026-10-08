import { useId, type Ref } from 'react';

export interface CheckboxProps {
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  /** Line under the label, e.g. "같은 회사 동료에게 보이지 않습니다". */
  description?: string;
  id?: string;
  name?: string;
  value?: string;
  disabled?: boolean;
  ref?: Ref<HTMLInputElement>;
}

/** Drawn checkbox, 18~20px, border-strong 1.5px, accent when on (tokens.md 9절). */
export function Checkbox({ ref, ...props }: CheckboxProps) {
  const autoId = useId();
  const id = props.id ?? autoId;
  return (
    <div data-pd="Checkbox">
      <input
        ref={ref}
        id={id}
        type="checkbox"
        name={props.name}
        value={props.value}
        checked={props.checked}
        onChange={(e) => {
          props.onChange(e.target.checked);
        }}
        disabled={props.disabled}
      />
      <label htmlFor={id}>{props.label}</label>
      {props.description && <p>{props.description}</p>}
    </div>
  );
}
