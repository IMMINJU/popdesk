import { useId, type Ref } from 'react';
import { CheckIcon } from '../icons';

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
  const descriptionId = `${id}-description`;
  return (
    <div className="pd-check" data-pd="Checkbox">
      <span className="pd-check-box">
        <input
          ref={ref}
          id={id}
          type="checkbox"
          className="pd-check-input"
          name={props.name}
          value={props.value}
          checked={props.checked}
          onChange={(e) => {
            props.onChange(e.target.checked);
          }}
          disabled={props.disabled}
          aria-describedby={props.description ? descriptionId : undefined}
        />
        <span className="pd-check-mark" aria-hidden="true">
          <CheckIcon size={14} />
        </span>
      </span>
      <span className="pd-check-text">
        <label className="pd-check-label" htmlFor={id}>
          {props.label}
        </label>
        {props.description && (
          <span id={descriptionId} className="pd-field-help">
            {props.description}
          </span>
        )}
      </span>
    </div>
  );
}
