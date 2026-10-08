import type { ReactNode } from 'react';

export interface EmptyStateProps {
  title: string;
  description?: ReactNode;
  /** Decorative icon from the icon assets. */
  icon?: ReactNode;
  /** 동작 버튼 or link, e.g. "문의 올리기", "목록으로". */
  action?: ReactNode;
  /** 1 when it replaces the whole screen (404, 권한 없음). Default 2. */
  headingLevel?: 1 | 2 | 3;
}

export function EmptyState(props: EmptyStateProps) {
  const Heading = `h${String(props.headingLevel ?? 2)}` as 'h1' | 'h2' | 'h3';
  return (
    <div data-pd="EmptyState">
      {props.icon}
      <Heading>{props.title}</Heading>
      {props.description && <p>{props.description}</p>}
      {props.action}
    </div>
  );
}
