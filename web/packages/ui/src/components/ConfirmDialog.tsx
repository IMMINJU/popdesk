import { useRef, type ReactNode } from 'react';
import { Button } from './Button';
import { Dialog } from './Dialog';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: ReactNode;
  confirmLabel: string;
  cancelLabel: string;
  onConfirm: () => void;
  onCancel: () => void;
  /** `danger` makes the confirm button a 위험 button (지우기, 배정 빼기). Default `default`. */
  tone?: 'default' | 'danger';
  /** The confirm button is pending. */
  pending?: boolean;
  /** Text on the confirm button while pending, e.g. "지우는 중…". */
  pendingLabel?: string;
}

/**
 * Question with 확인·취소 on top of Dialog (`alertdialog`). Esc and the close
 * button are 취소. A danger question starts on 취소 so Enter does not destroy.
 */
export function ConfirmDialog(props: ConfirmDialogProps) {
  const cancel = useRef<HTMLButtonElement>(null);
  const confirm = useRef<HTMLButtonElement>(null);
  return (
    <Dialog
      open={props.open}
      role="alertdialog"
      title={props.title}
      onClose={props.onCancel}
      closeLabel={props.cancelLabel}
      pending={props.pending ?? false}
      initialFocus={props.tone === 'danger' ? cancel : confirm}
      footer={
        <>
          <Button ref={cancel} variant="secondary" onClick={props.onCancel}>
            {props.cancelLabel}
          </Button>
          <Button
            ref={confirm}
            variant={props.tone === 'danger' ? 'danger' : 'primary'}
            pending={props.pending ?? false}
            {...(props.pendingLabel === undefined ? {} : { pendingLabel: props.pendingLabel })}
            onClick={props.onConfirm}
          >
            {props.confirmLabel}
          </Button>
        </>
      }
    >
      <div className="pd-confirm-message">{props.message}</div>
    </Dialog>
  );
}
