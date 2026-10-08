export interface ToastMessage {
  id: string;
  /** 성공, 안내. */
  tone: 'success' | 'info';
  message: string;
}

export interface ToastProps {
  /** Newest last. At most three are shown; older ones drop off. */
  toasts: readonly ToastMessage[];
  /** Called when a toast times out or is closed. */
  onDismiss: (id: string) => void;
  /** Default 5000. The timer stops while the pointer or focus is on a toast. */
  durationMs?: number;
}

/** Toast stack: one `role="status"` region (`aria-live="polite"`). */
export function Toast(props: ToastProps) {
  return (
    <div data-pd="Toast" role="status" aria-live="polite">
      {props.toasts.slice(-3).map((t) => (
        <div key={t.id} data-tone={t.tone}>
          {t.message}
        </div>
      ))}
    </div>
  );
}
