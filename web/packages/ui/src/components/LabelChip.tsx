import { labelTextColor } from '../color';

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
 * Both colours sit on the chip's own `style`: they belong to the label, not to the theme. A long
 * name wraps inside the chip instead of pushing its row wider.
 */
export function LabelChip(props: LabelChipProps) {
  return (
    <span className="pd-label-chip" data-pd="LabelChip" style={{ backgroundColor: props.color, color: labelTextColor(props.color) ?? undefined }}>
      {props.name}
    </span>
  );
}
