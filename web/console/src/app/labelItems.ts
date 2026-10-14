// Labels as the shared components take them (6a-labels screens.md 1·2·5절): C-3 and C-4 both use these.
import type { LabelItem } from '@popdesk/ui';

/** "라벨 2개 더: 모바일, 보안": the hidden part of a row of chips (screens.md 5절). */
export function moreLabelsText(hidden: readonly LabelItem[]): string {
  return `라벨 ${String(hidden.length)}개 더: ${hidden.map((l) => l.name).join(', ')}`;
}

/** A label of the API as the components take it. */
export function labelItem(l: { id: number; name: string; color: string }): LabelItem {
  return { id: String(l.id), name: l.name, color: l.color };
}

/** Name order whatever the case (S6A-AC-50). */
export function byName<T extends { name: string }>(items: readonly T[]): T[] {
  return [...items].sort((a, b) => a.name.toLocaleLowerCase().localeCompare(b.name.toLocaleLowerCase()));
}
