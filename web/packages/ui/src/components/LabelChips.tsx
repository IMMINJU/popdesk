import type { LabelItem } from '../types';
import { LabelChip } from './LabelChip';

export interface LabelChipsProps {
  /** The labels of one issue, in name order. Empty: nothing is drawn. */
  labels: readonly LabelItem[];
  /** How many chips show before "+n". Default 3. */
  max?: number;
  /**
   * Spoken and `title` text of "+n", given the hidden labels, e.g. "라벨 2개 더: 모바일, 보안".
   */
  moreText: (hidden: readonly LabelItem[]) => string;
}

/**
 * The labels of one issue in a row (slice 6a screens.md 2절 C-3 라벨 칸): the first `max` chips,
 * then "+n" whose `title` and hidden text name the rest.
 *
 * "+n" is read too, then the hidden text names what it stands for.
 */
export function LabelChips(props: LabelChipsProps) {
  if (props.labels.length === 0) return null;
  const shown = props.labels.slice(0, Math.max(0, props.max ?? 3));
  const hidden = props.labels.slice(shown.length);
  const more = hidden.length > 0 ? props.moreText(hidden) : '';
  return (
    <span className="pd-label-chips" data-pd="LabelChips">
      {shown.map((l) => (
        <LabelChip key={l.id} name={l.name} color={l.color} />
      ))}
      {hidden.length > 0 && (
        <span className="pd-label-more" title={more}>
          <span>+{hidden.length}</span>
          <span className="pd-sr-only">{more}</span>
        </span>
      )}
    </span>
  );
}
