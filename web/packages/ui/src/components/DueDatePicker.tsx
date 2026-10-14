import { useId, useState } from 'react';
import { Button } from './Button';
import { usePopover } from './popover';
import { TextField } from './TextField';

export interface DueDatePickerProps {
  /** Names the date input when opened, e.g. "마감일". */
  label: string;
  /** The text of the button that opens and closes it, e.g. "바꾸기". */
  toggleLabel: string;
  /**
   * Accessible name of that button when the visible text alone is not enough, e.g. "라벨 바꾸기"
   * beside the visible "바꾸기" (it must contain the visible text). Default: the visible text.
   */
  toggleName?: string;
  /** The issue's due date, `YYYY-MM-DD`, or `null` for none. The input starts from it. */
  value: string | null;
  /**
   * The earliest day the browser lets one pick, `YYYY-MM-DD` (today in the browser). A hint only;
   * the server decides and its 400 comes back as `error`.
   */
  min?: string;
  /** "정하기": called with the picked `YYYY-MM-DD`. */
  onSubmit: (date: string) => void;
  /** "지우기", shown only while `value` is not `null`. Not given: no clear button. */
  onClear?: () => void;
  /** A request is on its way: the input and the buttons are held. */
  busy?: boolean;
  /** Under the input, e.g. "오늘이나 그 뒤의 날짜를 고르세요." */
  error?: string;
  /** "정하기". */
  submitLabel: string;
  /** "지우기". */
  clearLabel: string;
  /** Controlled when given. Default: closed. */
  open?: boolean;
  /**
   * Called when the button, Escape or a press outside opens or closes it. On closing, focus goes
   * back to the button.
   */
  onOpenChange?: (open: boolean) => void;
  /** `id` of the button; the opened part gets `{id}-panel`, the input `{id}-date`. */
  id?: string;
}

/**
 * Sets or clears an issue's due date (slice 6a screens.md 1절 마감일 고르기): a button with
 * `aria-expanded`, and under it once opened a `TextField` of `type="date"` with `min`, "정하기",
 * and "지우기" while there is a date. Opens and closes like `LabelPicker`.
 *
 * The form has `noValidate`: a day before `min` is sent as it is and the server's 400 answers it.
 * While `busy` the buttons are `aria-disabled`, so focus stays on them.
 */
export function DueDatePicker(props: DueDatePickerProps) {
  const autoId = useId();
  const id = props.id ?? autoId;
  const panelId = `${id}-panel`;
  const { root, toggle, open, setOpen } = usePopover(props);
  return (
    <div ref={root} className="pd-due-date-picker" data-pd="DueDatePicker">
      <button
        ref={toggle}
        id={id}
        type="button"
        className="pd-button"
        data-variant="secondary"
        data-size="sm"
        aria-label={props.toggleName}
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        onClick={() => {
          setOpen(!open);
        }}
      >
        <span className="pd-button-text">{props.toggleLabel}</span>
      </button>
      {open && <DueDatePanel {...props} id={id} panelId={panelId} />}
    </div>
  );
}

function DueDatePanel(props: DueDatePickerProps & { id: string; panelId: string }) {
  // The input starts from the issue's date each time the part opens.
  const [date, setDate] = useState(props.value ?? '');
  const busy = props.busy === true;
  return (
    <form
      id={props.panelId}
      className="pd-popover pd-due-date-panel"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        if (!busy && date !== '') props.onSubmit(date);
      }}
    >
      <TextField id={`${props.id}-date`} type="date" label={props.label} value={date} onChange={setDate} {...(props.min === undefined ? {} : { min: props.min })} disabled={busy} {...(props.error === undefined ? {} : { error: props.error })} />
      <div className="pd-popover-actions">
        <Button type="submit" variant="primary" size="sm" blocked={busy}>
          {props.submitLabel}
        </Button>
        {props.value !== null && props.onClear !== undefined && (
          <Button
            variant="secondary"
            size="sm"
            blocked={busy}
            onClick={() => {
              props.onClear?.();
            }}
          >
            {props.clearLabel}
          </Button>
        )}
      </div>
    </form>
  );
}
