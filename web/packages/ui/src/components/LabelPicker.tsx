import type { ReactNode } from 'react';
import type { LabelItem } from '../types';

export interface LabelPickerProps {
  /** Names the boxes when opened, e.g. "라벨". */
  label: string;
  /** The text of the button that opens and closes it, e.g. "바꾸기". */
  toggleLabel: string;
  /** The labels of the issue's company, in name order. Empty: `emptyText` and `emptyAction` instead of boxes. */
  labels: readonly LabelItem[];
  /** IDs of the labels on the issue. */
  selected: readonly string[];
  /**
   * Called once per box: `on` true to put the label on, false to take it off. The picker does not
   * change `selected` itself; the screen sends one request and passes the new `selected` back.
   */
  onToggle: (id: string, on: boolean) => void;
  /** IDs whose request is on its way: those rows are held until it comes back. */
  busyIds?: readonly string[];
  /** 상한: with this many selected the unchecked rows are held and `limitText` shows. Default: no limit. */
  limit?: number;
  /** "라벨은 20개까지 붙입니다." */
  limitText?: string;
  /** Controlled when given. Default: closed. */
  open?: boolean;
  /**
   * Called when the button, Escape or a press outside opens or closes it. On closing, focus goes
   * back to the button.
   */
  onOpenChange?: (open: boolean) => void;
  /** Name of the filter input, e.g. "라벨 거르기". The input is there only with more than eight labels. */
  filterLabel?: string;
  /** When the filter leaves no row, e.g. "맞는 라벨이 없습니다." */
  noMatchText?: string;
  /** "이 고객사에 라벨이 없습니다." */
  emptyText?: string;
  /** After `emptyText`, e.g. the admin's link "고객사 설정에서 만들기". */
  emptyAction?: ReactNode;
  /** One line under the list after a failed request, e.g. "라벨을 바꾸지 못했습니다. 다시 해 보세요." */
  error?: string;
  /** `id` of the button; the opened part gets `{id}-list`. */
  id?: string;
}

/**
 * Puts labels on an issue and takes them off (slice 6a screens.md 1절 라벨 고르기): a button with
 * `aria-expanded`, and under it once opened a filter input (over eight labels), the labels as
 * boxes named `label` with their chips, the limit line and the error line. Each box is one
 * `onToggle` call; a busy row and, at the limit, an unchecked row are held.
 *
 * Contract shell: only the folded button is drawn. Opening, the filter, the boxes, the limit,
 * closing on Escape or outside and the `onToggle` call come later (C-9).
 */
export function LabelPicker(props: LabelPickerProps) {
  return (
    <div className="pd-label-picker" data-pd="LabelPicker">
      <button id={props.id} type="button" className="pd-button" data-variant="secondary" data-size="sm" aria-expanded={false}>
        <span className="pd-button-text">{props.toggleLabel}</span>
      </button>
    </div>
  );
}
