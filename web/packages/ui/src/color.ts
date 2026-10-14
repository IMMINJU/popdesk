// Colour arithmetic for label chips (slice 6a design.md 9절, S6A-AC-84).

const BLACK = '#000000';
const WHITE = '#FFFFFF';

/** WCAG 2.1 relative luminance of one 0~255 channel, linearised. */
function channel(c: number): number {
  const s = c / 255;
  return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
}

/**
 * The text colour for a label chip on `background`: black or white, whichever has the larger
 * WCAG 2.1 contrast ratio with it.
 *
 * Takes `#RRGGBB` in either case, with or without the `#` (`#f2c14e`, `F2C14E`), and spaces
 * around it. Returns `'#000000'` or `'#FFFFFF'`. When the two ratios are equal it returns black.
 * The two ratios cross at a relative luminance of about 0.179: `#767676` (luminance 0.181) gets
 * black, `#757575` (0.178) gets white.
 *
 * Returns `null` for anything else: an empty string, `#RGB`, a name like `red`, `rgb(…)`, or a
 * letter that is not hexadecimal. The chip then sets no colour of its own and keeps the theme's.
 */
export function labelTextColor(background: string): string | null {
  const hex = /^#?([0-9a-f]{6})$/i.exec(background.trim())?.[1];
  if (hex === undefined) return null;
  const [r, g, b] = [0, 2, 4].map((i) => channel(parseInt(hex.slice(i, i + 2), 16))) as [number, number, number];
  const luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
  const onBlack = (luminance + 0.05) / 0.05;
  const onWhite = 1.05 / (luminance + 0.05);
  return onBlack >= onWhite ? BLACK : WHITE;
}
