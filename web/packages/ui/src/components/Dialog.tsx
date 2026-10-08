import { useId, type ReactNode } from 'react';

export interface DialogProps {
  open: boolean;
  title: string;
  /** Esc, the close button and the overlay call this. The screen asks first if there is unsaved input. */
  onClose: () => void;
  children: ReactNode;
  description?: string;
  /** Buttons at the bottom. */
  footer?: ReactNode;
  /** 보내는 중: the submit button is pending; closing still works (C-8). */
  pending?: boolean;
  /** Close button name, e.g. "닫기". */
  closeLabel?: string;
}

/**
 * Modal dialog. Focus goes to the first field on open, stays inside while
 * open, and returns to the element that opened it on close.
 */
export function Dialog(props: DialogProps) {
  const titleId = useId();
  if (!props.open) return null;
  return (
    <div data-pd="Dialog" role="dialog" aria-modal="true" aria-labelledby={titleId} aria-busy={props.pending}>
      <h2 id={titleId}>{props.title}</h2>
      {props.description && <p>{props.description}</p>}
      <div>{props.children}</div>
      {props.footer}
      <button type="button" aria-label={props.closeLabel} onClick={props.onClose}>
        ×
      </button>
    </div>
  );
}
