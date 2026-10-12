// Toasts for saved changes (screens.md 2절 저장 성공). The stack sits outside the
// router so a toast survives the move to the next screen.
import { Toast, type ToastMessage } from '@popdesk/ui';
import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react';
import { ToastContext, type ShowToast } from './toastContext';

export function ToastProvider(props: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastMessage[]>([]);
  const next = useRef(0);
  const show = useCallback<ShowToast>((message, tone = 'success') => {
    next.current += 1;
    const id = `toast-${String(next.current)}`;
    setToasts((current) => [...current, { id, tone, message }].slice(-3));
  }, []);
  const dismiss = useCallback((id: string) => {
    setToasts((current) => current.filter((t) => t.id !== id));
  }, []);
  const value = useMemo(() => show, [show]);
  return (
    <ToastContext value={value}>
      {props.children}
      <Toast toasts={toasts} onDismiss={dismiss} />
    </ToastContext>
  );
}
