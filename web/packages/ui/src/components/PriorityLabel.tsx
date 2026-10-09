import { BarsIcon, ExclamationIcon } from '../icons';
import type { Priority } from '../types';
import { PRIORITY_WORDS } from '../words';

export interface PriorityLabelProps {
  priority: Priority;
}

const BARS: Record<Exclude<Priority, 'URGENT'>, 1 | 2 | 3> = { LOW: 1, NORMAL: 2, HIGH: 3 };

/**
 * Bar icon tag and text: 낮음 one bar, 보통 two, 높음 three on `sunken`;
 * 긴급 on `danger` with an exclamation icon. Never color alone.
 */
export function PriorityLabel(props: PriorityLabelProps) {
  return (
    <span className="pd-priority" data-pd="PriorityLabel" data-priority={props.priority}>
      {props.priority === 'URGENT' ? <ExclamationIcon size={14} /> : <BarsIcon count={BARS[props.priority]} />}
      <span>{PRIORITY_WORDS[props.priority]}</span>
    </span>
  );
}
