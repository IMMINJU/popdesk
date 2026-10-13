import {
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ClipboardEvent,
  type DragEvent,
  type KeyboardEvent,
  type ReactNode,
  type Ref,
} from 'react';
import { codePoints } from '../format';
import { describedBy } from '../aria';
import { WarningIcon } from '../icons';
import { FieldError, FieldHelp } from './fieldParts';
import { MarkdownView } from './MarkdownView';

/** What an image upload ends with. The text of a failure is made by the app. */
export type ImageUploadResult = { ok: true; id: string } | { ok: false; message: string };

/**
 * Toolbar buttons in the first slice. The image button (ATT-6) is not one of them: it comes
 * after these when `onImageUpload` is given.
 */
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
  /**
   * 이미지 (slice 5): uploads one file. With it the toolbar gets an "이미지" button after `tools`,
   * and the text area takes pasted images and dropped files. Several files go one at a time.
   * When one is up, `![file name](attachment:{id})` and a line break go in where the cursor
   * was when the upload began. Absent: none of this, the editor is what it was.
   */
  onImageUpload?: ((file: File) => Promise<ImageUploadResult>) | undefined;
  /** The address of an image by its id, for the preview (see MarkdownView). Absent: images stay text there. */
  imageSrc?: ((id: string) => string | null) | undefined;
  /** Told `true` when uploads begin and `false` when the last one ends, so the screen can hold its save back. */
  onImageBusyChange?: ((busy: boolean) => void) | undefined;
  /** Name of the image button. Default "이미지". */
  imageLabel?: string | undefined;
  /** The line under the text area while a file is going up. Default "이미지 올리는 중… (file name)". */
  imageUploadingText?: ((fileName: string) => string) | undefined;
  /** The line while files are dragged over the text area. Default "여기에 놓으면 이미지를 넣습니다". */
  imageDropText?: string | undefined;
  /** The line after a dropped file that is not an image. */
  imageOnlyText?: string | undefined;
  /** Handed to the preview (see MarkdownView). */
  unreadableImageText?: string | undefined;
  /**
   * A new value takes away the lines of failed uploads: the screen saved the text, so they are
   * about a text that is gone. Without it they stay until the writer starts the next upload.
   */
  imageNoticesKey?: number | string | undefined;
  ref?: Ref<HTMLTextAreaElement>;
}

const IMAGE_ACCEPT = 'image/png,image/jpeg,image/gif,image/webp';

const IMAGE_GLYPH = (
  <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
    <rect x="2" y="3" width="12" height="10" rx="1.5" />
    <circle cx="5.75" cy="6.5" r="1" fill="currentColor" stroke="none" />
    <path d="m2.5 11.5 3.25-3 2.5 2.25 2-1.75 3.25 3" />
  </svg>
);

/** A place in the text that follows what is typed around it while an upload is on its way. */
interface Mark {
  at: number;
}

/**
 * Where the position `at` of `before` is in `after`, taking the change as one replaced run
 * (what one key press, paste or tool makes). A position before the run stays, one after it
 * moves by the difference in length, one inside it goes to the end of what replaced it.
 */
function movedPosition(at: number, before: string, after: string): number {
  const shorter = Math.min(before.length, after.length);
  let head = 0;
  while (head < shorter && before[head] === after[head]) head += 1;
  if (at <= head) return at;
  let tail = 0;
  while (tail < shorter - head && before[before.length - 1 - tail] === after[after.length - 1 - tail]) tail += 1;
  if (at >= before.length - tail) return at + after.length - before.length;
  return after.length - tail;
}

function isImage(file: File): boolean {
  return file.type.startsWith('image/');
}

/** True while what is dragged or dropped is files (not a piece of text). */
function carriesFiles(transfer: DataTransfer): boolean {
  return Array.from(transfer.types).includes('Files');
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
  // The image button is not a tool: it comes after `tools`, and not at all without a toolbar.
  const imageButton = props.onImageUpload !== undefined && tools.length > 0;
  const buttonCount = tools.length + (imageButton ? 1 : 0);

  const fileInput = useRef<HTMLInputElement | null>(null);
  const [uploading, setUploading] = useState<string | null>(null);
  const [failures, setFailures] = useState<{ name: string; message: string }[]>([]);
  const [dragging, setDragging] = useState(false);
  const [notImage, setNotImage] = useState(false);
  const [noticesKey, setNoticesKey] = useState(props.imageNoticesKey);
  if (noticesKey !== props.imageNoticesKey) {
    setNoticesKey(props.imageNoticesKey);
    setFailures([]);
    setNotImage(false);
  }
  const queue = useRef<{ file: File; mark: Mark }[]>([]);
  /** The mark of the upload on its way. Set while the queue is being worked through. */
  const active = useRef<Mark | null>(null);
  const alive = useRef(true);
  const latest = useRef({ value: props.value, onChange: props.onChange, onImageUpload: props.onImageUpload, onImageBusyChange: props.onImageBusyChange });

  const marks = () => new Set([...(active.current ? [active.current] : []), ...queue.current.map((item) => item.mark)]);

  useLayoutEffect(() => {
    const before = latest.current.value;
    if (before !== props.value) {
      for (const mark of marks()) mark.at = movedPosition(mark.at, before, props.value);
    }
    latest.current = { value: props.value, onChange: props.onChange, onImageUpload: props.onImageUpload, onImageBusyChange: props.onImageBusyChange };
  });

  useEffect(() => {
    alive.current = true;
    return () => {
      // An answer that comes after this is dropped. The screen is told the uploads are over.
      alive.current = false;
      queue.current = [];
      if (active.current) latest.current.onImageBusyChange?.(false);
    };
  }, []);

  const insertImage = (mark: Mark, fileName: string, imageId: string) => {
    const { value, onChange } = latest.current;
    const at = Math.min(mark.at, value.length);
    // "[" and "]" in a file name would end the description early.
    const inserted = `![${fileName.replace(/[[\]]/g, '')}](attachment:${imageId})\n`;
    const next = value.slice(0, at) + inserted + value.slice(at);
    const after = (position: number) => (position >= at ? position + inserted.length : position);
    // An upload begun later at the same place goes under this one too, hence ">=".
    for (const other of marks()) if (other !== mark && other.at >= at) other.at += inserted.length;
    // The next file of the same batch goes under this one.
    mark.at = at + inserted.length;
    latest.current.value = next;
    const el = area.current;
    const selection = el ? ([after(el.selectionStart), after(el.selectionEnd)] as const) : null;
    onChange(next);
    if (!selection) return;
    // Setting the value moves the cursor to the end; put it back where the writer had it.
    requestAnimationFrame(() => {
      area.current?.setSelectionRange(selection[0], selection[1]);
    });
  };

  const drain = async () => {
    latest.current.onImageBusyChange?.(true);
    for (let next = queue.current.shift(); next; next = queue.current.shift()) {
      active.current = next.mark;
      setUploading(next.file.name);
      const { file } = next;
      const upload = latest.current.onImageUpload;
      // `then` so that a function that throws before it returns a promise fails the same way.
      const result: ImageUploadResult = await Promise.resolve()
        .then(() => (upload ? upload(file) : { ok: false as const, message: '' }))
        .catch(() => ({ ok: false as const, message: '' }));
      if (!alive.current) return;
      if (result.ok) insertImage(next.mark, file.name, result.id);
      else setFailures((list) => [...list, { name: file.name, message: result.message }]);
    }
    active.current = null;
    setUploading(null);
    latest.current.onImageBusyChange?.(false);
  };

  /** The one way in for the button, a paste and a drop. The files go up one at a time, in this order. */
  const takeImages = (files: File[]) => {
    const el = area.current;
    if (!props.onImageUpload || props.disabled || !el || files.length === 0) return;
    // A new upload by the writer clears what the last one left.
    setFailures([]);
    setNotImage(false);
    // With a selection, the image goes after it and the selected text stays.
    const mark = { at: el.selectionEnd };
    queue.current.push(...files.map((file) => ({ file, mark })));
    if (!active.current) void drain();
  };

  const onPaste = (e: ClipboardEvent<HTMLTextAreaElement>) => {
    if (!props.onImageUpload || props.disabled) return;
    const images = Array.from(e.clipboardData.files).filter(isImage);
    // Text alone, or a file that is not an image, is pasted as before.
    if (images.length === 0) return;
    e.preventDefault();
    takeImages(images);
  };

  const onDragOver = (e: DragEvent<HTMLTextAreaElement>) => {
    if (!props.onImageUpload || props.disabled || !carriesFiles(e.dataTransfer)) return;
    e.preventDefault();
    setDragging(true);
  };

  const onDrop = (e: DragEvent<HTMLTextAreaElement>) => {
    if (!props.onImageUpload || props.disabled || !carriesFiles(e.dataTransfer)) return;
    // Without this the browser opens the dropped file.
    e.preventDefault();
    setDragging(false);
    const files = Array.from(e.dataTransfer.files);
    takeImages(files.filter(isImage));
    setNotImage(files.some((file) => !isImage(file)));
  };

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
      ArrowRight: (index + 1) % buttonCount,
      ArrowLeft: (index - 1 + buttonCount) % buttonCount,
      Home: 0,
      End: buttonCount - 1,
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
      data-dragging={dragging ? 'true' : undefined}
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
                  tabIndex={index === Math.min(activeTool, buttonCount - 1) ? 0 : -1}
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
              {imageButton && (
                <button
                  ref={(el) => {
                    toolButtons.current[tools.length] = el;
                  }}
                  type="button"
                  className="pd-editor-tool"
                  aria-label={props.imageLabel ?? '이미지'}
                  title={props.imageLabel ?? '이미지'}
                  tabIndex={Math.min(activeTool, buttonCount - 1) === tools.length ? 0 : -1}
                  disabled={props.disabled}
                  onMouseDown={(e) => {
                    e.preventDefault();
                  }}
                  onClick={() => {
                    setActiveTool(tools.length);
                    fileInput.current?.click();
                  }}
                  onKeyDown={(e) => {
                    onToolKey(e, tools.length);
                  }}
                >
                  <span aria-hidden="true">{IMAGE_GLYPH}</span>
                </button>
              )}
            </div>
          )}
          {imageButton && (
            <input
              ref={fileInput}
              type="file"
              hidden
              tabIndex={-1}
              accept={IMAGE_ACCEPT}
              multiple
              disabled={props.disabled}
              aria-label={props.imageLabel ?? '이미지'}
              onChange={(e) => {
                takeImages(Array.from(e.target.files ?? []));
                // Lets the same file be picked again.
                e.target.value = '';
              }}
            />
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
            onPaste={onPaste}
            onDragEnter={onDragOver}
            onDragOver={onDragOver}
            onDragLeave={() => {
              setDragging(false);
            }}
            onDrop={onDrop}
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
              <MarkdownView source={props.value} imageSrc={props.imageSrc} unreadableImageText={props.unreadableImageText} />
            )}
          </div>
        )}
      </div>
      {dragging && <p className="pd-editor-note">{props.imageDropText ?? '여기에 놓으면 이미지를 넣습니다'}</p>}
      {uploading !== null && (
        <p className="pd-editor-note" role="status">
          {props.imageUploadingText ? props.imageUploadingText(uploading) : `이미지 올리는 중… (${uploading})`}
        </p>
      )}
      {(failures.length > 0 || notImage) && (
        <div className="pd-editor-failures" role="alert">
          {notImage && (
            <p className="pd-field-error">
              <WarningIcon size={14} />
              <span>{props.imageOnlyText ?? '이미지 파일만 본문에 넣을 수 있습니다. 다른 파일은 첨부로 올리세요'}</span>
            </p>
          )}
          {failures.map((failure, index) => (
            // The list only grows, and is emptied as a whole.
            <p key={index} className="pd-field-error">
              <WarningIcon size={14} />
              <span>
                {failure.name}
                {failure.message !== '' && `: ${failure.message}`}
              </span>
            </p>
          ))}
        </div>
      )}
      <FieldHelp id={helpId} text={props.helpText} />
      <FieldError id={errorId} text={props.error} />
    </div>
  );
}
