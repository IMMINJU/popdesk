// A row's actions behind one button (screens.md C-10 줄 메뉴). The list has a
// fixed position, so the table's sideways scroll does not cut it off.
import { MoreIcon } from '@popdesk/ui';
import { useEffect, useId, useLayoutEffect, useRef, useState, type CSSProperties, type KeyboardEvent } from 'react';

export interface RowMenuItem {
  label: string;
  onSelect: () => void;
  /** `danger` for an action that takes something away (정지, 초대 취소). */
  tone?: 'danger';
}

const ITEM_HEIGHT = 36;
const GAP = 4;

export function RowMenu(props: { label: string; items: readonly RowMenuItem[] }) {
  const id = useId();
  const [place, setPlace] = useState<CSSProperties | null>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const open = place !== null;

  const close = (refocus: boolean) => {
    setPlace(null);
    if (refocus) trigger.current?.focus();
  };

  const toggle = () => {
    if (open || !trigger.current) {
      setPlace(null);
      return;
    }
    const rect = trigger.current.getBoundingClientRect();
    const right = Math.max(0, window.innerWidth - rect.right);
    // Opens upward when the list would run past the bottom of the window.
    const below = rect.bottom + GAP + props.items.length * ITEM_HEIGHT + 2 * GAP <= window.innerHeight;
    setPlace(below ? { top: rect.bottom + GAP, right } : { bottom: window.innerHeight - rect.top + GAP, right });
  };

  // Layout effect: focus is on the first item in the commit that shows the list.
  useLayoutEffect(() => {
    if (open) list.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
  }, [open]);

  useEffect(() => {
    if (!open) return undefined;
    const outside = (e: MouseEvent) => {
      const target = e.target as Node;
      if (!list.current?.contains(target) && !trigger.current?.contains(target)) setPlace(null);
    };
    // The list does not follow its row, so a scroll or a resize closes it.
    const moved = () => {
      setPlace(null);
    };
    document.addEventListener('mousedown', outside);
    window.addEventListener('scroll', moved, true);
    window.addEventListener('resize', moved);
    return () => {
      document.removeEventListener('mousedown', outside);
      window.removeEventListener('scroll', moved, true);
      window.removeEventListener('resize', moved);
    };
  }, [open]);

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const items = Array.from(list.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);
    const at = items.indexOf(document.activeElement as HTMLElement);
    let next: HTMLElement | undefined;
    if (e.key === 'Escape') {
      e.stopPropagation();
      close(true);
      return;
    }
    if (e.key === 'Tab') {
      close(true);
      return;
    }
    if (e.key === 'ArrowDown') next = items[(at + 1) % items.length];
    else if (e.key === 'ArrowUp') next = items[(at - 1 + items.length) % items.length];
    else if (e.key === 'Home') next = items[0];
    else if (e.key === 'End') next = items[items.length - 1];
    if (!next) return;
    e.preventDefault();
    next.focus();
  };

  return (
    <span className="pd-row-menu">
      <button
        ref={trigger}
        type="button"
        className="pd-icon-button"
        data-size="sm"
        aria-label={props.label}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        onClick={toggle}
      >
        <MoreIcon />
      </button>
      {place !== null && (
        <div ref={list} id={id} className="pd-row-menu-list" role="menu" aria-label={props.label} style={place} onKeyDown={onKeyDown}>
          {props.items.map((item) => (
            <button
              key={item.label}
              type="button"
              role="menuitem"
              className="pd-row-menu-item"
              data-tone={item.tone}
              tabIndex={-1}
              onClick={() => {
                // Focus goes back first, so a dialog the action opens returns it to the row.
                close(true);
                item.onSelect();
              }}
            >
              {item.label}
            </button>
          ))}
        </div>
      )}
    </span>
  );
}
