import { useEffect, useId, useRef, useState, type Ref } from 'react';
import { describedBy } from '../aria';
import { CloseIcon } from '../icons';
import { FieldHelp } from './fieldParts';
import { IconButton } from './IconButton';

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
 * The box keeps what is typed and tells the screen after the typing stops, at once on Enter
 * and when the 검색어 지우기 button empties it.
 */
export function SearchField({ ref, ...props }: SearchFieldProps) {
  const id = useId();
  const hintId = `${id}-hint`;
  const [text, setText] = useState(props.value);
  // The last `value` the box saw and the last text it told the screen. A `value` that only
  // echoes what the box told does not overwrite what was typed since.
  const [seen, setSeen] = useState(props.value);
  const [told, setTold] = useState<string | null>(null);
  if (props.value !== seen) {
    setSeen(props.value);
    if (props.value !== told) setText(props.value);
  }

  const input = useRef<HTMLInputElement | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(
    () => () => {
      clearTimeout(timer.current);
    },
    [],
  );

  const tell = (value: string) => {
    clearTimeout(timer.current);
    timer.current = undefined;
    setTold(value);
    props.onSearch(value);
  };
  const delayMs = props.delayMs ?? 300;

  return (
    <div className="pd-field" data-pd="SearchField" role="search">
      <div className="pd-field-head">
        <label className="pd-label" htmlFor={id}>
          {props.label}
        </label>
      </div>
      <div className="pd-input-wrap" data-has-end={text === '' ? undefined : 'true'}>
        <input
          ref={(el) => {
            input.current = el;
            if (typeof ref === 'function') ref(el);
            else if (ref) ref.current = el;
          }}
          id={id}
          className="pd-input"
          type="search"
          value={text}
          onChange={(e) => {
            const next = e.target.value;
            setText(next);
            clearTimeout(timer.current);
            timer.current = setTimeout(() => {
              tell(next);
            }, delayMs);
          }}
          onKeyDown={(e) => {
            // Not while an IME is still composing a syllable.
            if (e.key === 'Enter' && !e.nativeEvent.isComposing) tell(text);
          }}
          disabled={props.disabled}
          maxLength={props.maxLength ?? 100}
          spellCheck={false}
          aria-describedby={describedBy(props.hint && hintId)}
        />
        {text !== '' && (
          <span className="pd-input-end">
            <IconButton
              label="검색어 지우기"
              icon={<CloseIcon />}
              size="sm"
              tooltip={false}
              disabled={props.disabled ?? false}
              onClick={() => {
                setText('');
                tell('');
                input.current?.focus();
              }}
            />
          </span>
        )}
      </div>
      <FieldHelp id={hintId} text={props.hint} />
    </div>
  );
}
