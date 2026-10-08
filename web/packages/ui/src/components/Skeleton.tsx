export interface SkeletonProps {
  /** 줄, 칸, 원. */
  shape: 'line' | 'block' | 'circle';
  /** CSS length. */
  width?: string;
  /** CSS length. */
  height?: string;
  /** Repeat this many times (e.g. five row lines). Default 1. */
  count?: number;
}

/**
 * Gray placeholder while loading. Hidden from screen readers; the region that
 * loads sets `aria-busy`. Stops pulsing under `prefers-reduced-motion`.
 */
export function Skeleton(props: SkeletonProps) {
  return (
    <span data-pd="Skeleton" data-shape={props.shape} aria-hidden="true">
      {Array.from({ length: props.count ?? 1 }, (_, i) => (
        <span key={i} />
      ))}
    </span>
  );
}
