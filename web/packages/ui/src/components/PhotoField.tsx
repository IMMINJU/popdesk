import { useId } from 'react';
import { Avatar } from './Avatar';

export interface PhotoFieldProps {
  /** Person name, used for the initials when there is no photo. */
  name: string;
  /** Photo URL now. Absent or `null` shows the initials. */
  photoUrl?: string | null;
  /** 올리는 중: the buttons are blocked and a spinner shows. */
  uploading?: boolean;
  /** Reason text of the last failed upload, e.g. "2MB를 넘습니다". The photo before it stays. */
  error?: string;
  /** Accepted formats and size, e.g. "PNG·JPEG·WebP, 2MB까지". */
  helpText?: string;
  /** `accept` of the file input, e.g. "image/png,image/jpeg,image/webp". */
  accept?: string;
  /** The file the person picked. The screen uploads it; there is no cropping. */
  onUpload: (file: File) => void;
  /** 지우기. Not asked twice (the photo can be uploaded again). */
  onRemove: () => void;
  /** Button name without a photo, e.g. "사진 올리기". */
  uploadLabel: string;
  /** Button name with a photo, e.g. "바꾸기". */
  changeLabel: string;
  /** e.g. "지우기". Shown only with a photo. */
  removeLabel: string;
}

/**
 * 사진 없음, 사진 있음, 올리는 중, 오류 (1a screens.md 1절 내 정보).
 * Contract shell: the markup only. C-9 fills in the look, the callbacks and the blocking.
 */
export function PhotoField(props: PhotoFieldProps) {
  const inputId = useId();
  return (
    <div data-pd="PhotoField" data-uploading={props.uploading ? 'true' : undefined}>
      <Avatar name={props.name} photoUrl={props.photoUrl ?? null} decorative />
      <label htmlFor={inputId}>{props.photoUrl ? props.changeLabel : props.uploadLabel}</label>
      <input id={inputId} type="file" accept={props.accept} />
      {props.photoUrl && <button type="button">{props.removeLabel}</button>}
      {props.helpText && <p>{props.helpText}</p>}
      {props.error && <p>{props.error}</p>}
    </div>
  );
}
