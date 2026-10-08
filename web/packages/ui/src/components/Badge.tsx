import type { ReactNode } from 'react';

/** 중립, accent, 경고, 내부 (lock icon and text in internal-text, no left stripe). */
export type BadgeTone = 'neutral' | 'accent' | 'warning' | 'internal';

export interface BadgeProps {
  tone: BadgeTone;
  /** Always text, e.g. "비공개", "내부", "수락 전", "설정 없음". */
  children: ReactNode;
  /** Decorative icon. `internal` uses the lock icon by default. */
  icon?: ReactNode;
}

export function Badge(props: BadgeProps) {
  return (
    <span data-pd="Badge" data-tone={props.tone}>
      {props.icon}
      {props.children}
    </span>
  );
}
