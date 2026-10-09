import { useId, useState, type DragEvent } from 'react';
import { codePoints, formatBytes } from '../format';
import { CheckCircleIcon, FileIcon, UploadIcon, WarningIcon } from '../icons';
import { Button } from './Button';
import { ProgressBar } from './ProgressBar';

/** Why the browser refused a file before upload (ATT-2). */
export type FileRejectReason = 'too-large' | 'total-too-large' | 'empty' | 'name-too-long';

export interface FileUploadLimits {
  /** 10MB in the first slice. */
  maxFileBytes: number;
  /** 50MB per issue in the first slice. */
  maxTotalBytes: number;
  /** 255 in the first slice. */
  maxNameLength: number;
}

export interface UploadItem {
  id: string;
  name: string;
  /** Bytes. */
  size: number;
  /** 고름, 올리는 중, 실패, 끝남, 화면에서 막음. */
  state: 'selected' | 'uploading' | 'failed' | 'done' | 'rejected';
  /** 0..1 while uploading; absent means unknown. */
  progress?: number;
  /** Reason text for `failed` and `rejected`, e.g. "파일이 서버 앞단의 크기 상한에 걸렸습니다". */
  error?: string;
}

export interface FileUploadProps {
  /** Button and drop zone name, e.g. "파일 첨부". */
  label: string;
  /** File rows the screen keeps. */
  items: readonly UploadItem[];
  /** Files picked or dropped, split by the limit check. Bytes already counted: the `items`. */
  onSelect: (accepted: File[], rejected: { file: File; reason: FileRejectReason }[]) => void;
  /** 지우기 / 빼기 on a row. */
  onRemove: (id: string) => void;
  /** 다시 올리기 on a failed row. */
  onRetry?: (id: string) => void;
  limits?: FileUploadLimits;
  helpText?: string;
  disabled?: boolean;
  /** Default true. */
  multiple?: boolean;
  /** Labels for the row buttons. */
  removeLabel?: string;
  retryLabel?: string;
  /** Bytes already used outside `items`, e.g. attachments the issue already has. Default 0. */
  usedBytes?: number;
  /** Hint inside the drop zone, e.g. "또는 여기로 끌어 놓으세요". */
  dropHint?: string;
}

/** Splits picked files by the limits (ATT-2), in the order they came. */
function check(files: File[], props: FileUploadProps) {
  const accepted: File[] = [];
  const rejected: { file: File; reason: FileRejectReason }[] = [];
  let total = (props.usedBytes ?? 0) + props.items.filter((i) => i.state !== 'rejected').reduce((sum, i) => sum + i.size, 0);
  for (const file of files) {
    const limits = props.limits;
    let reason: FileRejectReason | undefined;
    if (file.size === 0) reason = 'empty';
    else if (limits && file.size > limits.maxFileBytes) reason = 'too-large';
    else if (limits && codePoints(file.name) > limits.maxNameLength) reason = 'name-too-long';
    else if (limits && total + file.size > limits.maxTotalBytes) reason = 'total-too-large';
    if (reason) {
      rejected.push({ file, reason });
    } else {
      accepted.push(file);
      total += file.size;
    }
  }
  return { accepted, rejected };
}

/** 비어 있음, 끌어 놓는 중, 고름, 올리는 중, 실패, 끝남. */
export function FileUpload(props: FileUploadProps) {
  const inputId = useId();
  const helpId = `${inputId}-help`;
  const [dragging, setDragging] = useState(false);

  const take = (files: File[]) => {
    if (props.disabled || files.length === 0) return;
    const picked = props.multiple === false ? files.slice(0, 1) : files;
    const { accepted, rejected } = check(picked, props);
    props.onSelect(accepted, rejected);
  };

  const hasFiles = (e: DragEvent) => Array.from(e.dataTransfer.types).includes('Files');

  return (
    <div className="pd-upload" data-pd="FileUpload" data-dragging={dragging ? 'true' : undefined} data-disabled={props.disabled ? 'true' : undefined}>
      <div
        className="pd-upload-zone"
        onDragEnter={(e) => {
          if (!hasFiles(e) || props.disabled) return;
          e.preventDefault();
          setDragging(true);
        }}
        onDragOver={(e) => {
          if (!hasFiles(e) || props.disabled) return;
          e.preventDefault();
        }}
        onDragLeave={(e) => {
          if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setDragging(false);
        }}
        onDrop={(e) => {
          e.preventDefault();
          setDragging(false);
          take(Array.from(e.dataTransfer.files));
        }}
      >
        <input
          id={inputId}
          className="pd-upload-input"
          type="file"
          multiple={props.multiple ?? true}
          disabled={props.disabled}
          aria-describedby={props.helpText ? helpId : undefined}
          onChange={(e) => {
            take(Array.from(e.target.files ?? []));
            // Lets the same file be picked again after it was removed.
            e.target.value = '';
          }}
        />
        <label className="pd-upload-label" htmlFor={inputId}>
          <span className="pd-upload-icon" aria-hidden="true">
            <UploadIcon />
          </span>
          <span className="pd-upload-name">{props.label}</span>
        </label>
        {props.dropHint && (
          <span className="pd-upload-hint" aria-hidden="true">
            {props.dropHint}
          </span>
        )}
      </div>
      {props.helpText && (
        <p id={helpId} className="pd-field-help">
          {props.helpText}
        </p>
      )}
      {props.items.length > 0 && (
        <ul className="pd-upload-list">
          {props.items.map((item) => {
            const nameId = `${inputId}-${item.id}`;
            return (
              <li key={item.id} className="pd-upload-row" data-state={item.state}>
                <span className="pd-upload-row-icon" aria-hidden="true">
                  {item.state === 'done' ? <CheckCircleIcon /> : item.state === 'failed' || item.state === 'rejected' ? <WarningIcon /> : <FileIcon />}
                </span>
                <span className="pd-upload-row-main">
                  <span className="pd-upload-row-name" id={nameId}>
                    {item.name}
                  </span>
                  <span className="pd-upload-row-size">{formatBytes(item.size)}</span>
                  {item.state === 'uploading' && (
                    <ProgressBar label={item.name} {...(item.progress === undefined ? {} : { value: item.progress })} />
                  )}
                  {item.error && (item.state === 'failed' || item.state === 'rejected') && (
                    <span className="pd-upload-row-error">{item.error}</span>
                  )}
                </span>
                <span className="pd-upload-row-actions">
                  {item.state === 'failed' && props.onRetry && (
                    <Button
                      variant="secondary"
                      size="sm"
                      describedBy={nameId}
                      onClick={() => {
                        props.onRetry?.(item.id);
                      }}
                    >
                      {props.retryLabel ?? 'Retry'}
                    </Button>
                  )}
                  {item.state !== 'uploading' && (
                    <Button
                      variant="quiet"
                      size="sm"
                      describedBy={nameId}
                      onClick={() => {
                        props.onRemove(item.id);
                      }}
                    >
                      {props.removeLabel ?? 'Remove'}
                    </Button>
                  )}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
