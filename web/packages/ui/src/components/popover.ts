// Opening and closing of a button with a part under it (LabelPicker, DueDatePicker; slice 6a
// screens.md 1절): the button, Escape and a press outside open or close it, and closing gives focus
// back to the button.
import { useEffect, useRef, useState } from 'react';

export interface PopoverOptions {
  /** Controlled when given. */
  open?: boolean | undefined;
  onOpenChange?: ((open: boolean) => void) | undefined;
}

export function usePopover(options: PopoverOptions) {
  const root = useRef<HTMLDivElement>(null);
  const toggle = useRef<HTMLButtonElement>(null);
  const [own, setOwn] = useState(false);
  const open = options.open ?? own;
  const { onOpenChange } = options;

  const setOpen = (next: boolean) => {
    setOwn(next);
    onOpenChange?.(next);
  };

  useEffect(() => {
    if (!open) return undefined;
    // Focus goes back to the button only when it was in the part or nowhere (a held row or a press
    // on plain text drops it on the body); a press on another control keeps it there.
    const close = () => {
      const at = document.activeElement;
      if (at === null || at === document.body || root.current?.contains(at)) toggle.current?.focus();
      setOwn(false);
      onOpenChange?.(false);
    };
    // On the document, not the part: a row held while its request runs has lost focus, and Escape
    // must still close (S6A-AC-86).
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      e.stopPropagation();
      close();
    };
    const onClick = (e: MouseEvent) => {
      if (!root.current?.contains(e.target as Node)) close();
    };
    document.addEventListener('keydown', onKey);
    document.addEventListener('click', onClick);
    return () => {
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('click', onClick);
    };
  }, [open, onOpenChange]);

  return { root, toggle, open, setOpen };
}
