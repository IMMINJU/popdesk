export interface DueDatePickerProps {
  /** Names the date input when opened, e.g. "마감일". */
  label: string;
  /** The text of the button that opens and closes it, e.g. "바꾸기". */
  toggleLabel: string;
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
 * Contract shell: only the folded button is drawn. Opening, the input, the buttons, `busy`,
 * `error` and closing on Escape or outside come later (C-9).
 */
export function DueDatePicker(props: DueDatePickerProps) {
  return (
    <div className="pd-due-date-picker" data-pd="DueDatePicker">
      <button id={props.id} type="button" className="pd-button" data-variant="secondary" data-size="sm" aria-expanded={false}>
        <span className="pd-button-text">{props.toggleLabel}</span>
      </button>
    </div>
  );
}
