import { useEffect, useEffectEvent, useId, useLayoutEffect, useRef, type KeyboardEvent, type ReactNode, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { CloseIcon } from '../icons';
import { IconButton } from './IconButton';

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
  /** `alertdialog` for questions that need an answer (ConfirmDialog). Default `dialog`. */
  role?: 'dialog' | 'alertdialog';
  /** Element to focus on open. Default: the first field, else the first footer button. */
  initialFocus?: RefObject<HTMLElement | null>;
  /** Default `md` (480px). `lg` is 640px. */
  size?: 'md' | 'lg';
  /** Default true: a close button in the head. A question without it closes with its own answer buttons and Esc. */
  closable?: boolean;
}

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** Open dialogs, newest last. Keys that land outside every dialog go to the newest. */
const openDialogs: symbol[] = [];

function focusables(root: HTMLElement): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((el) => el.closest('[hidden], [inert]') === null);
}

/**
 * Modal dialog. Focus goes to the first field on open, stays inside while
 * open, and returns to the element that opened it on close.
 */
export function Dialog(props: DialogProps) {
  const id = useId();
  const titleId = `${id}-title`;
  const descriptionId = `${id}-description`;
  const box = useRef<HTMLDivElement>(null);
  const body = useRef<HTMLDivElement>(null);
  const footer = useRef<HTMLDivElement>(null);

  const focusFirst = useEffectEvent(() => {
    const target =
      props.initialFocus?.current ??
      body.current?.querySelector<HTMLElement>('input:not([type="hidden"]):not([disabled]), select:not([disabled]), textarea:not([disabled])') ??
      (footer.current ? focusables(footer.current)[0] : undefined) ??
      (box.current ? focusables(box.current)[0] : undefined);
    target?.focus();
  });

  const self = useRef(Symbol('dialog'));

  // Layout effect: focus is inside before anything else can take it.
  useLayoutEffect(() => {
    if (!props.open) return undefined;
    const me = self.current;
    openDialogs.push(me);
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    focusFirst();
    return () => {
      openDialogs.splice(openDialogs.indexOf(me), 1);
      // Back to the control that opened it, when it is still on the page.
      if (opener?.isConnected) opener.focus();
    };
  }, [props.open]);

  // When the focused control goes away (a form replaced by its result), focus
  // falls to the page. Esc and Tab from there still belong to the newest dialog.
  const onStrayKey = useEffectEvent((e: globalThis.KeyboardEvent) => {
    if (openDialogs[openDialogs.length - 1] !== self.current || !box.current) return;
    if (box.current.contains(document.activeElement)) return;
    if (e.key === 'Escape') {
      e.preventDefault();
      props.onClose();
    } else if (e.key === 'Tab') {
      e.preventDefault();
      focusFirst();
    }
  });
  useEffect(() => {
    if (!props.open) return undefined;
    const listener = (e: globalThis.KeyboardEvent) => {
      onStrayKey(e);
    };
    document.addEventListener('keydown', listener);
    return () => {
      document.removeEventListener('keydown', listener);
    };
  }, [props.open]);

  if (!props.open) return null;

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'Escape') {
      e.stopPropagation();
      e.preventDefault();
      props.onClose();
      return;
    }
    if (e.key !== 'Tab' || !box.current) return;
    const items = focusables(box.current);
    const first = items[0];
    const last = items[items.length - 1];
    if (!first || !last) {
      e.preventDefault();
      return;
    }
    const active = document.activeElement;
    if (e.shiftKey && (active === first || !box.current.contains(active))) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && (active === last || !box.current.contains(active))) {
      e.preventDefault();
      first.focus();
    }
  };

  return createPortal(
    <div className="pd-dialog-layer" data-pd="DialogLayer">
      <div
        className="pd-dialog-overlay"
        aria-hidden="true"
        onClick={() => {
          props.onClose();
        }}
      />
      <div
        ref={box}
        className="pd-dialog"
        data-pd="Dialog"
        data-size={props.size ?? 'md'}
        role={props.role ?? 'dialog'}
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={props.description ? descriptionId : undefined}
        aria-busy={props.pending ? true : undefined}
        onKeyDown={onKeyDown}
      >
        <div className="pd-dialog-head">
          <h2 id={titleId} className="pd-dialog-title">
            {props.title}
          </h2>
          {props.closable !== false && (
            <IconButton label={props.closeLabel ?? 'Close'} icon={<CloseIcon />} size="sm" tooltip={false} onClick={props.onClose} />
          )}
        </div>
        {props.description && (
          <p id={descriptionId} className="pd-dialog-description">
            {props.description}
          </p>
        )}
        <div ref={body} className="pd-dialog-body">
          {props.children}
        </div>
        {props.footer !== undefined && (
          <div ref={footer} className="pd-dialog-footer">
            {props.footer}
          </div>
        )}
      </div>
    </div>,
    document.body,
  );
}
