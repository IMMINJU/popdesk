import type { MouseEventHandler, ReactNode, Ref } from 'react';

/** 주(accent), 보조(sunken 바탕), 조용한(글자만), 위험(danger), 내부(internal-border 바탕, C-4). */
export type ButtonVariant = 'primary' | 'secondary' | 'quiet' | 'danger' | 'internal';

/** 보통, 작게. Heights come from the density tokens. */
export type ButtonSize = 'md' | 'sm';

export interface ButtonProps {
  children: ReactNode;
  /** Default `primary`. */
  variant?: ButtonVariant;
  /** Default `md`. */
  size?: ButtonSize;
  /** Default `button`. */
  type?: 'button' | 'submit' | 'reset';
  /** 막힘. While saving use `pending` instead, so focus stays on the button. */
  disabled?: boolean;
  /** 보내는 중: spinner and `pendingLabel`, `aria-disabled="true"`, clicks ignored (UX-9). */
  pending?: boolean;
  /** Text while pending, e.g. "보내는 중…". Falls back to `children`. */
  pendingLabel?: string;
  onClick?: MouseEventHandler<HTMLButtonElement>;
  ref?: Ref<HTMLButtonElement>;
}

export function Button({ ref, ...props }: ButtonProps) {
  return (
    <button
      ref={ref}
      type={props.type ?? 'button'}
      data-variant={props.variant ?? 'primary'}
      data-size={props.size ?? 'md'}
      disabled={props.disabled}
      aria-disabled={props.pending}
      onClick={props.onClick}
    >
      {props.pending ? (props.pendingLabel ?? props.children) : props.children}
    </button>
  );
}
