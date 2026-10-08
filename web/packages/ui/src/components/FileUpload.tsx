import { useId } from 'react';

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
}

/** 비어 있음, 끌어 놓는 중, 고름, 올리는 중, 실패, 끝남. */
export function FileUpload(props: FileUploadProps) {
  const inputId = useId();
  return (
    <div data-pd="FileUpload">
      <label htmlFor={inputId}>{props.label}</label>
      <input
        id={inputId}
        type="file"
        multiple={props.multiple ?? true}
        disabled={props.disabled}
        onChange={(e) => {
          props.onSelect(Array.from(e.target.files ?? []), []);
        }}
      />
      {props.helpText && <p>{props.helpText}</p>}
      <ul>
        {props.items.map((item) => (
          <li key={item.id} data-state={item.state}>
            {item.name}
          </li>
        ))}
      </ul>
    </div>
  );
}
