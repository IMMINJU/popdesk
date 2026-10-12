// A folded part with the button that opens it (3-issues screens.md P-5 고치기:
// "지금 내용 보기"). What is folded is not drawn.
import { ChevronDownIcon } from '@popdesk/ui';
import { useId, useState, type ReactNode } from 'react';

export interface DisclosureProps {
  /** The text of the button, e.g. "지금 내용 보기". */
  label: string;
  children: ReactNode;
}

export function Disclosure(props: DisclosureProps) {
  const [open, setOpen] = useState(false);
  const id = useId();
  return (
    <div className="pd-disclosure" data-open={open ? 'true' : undefined}>
      <button
        type="button"
        className="pd-disclosure-button"
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        onClick={() => {
          setOpen(!open);
        }}
      >
        <ChevronDownIcon size={14} />
        <span>{props.label}</span>
      </button>
      {open && (
        <div id={id} className="pd-disclosure-body">
          {props.children}
        </div>
      )}
    </div>
  );
}
