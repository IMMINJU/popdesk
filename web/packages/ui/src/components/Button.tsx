import type { MouseEvent, MouseEventHandler, ReactNode, Ref } from 'react';
import { Spinner } from '../icons';

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
  /**
   * Held back without a spinner, e.g. while a 429 countdown runs: `aria-disabled="true"`,
   * clicks ignored, the text stays, focus stays.
   */
  blocked?: boolean;
  /** Leading decorative icon. */
  icon?: ReactNode;
  /** Joins a description, e.g. the file name a row button acts on. */
  describedBy?: string;
  /** Submits this form from outside it (a dialog footer). */
  form?: string;
  onClick?: MouseEventHandler<HTMLButtonElement>;
  ref?: Ref<HTMLButtonElement>;
}

export function Button({ ref, ...props }: ButtonProps) {
  const held = props.pending === true || props.blocked === true;
  const onClick = (e: MouseEvent<HTMLButtonElement>) => {
    if (held) {
      // Also stops a submit button from sending its form again.
      e.preventDefault();
      return;
    }
    props.onClick?.(e);
  };
  return (
    <button
      ref={ref}
      type={props.type ?? 'button'}
      form={props.form}
      className="pd-button"
      data-pd="Button"
      data-variant={props.variant ?? 'primary'}
      data-size={props.size ?? 'md'}
      data-pending={props.pending ? 'true' : undefined}
      disabled={props.disabled}
      aria-disabled={held ? true : undefined}
      aria-describedby={props.describedBy}
      onClick={onClick}
    >
      {props.pending ? <Spinner /> : props.icon !== undefined && <span aria-hidden="true">{props.icon}</span>}
      <span className="pd-button-text">{props.pending ? (props.pendingLabel ?? props.children) : props.children}</span>
    </button>
  );
}
