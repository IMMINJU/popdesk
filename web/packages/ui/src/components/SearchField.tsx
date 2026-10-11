import { useId } from 'react';
import { describedBy } from '../aria';
import { FieldHelp } from './fieldParts';

export interface SearchFieldProps {
  /** Names the box, e.g. "문의 검색". */
  label: string;
  /** The text the list is searched with now. */
  value: string;
  /** Called with the typed text after the typing stops, and at once on Enter. */
  onSearch: (value: string) => void;
  /** How long after the last key `onSearch` is called. Default 300. */
  delayMs?: number;
  /** 도움말 under the box, joined with `aria-describedby`, e.g. "제목과 내용에서 찾습니다". */
  hint?: string;
  disabled?: boolean;
}

/**
 * A labelled search box in a `search` landmark, drawn like `TextField` (tokens.md 7절).
 * Contract shell: the delay, Enter, the 검색어 지우기 button and the `onSearch` call come later (C-9).
 */
export function SearchField(props: SearchFieldProps) {
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
        id={id}
        className="pd-input"
        type="search"
        defaultValue={props.value}
        disabled={props.disabled}
        spellCheck={false}
        aria-describedby={describedBy(props.hint && hintId)}
      />
      <FieldHelp id={hintId} text={props.hint} />
    </div>
  );
}
