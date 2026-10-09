import type { ToastMessage } from '@popdesk/ui';
import { createContext, useContext } from 'react';

export type ShowToast = (message: string, tone?: ToastMessage['tone']) => void;

export const ToastContext = createContext<ShowToast>(() => undefined);

export function useToast(): ShowToast {
  return useContext(ToastContext);
}
