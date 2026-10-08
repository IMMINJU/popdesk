import type { Priority } from '../types';

export interface PriorityLabelProps {
  priority: Priority;
}

/**
 * Bar icon tag and text: 낮음 one bar, 보통 two, 높음 three on `sunken`;
 * 긴급 on `danger` with an exclamation icon. Never color alone.
 */
export function PriorityLabel(props: PriorityLabelProps) {
  return (
    <span data-pd="PriorityLabel" data-priority={props.priority}>
      {props.priority}
    </span>
  );
}
