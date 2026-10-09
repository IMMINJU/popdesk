// Shared parts of the labelled controls: the help and error lines under a
// field and the `aria-describedby` that joins them (screens.md 2절 접근성).
import { WarningIcon } from '../icons';

export function FieldHelp(props: { id: string; text?: string | undefined }) {
  if (!props.text) return null;
  return (
    <p id={props.id} className="pd-field-help">
      {props.text}
    </p>
  );
}

export function FieldError(props: { id: string; text?: string | undefined }) {
  if (!props.text) return null;
  return (
    <p id={props.id} className="pd-field-error">
      <WarningIcon size={14} />
      <span>{props.text}</span>
    </p>
  );
}
