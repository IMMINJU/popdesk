import { useId, type Ref } from 'react';

/** Toolbar buttons in the first slice. The image button comes with ATT-6. */
export type MarkdownTool =
  | 'bold'
  | 'italic'
  | 'strikethrough'
  | 'heading'
  | 'bulletList'
  | 'orderedList'
  | 'quote'
  | 'code'
  | 'link';

export interface MarkdownEditorProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
  /** px. P-4 body 420, comments 112. */
  minHeight: number;
  id?: string;
  name?: string;
  /** 쓰기·미리 보기 tab. Controlled when given. */
  mode?: 'write' | 'preview';
  onModeChange?: (mode: 'write' | 'preview') => void;
  /** Default: every tool in `MarkdownTool`. */
  tools?: readonly MarkdownTool[];
  placeholder?: string;
  maxLength?: number;
  /** 글자 수 표시. Needs `maxLength`. */
  showCount?: boolean;
  helpText?: string;
  error?: string;
  disabled?: boolean;
  /** 내부 모드 (C-4): internal-bg background and internal-border bottom line. */
  internal?: boolean;
  /** Ctrl+Enter (Cmd+Enter on mac) in the text area (C-4). Tab still leaves the field. */
  onSubmitShortcut?: () => void;
  ref?: Ref<HTMLTextAreaElement>;
}

export function MarkdownEditor({ ref, ...props }: MarkdownEditorProps) {
  const autoId = useId();
  const id = props.id ?? autoId;
  return (
    <div data-pd="MarkdownEditor" data-mode={props.mode ?? 'write'} data-internal={props.internal}>
      <label htmlFor={id}>{props.label}</label>
      <textarea
        ref={ref}
        id={id}
        name={props.name}
        value={props.value}
        onChange={(e) => {
          props.onChange(e.target.value);
        }}
        placeholder={props.placeholder}
        maxLength={props.maxLength}
        disabled={props.disabled}
        aria-invalid={props.error ? true : undefined}
        style={{ minHeight: props.minHeight }}
      />
      {props.helpText && <p>{props.helpText}</p>}
      {props.error && <p>{props.error}</p>}
    </div>
  );
}
