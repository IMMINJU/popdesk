import type { MouseEventHandler, ReactNode, Ref } from 'react';
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
  onClick?: MouseEventHandler<HTMLButtonElement>;
  ref?: Ref<HTMLButtonElement>;
}

export function IconButton({ ref, ...props }: IconButtonProps) {
  return (
    <button
      ref={ref}
      type="button"
      aria-label={props.label}
      aria-pressed={props.pressed}
      aria-disabled={props.pending}
      data-variant={props.variant ?? 'quiet'}
      data-size={props.size ?? 'md'}
      disabled={props.disabled}
      onClick={props.onClick}
    >
      {props.icon}
    </button>
  );
}
