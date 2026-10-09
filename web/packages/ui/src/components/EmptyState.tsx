import type { ReactNode, Ref } from 'react';

export interface EmptyStateProps {
  title: string;
  description?: ReactNode;
  /** Decorative icon from the icon assets. */
  icon?: ReactNode;
  /** 동작 버튼 or link, e.g. "문의 올리기", "목록으로". */
  action?: ReactNode;
  /** 1 when it replaces the whole screen (404, 권한 없음). Default 2. */
  headingLevel?: 1 | 2 | 3;
  /** The heading, so a screen can move focus to it (`tabIndex={-1}` is set for level 1). */
  headingRef?: Ref<HTMLHeadingElement>;
}

export function EmptyState({ headingRef, ...props }: EmptyStateProps) {
  const level = props.headingLevel ?? 2;
  const Heading = `h${String(level)}` as 'h1' | 'h2' | 'h3';
  return (
    <div className="pd-empty" data-pd="EmptyState" data-level={level}>
      {props.icon !== undefined && (
        <span className="pd-empty-icon" aria-hidden="true">
          {props.icon}
        </span>
      )}
      <Heading ref={headingRef} className="pd-empty-title" tabIndex={level === 1 ? -1 : undefined}>
        {props.title}
      </Heading>
      {props.description !== undefined && <div className="pd-empty-description">{props.description}</div>}
      {props.action !== undefined && <div className="pd-empty-action">{props.action}</div>}
    </div>
  );
}
