import { createContext, useContext } from 'react';

export const NavCountContext = createContext<() => void>(() => undefined);

/**
 * Asks the frame to read the side menu's numbers again (the open membership
 * requests on 고객사). A screen calls it after it closed a request (2-manager screens.md 5절).
 */
export function useNavCountRefresh(): () => void {
  return useContext(NavCountContext);
}
