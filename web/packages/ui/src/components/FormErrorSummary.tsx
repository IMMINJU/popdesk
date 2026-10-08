import type { Ref } from 'react';

export interface FieldError {
  /** `id` of the field; the link jumps there. */
  fieldId: string;
  message: string;
}

export interface FormErrorSummaryProps {
  /** Field errors shown in the form, in form order. */
  errors: readonly FieldError[];
  /** Heading, e.g. "2개 칸을 고쳐 주세요". */
  title: string;
  /** Server `message`, shown when `fields` named a field the screen does not have (screens.md 2절). */
  serverMessage?: string;
  ref?: Ref<HTMLDivElement>;
}

export function FormErrorSummary({ ref, ...props }: FormErrorSummaryProps) {
  return (
    <div data-pd="FormErrorSummary" ref={ref} tabIndex={-1}>
      <p>{props.title}</p>
      <ul>
        {props.errors.map((e) => (
          <li key={e.fieldId}>
            <a href={`#${e.fieldId}`}>{e.message}</a>
          </li>
        ))}
      </ul>
      {props.serverMessage && <p>{props.serverMessage}</p>}
    </div>
  );
}
