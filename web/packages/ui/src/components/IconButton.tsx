import type { MouseEvent, MouseEventHandler, ReactNode, Ref } from 'react';
import { Spinner } from '../icons';
import type { ButtonSize, ButtonVariant } from './Button';

export interface IconButtonProps {
  /** Accessible name (`aria-label`). Required: the button has no text. */
  label: string;
  /** Icon from the icon assets. Decorative; the name comes from `label`. */
  icon: ReactNode;
  /** Default `quiet`. */
  variant?: ButtonVariant;
  /** Default `md`. */
  size?: ButtonSize;
  disabled?: boolean;
  /** Same as Button: spinner, `aria-disabled`, clicks ignored. */
  pending?: boolean;
  /** For toggles such as 비밀번호 보이기. */
  pressed?: boolean;
  /** For a button that opens and closes a part, e.g. the console side menu. */
  expanded?: boolean;
  /** `id` of the part `expanded` controls. */
  controls?: string;
  /** Shows `label` as a tooltip on hover and focus. Default true. */
  tooltip?: boolean;
  onClick?: MouseEventHandler<HTMLButtonElement>;
  ref?: Ref<HTMLButtonElement>;
}

export function IconButton({ ref, ...props }: IconButtonProps) {
  const onClick = (e: MouseEvent<HTMLButtonElement>) => {
    if (props.pending) {
      e.preventDefault();
      return;
    }
    props.onClick?.(e);
  };
  return (
    <button
      ref={ref}
      type="button"
      className="pd-icon-button"
      data-pd="IconButton"
      aria-label={props.label}
      title={props.tooltip === false ? undefined : props.label}
      aria-pressed={props.pressed}
      aria-expanded={props.expanded}
      aria-controls={props.controls}
      aria-disabled={props.pending ? true : undefined}
      data-variant={props.variant ?? 'quiet'}
      data-size={props.size ?? 'md'}
      disabled={props.disabled}
      onClick={onClick}
    >
      {props.pending ? <Spinner /> : <span aria-hidden="true">{props.icon}</span>}
    </button>
  );
}
