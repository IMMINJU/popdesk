import { useId, type Ref } from 'react';
import { describedBy } from '../aria';
import { FieldHelp } from './fieldParts';

export interface SearchFieldProps {
  /** Names the box, e.g. "문의 검색". */
  label: string;
  /** The text the list is searched with now. When it changes from outside (뒤로 가기, 필터 지우기), the box shows the new text. */
  value: string;
  /** Called with the typed text after the typing stops, and at once on Enter. */
  onSearch: (value: string) => void;
  /** How long after the last key `onSearch` is called. Default 300. */
  delayMs?: number;
  /** 도움말 under the box, joined with `aria-describedby`, e.g. "제목과 내용에서 찾습니다". */
  hint?: string;
  disabled?: boolean;
  /** Default 100, the longest `q` the API takes. */
  maxLength?: number;
  /** Lets the screen move focus to the box. */
  ref?: Ref<HTMLInputElement>;
}

/**
 * A labelled search box in a `search` landmark, drawn like `TextField` (tokens.md 7절).
 * Contract shell: the delay, Enter, the 검색어 지우기 button, the `onSearch` call and following `value` come later (C-9).
 */
export function SearchField({ ref, ...props }: SearchFieldProps) {
  const id = useId();
  const hintId = `${id}-hint`;
  return (
    <div className="pd-field" data-pd="SearchField" role="search">
      <div className="pd-field-head">
        <label className="pd-label" htmlFor={id}>
          {props.label}
        </label>
      </div>
      <input
        ref={ref}
        id={id}
        className="pd-input"
        type="search"
        defaultValue={props.value}
        disabled={props.disabled}
        maxLength={props.maxLength ?? 100}
        spellCheck={false}
        aria-describedby={describedBy(props.hint && hintId)}
      />
      <FieldHelp id={hintId} text={props.hint} />
    </div>
  );
}
