export interface PeoplePickerOption {
  /** Account ID as text, like the values of `CheckboxGroup`. */
  id: string;
  /** The line of the box, e.g. "김상담 (상담원)", "박고객 (올린 고객)". No photo. */
  label: string;
}

export interface PeoplePickerProps {
  /** Names the boxes when opened, e.g. "알릴 사람". */
  label: string;
  /** The text of the button that opens and folds it, e.g. "알릴 사람 고르기". */
  toggleLabel: string;
  /** Who can be picked. Empty with `status` `ready`: `emptyText` instead of boxes. */
  candidates: readonly PeoplePickerOption[];
  /** The picked IDs. */
  value: readonly string[];
  onChange: (value: string[]) => void;
  /** Shown beside the button while folded, e.g. "알릴 사람: 김상담, 이관리" or "알릴 사람 없음". */
  summary: string;
  /** Controlled when given. Default: folded. */
  open?: boolean;
  /** Called when the button opens or folds it, e.g. to load the candidates at the first opening. */
  onOpenChange?: (open: boolean) => void;
  /** 상한: with this many picked the other boxes are held and `maxText` shows. Default: no limit. */
  max?: number;
  /** "10명까지 고를 수 있습니다". */
  maxText?: string;
  /** The candidates: `loading` shows `loadingText`, `failed` shows `failedText` and the retry button. Default `ready`. */
  status?: 'ready' | 'loading' | 'failed';
  /** "불러오는 중…". */
  loadingText?: string;
  /** "후보를 불러오지 못했습니다". */
  failedText?: string;
  /** "다시 불러오기". */
  retryLabel?: string;
  onRetry?: () => void;
  /** "알릴 수 있는 사람이 없습니다". */
  emptyText?: string;
  /** One line under it, e.g. "내부 메모에서는 고객에게 알릴 수 없어 박고객 님을 뺐습니다." */
  notice?: string;
  /** `id` of the button; the opened part gets `{id}-list`. */
  id?: string;
}

/**
 * Picks several people out of a short list (slice 4 screens.md C-4 "알릴 사람"): one folded line,
 * a button with `aria-expanded` and the summary of who is picked, and under it the candidates as a
 * `CheckboxGroup` named `label` once opened. Not a `radiogroup`, no photos.
 *
 * Contract shell: only the folded line and `notice` are drawn. Opening, the boxes, the states of
 * the candidates, the limit and the `onChange` call come later (C-9).
 */
export function PeoplePicker(props: PeoplePickerProps) {
  return (
    <div className="pd-people-picker" data-pd="PeoplePicker">
      <div className="pd-people-picker-line">
        <button id={props.id} type="button" className="pd-button" data-variant="secondary" data-size="sm" aria-expanded={false}>
          <span className="pd-button-text">{props.toggleLabel}</span>
        </button>
        <span className="pd-people-picker-summary">{props.summary}</span>
      </div>
      {props.notice !== undefined && <p className="pd-people-picker-notice">{props.notice}</p>}
    </div>
  );
}
