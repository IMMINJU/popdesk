import { useId, useState, type ReactNode } from 'react';
import { CheckIcon } from '../icons';
import type { LabelItem } from '../types';
import { LabelChip } from './LabelChip';
import { usePopover } from './popover';
import { TextField } from './TextField';

/** Over this many labels a filter input comes first (screens.md 1절). */
const FILTER_FROM = 9;

export interface LabelPickerProps {
  /** Names the boxes when opened, e.g. "라벨". */
  label: string;
  /** The text of the button that opens and closes it, e.g. "바꾸기". */
  toggleLabel: string;
  /**
   * Accessible name of that button when the visible text alone is not enough, e.g. "라벨 바꾸기"
   * beside the visible "바꾸기" (it must contain the visible text). Default: the visible text.
   */
  toggleName?: string;
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
 * A held row is `aria-disabled`, not `disabled`, so focus stays on it while its request runs.
 */
export function LabelPicker(props: LabelPickerProps) {
  const autoId = useId();
  const id = props.id ?? autoId;
  const listId = `${id}-list`;
  const { root, toggle, open, setOpen } = usePopover(props);
  return (
    <div ref={root} className="pd-label-picker" data-pd="LabelPicker">
      <button
        ref={toggle}
        id={id}
        type="button"
        className="pd-button"
        data-variant="secondary"
        data-size="sm"
        aria-label={props.toggleName}
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        onClick={() => {
          setOpen(!open);
        }}
      >
        <span className="pd-button-text">{props.toggleLabel}</span>
      </button>
      {open && <LabelPickerList {...props} listId={listId} />}
    </div>
  );
}

function LabelPickerList(props: LabelPickerProps & { listId: string }) {
  const [filter, setFilter] = useState('');
  const busy = props.busyIds ?? [];
  const full = props.limit !== undefined && props.selected.length >= props.limit;
  const needle = filter.trim().toLocaleLowerCase();
  const rows = needle === '' ? props.labels : props.labels.filter((l) => l.name.toLocaleLowerCase().includes(needle));
  // In the group with the rows it is about, so it is read with them.
  const error = props.error !== undefined && props.error !== '' && (
    <p className="pd-popover-error" role="alert">
      {props.error}
    </p>
  );
  return (
    <div id={props.listId} className="pd-popover pd-label-picker-list">
      {props.labels.length === 0 ? (
        <div className="pd-popover-state">
          <p>{props.emptyText}</p>
          {props.emptyAction}
        </div>
      ) : (
        <>
          {props.labels.length >= FILTER_FROM && <TextField type="search" label={props.filterLabel ?? ''} value={filter} onChange={setFilter} />}
          <fieldset className="pd-group pd-label-picker-group">
            <legend className="pd-label">{props.label}</legend>
            <div className="pd-group-options">
              {rows.map((l) => {
                const on = props.selected.includes(l.id);
                const held = busy.includes(l.id) || (full && !on);
                const boxId = `${props.listId}-${l.id}`;
                return (
                  <div key={l.id} className="pd-check">
                    <span className="pd-check-box">
                      <input
                        id={boxId}
                        type="checkbox"
                        className="pd-check-input"
                        value={l.id}
                        checked={on}
                        aria-disabled={held ? true : undefined}
                        onChange={(e) => {
                          if (!held) props.onToggle(l.id, e.target.checked);
                        }}
                      />
                      <span className="pd-check-mark" aria-hidden="true">
                        <CheckIcon size={14} />
                      </span>
                    </span>
                    <label className="pd-check-label" htmlFor={boxId}>
                      <LabelChip name={l.name} color={l.color} />
                    </label>
                  </div>
                );
              })}
            </div>
            {rows.length === 0 && <p className="pd-popover-state">{props.noMatchText}</p>}
            {full && props.limitText !== undefined && <p className="pd-popover-state">{props.limitText}</p>}
            {error}
          </fieldset>
        </>
      )}
      {props.labels.length === 0 && error}
    </div>
  );
}
