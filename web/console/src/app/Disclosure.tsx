// A folded part with the button that opens it (3-issues screens.md C-4: 이력,
// "전 내용 보기", "지금 내용 보기"). What is folded is not drawn.
import { ChevronDownIcon } from '@popdesk/ui';
import { useId, useState, type ReactNode } from 'react';

export interface DisclosureProps {
  /** The text of the button, e.g. "이력". */
  label: string;
  children: ReactNode;
  /** `section`: the button is the heading of a part of the screen. Default `inline`: a small one inside a line. */
  kind?: 'section' | 'inline';
  /** Called when it opens or closes, e.g. to load what it shows. */
  onToggle?: (open: boolean) => void;
}

export function Disclosure(props: DisclosureProps) {
  const [open, setOpen] = useState(false);
  const id = useId();
  const button = (
    <button
      type="button"
      className="pd-disclosure-button"
      aria-expanded={open}
      aria-controls={open ? id : undefined}
      onClick={() => {
        setOpen(!open);
        props.onToggle?.(!open);
      }}
    >
      <ChevronDownIcon size={14} />
      <span>{props.label}</span>
    </button>
  );
  return (
    <div className="pd-disclosure" data-kind={props.kind ?? 'inline'} data-open={open ? 'true' : undefined}>
      {props.kind === 'section' ? <h2 className="pd-section-title">{button}</h2> : button}
      {open && (
        <div id={id} className="pd-disclosure-body">
          {props.children}
        </div>
      )}
    </div>
  );
}
