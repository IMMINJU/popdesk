import { createContext, useContext, useEffect } from 'react';

export const BusyContext = createContext<(busy: boolean) => void>(() => undefined);

/** Marks `main` with `aria-busy` while a screen loads its first data (screens.md 2절). */
export function useMainBusy(busy: boolean) {
  const setBusy = useContext(BusyContext);
  useEffect(() => {
    setBusy(busy);
    return () => {
      setBusy(false);
    };
  }, [busy, setBusy]);
}
