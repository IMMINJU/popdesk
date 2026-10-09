import { useId, useRef, useState, type KeyboardEvent, type ReactNode, type Ref } from 'react';
import { codePoints } from '../format';
import { describedBy } from '../aria';
import { FieldError, FieldHelp } from './fieldParts';
import { MarkdownView } from './MarkdownView';

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
  /** Hides the visible label (the screen names the editor another way, e.g. 댓글 쓰기 tabs). */
  labelHidden?: boolean;
  /** Tool button names. Default: the Korean names of the first slice (screens.md P-4). */
  toolLabels?: Partial<Record<MarkdownTool, string>>;
  /** Default "쓰기", "미리 보기", "서식", "미리 볼 내용이 없습니다". */
  writeLabel?: string;
  previewLabel?: string;
  toolbarLabel?: string;
  emptyPreviewText?: string;
  ref?: Ref<HTMLTextAreaElement>;
}

const ALL_TOOLS: readonly MarkdownTool[] = ['bold', 'italic', 'strikethrough', 'heading', 'bulletList', 'orderedList', 'quote', 'code', 'link'];

const TOOL_LABELS: Record<MarkdownTool, string> = {
  bold: '굵게',
  italic: '기울임',
  strikethrough: '취소선',
  heading: '제목',
  bulletList: '글머리 목록',
  orderedList: '번호 목록',
  quote: '인용',
  code: '코드',
  link: '링크',
};

/** Glyphs drawn on the tool buttons. Decorative; the name is the label. */
const TOOL_GLYPHS: Record<MarkdownTool, ReactNode> = {
  bold: <b>B</b>,
  italic: <i>I</i>,
  strikethrough: <s>S</s>,
  heading: 'H',
  bulletList: (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
      <circle cx="3" cy="4" r="0.9" fill="currentColor" />
      <circle cx="3" cy="8" r="0.9" fill="currentColor" />
      <circle cx="3" cy="12" r="0.9" fill="currentColor" />
      <path d="M6 4h7.5M6 8h7.5M6 12h7.5" />
    </svg>
  ),
  orderedList: (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
      <path d="M6 4h7.5M6 8h7.5M6 12h7.5" />
      <path d="M2.25 2.75h1v2.5M2 10.5c.3-.4.7-.6 1.1-.6.5 0 .9.3.9.8 0 .7-1.9 1.4-1.9 2.3h2" strokeWidth="1.1" />
    </svg>
  ),
  quote: (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
      <path d="M3 3.5v9M6.5 5h7M6.5 8h7M6.5 11h5" />
    </svg>
  ),
  code: (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M5.5 4.5 2 8l3.5 3.5M10.5 4.5 14 8l-3.5 3.5" />
    </svg>
  ),
  link: (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
      <path d="M6.75 9.25a2.5 2.5 0 0 0 3.5 0l2.25-2.25a2.5 2.5 0 0 0-3.5-3.5l-.75.75M9.25 6.75a2.5 2.5 0 0 0-3.5 0L3.5 9a2.5 2.5 0 0 0 3.5 3.5l.75-.75" />
    </svg>
  ),
};

interface Edit {
  value: string;
  start: number;
  end: number;
}

/** Wraps the selection in `before` and `after`, or inserts both with the cursor between. */
function wrap(value: string, start: number, end: number, before: string, after = before): Edit {
  const picked = value.slice(start, end);
  return {
    value: value.slice(0, start) + before + picked + after + value.slice(end),
    start: start + before.length,
    end: end + before.length,
  };
}

/** Puts `prefix` (or a numbered prefix) at the start of every line the selection touches. */
function prefixLines(value: string, start: number, end: number, prefix: (index: number) => string): Edit {
  const lineStart = value.lastIndexOf('\n', start - 1) + 1;
  const nextBreak = value.indexOf('\n', end);
  const lineEnd = nextBreak < 0 ? value.length : nextBreak;
  const lines = value.slice(lineStart, lineEnd).split('\n');
  const changed = lines.map((line, i) => prefix(i) + line).join('\n');
  const added = changed.length - (lineEnd - lineStart);
  return {
    value: value.slice(0, lineStart) + changed + value.slice(lineEnd),
    start: start + prefix(0).length,
    end: end + added,
  };
}

function apply(tool: MarkdownTool, value: string, start: number, end: number): Edit {
  switch (tool) {
    case 'bold':
      return wrap(value, start, end, '**');
    case 'italic':
      return wrap(value, start, end, '*');
    case 'strikethrough':
      return wrap(value, start, end, '~~');
    case 'heading':
      return prefixLines(value, start, end, () => '## ');
    case 'bulletList':
      return prefixLines(value, start, end, () => '- ');
    case 'orderedList':
      return prefixLines(value, start, end, (i) => `${String(i + 1)}. `);
    case 'quote':
      return prefixLines(value, start, end, () => '> ');
    case 'code':
      return value.slice(start, end).includes('\n') ? wrap(value, start, end, '```\n', '\n```') : wrap(value, start, end, '`');
    case 'link': {
      const text = value.slice(start, end);
      const inserted = `[${text}](https://)`;
      const urlStart = start + text.length + 3;
      return { value: value.slice(0, start) + inserted + value.slice(end), start: urlStart, end: urlStart + 'https://'.length };
    }
  }
}

/**
 * Markdown text area with a toolbar and 쓰기·미리 보기 tabs. The toolbar is one
 * Tab stop (arrow keys move inside it). Tab in the text area leaves it.
 */
export function MarkdownEditor({ ref, ...props }: MarkdownEditorProps) {
  const autoId = useId();
  const id = props.id ?? autoId;
  const helpId = `${id}-help`;
  const errorId = `${id}-error`;
  const writeTabId = `${id}-write-tab`;
  const previewTabId = `${id}-preview-tab`;
  const writePanelId = `${id}-write`;
  const previewPanelId = `${id}-preview`;
  const area = useRef<HTMLTextAreaElement | null>(null);
  const tabs = useRef<(HTMLButtonElement | null)[]>([]);
  const toolButtons = useRef<(HTMLButtonElement | null)[]>([]);
  const [ownMode, setOwnMode] = useState<'write' | 'preview'>('write');
  const [activeTool, setActiveTool] = useState(0);
  const mode = props.mode ?? ownMode;
  const tools = props.tools ?? ALL_TOOLS;

  const setArea = (el: HTMLTextAreaElement | null) => {
    area.current = el;
    if (typeof ref === 'function') ref(el);
    else if (ref) ref.current = el;
  };

  const changeMode = (next: 'write' | 'preview') => {
    if (props.mode === undefined) setOwnMode(next);
    props.onModeChange?.(next);
  };

  const onTabKey = (e: KeyboardEvent<HTMLButtonElement>, index: number) => {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft' && e.key !== 'Home' && e.key !== 'End') return;
    e.preventDefault();
    const next = e.key === 'Home' ? 0 : e.key === 'End' ? 1 : (index + 1) % 2;
    tabs.current[next]?.focus();
    changeMode(next === 0 ? 'write' : 'preview');
  };

  const runTool = (tool: MarkdownTool) => {
    const el = area.current;
    if (!el || props.disabled) return;
    const edit = apply(tool, props.value, el.selectionStart, el.selectionEnd);
    props.onChange(edit.value);
    // The controlled value lands on the next render; put the cursor back after it.
    requestAnimationFrame(() => {
      const current = area.current;
      if (!current) return;
      current.focus();
      current.setSelectionRange(edit.start, edit.end);
    });
  };

  const onToolKey = (e: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const moves: Record<string, number> = {
      ArrowRight: (index + 1) % tools.length,
      ArrowLeft: (index - 1 + tools.length) % tools.length,
      Home: 0,
      End: tools.length - 1,
    };
    const next = moves[e.key];
    if (next === undefined) return;
    e.preventDefault();
    setActiveTool(next);
    toolButtons.current[next]?.focus();
  };

  const onAreaKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      props.onSubmitShortcut?.();
    }
  };

  const count = props.showCount === true && props.maxLength !== undefined;
  const labels = { ...TOOL_LABELS, ...props.toolLabels };

  return (
    <div
      className="pd-field pd-editor"
      data-pd="MarkdownEditor"
      data-mode={mode}
      data-internal={props.internal ? 'true' : undefined}
      data-invalid={props.error ? 'true' : undefined}
    >
      <div className="pd-field-head">
        <label className={props.labelHidden ? 'pd-sr-only' : 'pd-label'} htmlFor={id}>
          {props.label}
        </label>
        {count && (
          <span className="pd-field-count">
            {codePoints(props.value).toLocaleString('en-US')} / {props.maxLength?.toLocaleString('en-US')}
          </span>
        )}
      </div>
      <div className="pd-editor-frame">
        <div className="pd-editor-bar">
          <div className="pd-editor-tabs" role="tablist" aria-label={props.label}>
            {(['write', 'preview'] as const).map((m, index) => (
              <button
                key={m}
                ref={(el) => {
                  tabs.current[index] = el;
                }}
                id={m === 'write' ? writeTabId : previewTabId}
                type="button"
                role="tab"
                className="pd-editor-tab"
                aria-selected={mode === m}
                aria-controls={m === 'write' ? writePanelId : previewPanelId}
                tabIndex={mode === m ? 0 : -1}
                onClick={() => {
                  changeMode(m);
                }}
                onKeyDown={(e) => {
                  onTabKey(e, index);
                }}
              >
                {m === 'write' ? (props.writeLabel ?? '쓰기') : (props.previewLabel ?? '미리 보기')}
              </button>
            ))}
          </div>
          {mode === 'write' && tools.length > 0 && (
            <div className="pd-editor-tools" role="toolbar" aria-label={props.toolbarLabel ?? '서식'} aria-controls={id}>
              {tools.map((tool, index) => (
                <button
                  key={tool}
                  ref={(el) => {
                    toolButtons.current[index] = el;
                  }}
                  type="button"
                  className="pd-editor-tool"
                  aria-label={labels[tool]}
                  title={labels[tool]}
                  tabIndex={index === Math.min(activeTool, tools.length - 1) ? 0 : -1}
                  disabled={props.disabled}
                  onMouseDown={(e) => {
                    // Keep the selection in the text area.
                    e.preventDefault();
                  }}
                  onClick={() => {
                    setActiveTool(index);
                    runTool(tool);
                  }}
                  onKeyDown={(e) => {
                    onToolKey(e, index);
                  }}
                >
                  <span aria-hidden="true">{TOOL_GLYPHS[tool]}</span>
                </button>
              ))}
            </div>
          )}
        </div>
        <div id={writePanelId} role="tabpanel" aria-labelledby={writeTabId} hidden={mode !== 'write'} className="pd-editor-panel">
          <textarea
            ref={setArea}
            id={id}
            className="pd-editor-area"
            name={props.name}
            value={props.value}
            onChange={(e) => {
              props.onChange(e.target.value);
            }}
            onKeyDown={onAreaKey}
            placeholder={props.placeholder}
            maxLength={props.maxLength}
            disabled={props.disabled}
            aria-invalid={props.error ? true : undefined}
            aria-describedby={describedBy(props.helpText && helpId, props.error && errorId)}
            style={{ minHeight: props.minHeight }}
          />
        </div>
        {mode === 'preview' && (
          <div
            id={previewPanelId}
            role="tabpanel"
            aria-labelledby={previewTabId}
            tabIndex={0}
            className="pd-editor-panel pd-editor-preview"
            style={{ minHeight: props.minHeight }}
          >
            {props.value.trim() === '' ? (
              <p className="pd-editor-empty">{props.emptyPreviewText ?? '미리 볼 내용이 없습니다'}</p>
            ) : (
              <MarkdownView source={props.value} />
            )}
          </div>
        )}
      </div>
      <FieldHelp id={helpId} text={props.helpText} />
      <FieldError id={errorId} text={props.error} />
    </div>
  );
}
