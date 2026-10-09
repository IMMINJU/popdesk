import type { ReactNode } from 'react';
import { LockIcon } from '../icons';

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
  const icon = props.icon ?? (props.tone === 'internal' ? <LockIcon size={12} /> : undefined);
  return (
    <span className="pd-badge" data-pd="Badge" data-tone={props.tone}>
      {icon !== undefined && (
        <span className="pd-badge-icon" aria-hidden="true">
          {icon}
        </span>
      )}
      <span>{props.children}</span>
    </span>
  );
}
