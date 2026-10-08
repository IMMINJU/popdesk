import type { ReactNode } from 'react';
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
}

export function ConfirmDialog(props: ConfirmDialogProps) {
  return (
    <Dialog
      open={props.open}
      title={props.title}
      onClose={props.onCancel}
      closeLabel={props.cancelLabel}
      footer={
        <>
          <Button variant="secondary" onClick={props.onCancel}>
            {props.cancelLabel}
          </Button>
          <Button
            variant={props.tone === 'danger' ? 'danger' : 'primary'}
            pending={props.pending ?? false}
            onClick={props.onConfirm}
          >
            {props.confirmLabel}
          </Button>
        </>
      }
    >
      {props.message}
    </Dialog>
  );
}
