// Whether the window is at phone width (under 640px, the width where portal.css
// turns a list row into a card). Known the way AppShell of the ui package knows its
// own narrow width: `matchMedia`, so what is folded is not drawn at all.
import { useSyncExternalStore } from 'react';

const NARROW = '(max-width: 639.98px)';

function watchNarrow(onChange: () => void): () => void {
  if (typeof window.matchMedia !== 'function') return () => undefined;
  const query = window.matchMedia(NARROW);
  query.addEventListener('change', onChange);
  return () => {
    query.removeEventListener('change', onChange);
  };
}

function isNarrow(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(NARROW).matches;
}

export function useNarrow(): boolean {
  return useSyncExternalStore(watchNarrow, isNarrow);
}
