import { useId, type ChangeEvent } from 'react';
import { Spinner } from '../icons';
import { Alert } from './Alert';
import { Avatar } from './Avatar';
import { Button } from './Button';

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

/** 사진 없음, 사진 있음, 올리는 중, 오류 (1a screens.md 1절 내 정보). */
export function PhotoField(props: PhotoFieldProps) {
  const inputId = useId();
  const helpId = `${inputId}-help`;
  const uploading = props.uploading ?? false;
  const pick = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    // Cleared so that picking the same file again, after a failure, is a change.
    e.target.value = '';
    if (file && !uploading) props.onUpload(file);
  };
  return (
    <div className="pd-photo-field" data-pd="PhotoField" data-uploading={uploading ? 'true' : undefined}>
      <div className="pd-photo-row">
        <Avatar name={props.name} photoUrl={props.photoUrl ?? null} />
        <div className="pd-photo-main">
          <div className="pd-photo-actions">
            <input
              id={inputId}
              className="pd-upload-input"
              type="file"
              accept={props.accept}
              disabled={uploading}
              aria-describedby={props.helpText ? helpId : undefined}
              onChange={pick}
            />
            <label className="pd-upload-label" htmlFor={inputId}>
              {props.photoUrl ? props.changeLabel : props.uploadLabel}
            </label>
            {props.photoUrl && (
              <Button variant="quiet" size="sm" blocked={uploading} onClick={props.onRemove}>
                {props.removeLabel}
              </Button>
            )}
            {uploading && <Spinner />}
          </div>
          {props.helpText && (
            <p id={helpId} className="pd-field-help">
              {props.helpText}
            </p>
          )}
        </div>
      </div>
      {props.error && <Alert tone="error">{props.error}</Alert>}
    </div>
  );
}
