export interface LabelChipProps {
  /** The label's name, 1~30 characters. Always drawn as text. */
  name: string;
  /** `#RRGGBB`, the background. */
  color: string;
}

/**
 * One label (slice 6a screens.md 5절): the name as text on the label's colour. The text colour
 * comes from `labelTextColor(color)`, black or white, whichever has more contrast (S6A-AC-84), and
 * a 1px border token keeps the chip apart from a background of about the same colour.
 *
 * Contract shell: only the name on the background. The text colour, the border and the size come
 * later (C-9).
 */
export function LabelChip(props: LabelChipProps) {
  return (
    <span className="pd-label-chip" data-pd="LabelChip" style={{ backgroundColor: props.color }}>
      {props.name}
    </span>
  );
}
