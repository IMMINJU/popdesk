import { useEffect, useEffectEvent, useRef, useState } from 'react';
import { CheckCircleIcon, InfoIcon } from '../icons';

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

function ToastItem(props: { toast: ToastMessage; paused: boolean; durationMs: number; onDismiss: (id: string) => void }) {
  const remaining = useRef(props.durationMs);
  const dismiss = useEffectEvent((id: string) => {
    props.onDismiss(id);
  });
  const { id } = props.toast;

  useEffect(() => {
    if (props.paused) return undefined;
    const started = Date.now();
    const timer = setTimeout(() => {
      dismiss(id);
    }, Math.max(remaining.current, 0));
    return () => {
      clearTimeout(timer);
      remaining.current -= Date.now() - started;
    };
  }, [props.paused, id]);

  return (
    <div className="pd-toast" data-tone={props.toast.tone}>
      <span className="pd-toast-icon" aria-hidden="true">
        {props.toast.tone === 'success' ? <CheckCircleIcon /> : <InfoIcon />}
      </span>
      <span>{props.toast.message}</span>
    </div>
  );
}

/** Toast stack: one `role="status"` region (`aria-live="polite"`). Filled, no shadow, no border. */
export function Toast(props: ToastProps) {
  const [hovered, setHovered] = useState(false);
  const [focused, setFocused] = useState(false);
  const paused = hovered || focused;
  return (
    <div
      className="pd-toast-stack"
      data-pd="Toast"
      role="status"
      aria-live="polite"
      onMouseEnter={() => {
        setHovered(true);
      }}
      onMouseLeave={() => {
        setHovered(false);
      }}
      onFocus={() => {
        setFocused(true);
      }}
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget)) setFocused(false);
      }}
    >
      {props.toasts.slice(-3).map((t) => (
        <ToastItem key={t.id} toast={t} paused={paused} durationMs={props.durationMs ?? 5000} onDismiss={props.onDismiss} />
      ))}
    </div>
  );
}
