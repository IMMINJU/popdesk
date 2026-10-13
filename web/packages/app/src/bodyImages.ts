// Content images of an editor (5-attachments screens.md 1·2절, S5-AC-54~58): which
// images went up in this edit, where each is drawn from, and which IDs a save sends.
// The upload call and the paths are the app's own and come in as arguments.
import type { ImageUploadResult } from '@popdesk/ui';
import { useState } from 'react';
import { uploadFailureText } from './attachments';
import type { Failure, Result } from './result';

export const IMAGE_TEXT = {
  notImage: 'PNG, JPEG, GIF, WebP 이미지만 본문에 넣을 수 있습니다',
  tooMany: '저장하지 않은 이미지가 너무 많습니다. 글을 저장한 뒤 다시 올리세요',
  stale: '본문의 이미지 가운데 올린 지 오래된 것이 있습니다. 이미지를 다시 올린 뒤 저장하세요',
  inline: '본문 이미지',
} as const;

/** For `known` of a save's failure band: the save named an image the server no longer takes (S5-AC-58). */
export const IMAGE_SAVE_TEXT = { TEMPORARY_IMAGE_INVALID: IMAGE_TEXT.stale } as const;

/** Why an image did not go up, for the line under the editor. What an attachment says too is said in its words. */
export function imageFailureText(failure: Failure): string {
  if (failure.kind === 'rejected' && failure.code === 'FILE_NOT_IMAGE') return IMAGE_TEXT.notImage;
  if (failure.kind === 'rejected' && failure.code === 'TEMPORARY_IMAGE_LIMIT_EXCEEDED') return IMAGE_TEXT.tooMany;
  return uploadFailureText(failure);
}

/** What an app gives an editor that takes images. */
export interface ImagePaths {
  /** The app's own upload of a temporary image. */
  upload: (file: File) => Promise<Result<{ id: string }>>;
  /** Address of an image uploaded and not attached yet. */
  temporaryHref: (id: string) => string;
  /** Address of an image attached to the issue being read. Absent where there is no issue yet. */
  attachedHref?: (id: string) => string;
}

/** The `imageIds` part of a save's body: absent when there is nothing to attach. */
export interface ImageIds {
  imageIds?: string[];
}

/**
 * The IDs a save sends as `imageIds` (S5-AC-57).
 *
 * `uploaded`: the IDs of the images uploaded in this edit, in the order they went up.
 * Any strings; an ID may be there more than once. Images attached before are never in it.
 * `content`: the text being saved. Any string, also empty.
 *
 * Returns the IDs of `uploaded` that `content` still holds as `(attachment:{id})`, the
 * target of an image as the editor writes it, each once, in the order of `uploaded`.
 * An empty list when `uploaded` is empty or `content` names none of them. Never an ID
 * that is not in `uploaded`, whatever `content` names.
 */
export function imageIdsToSend(uploaded: readonly string[], content: string): string[] {
  return [...new Set(uploaded)].filter((id) => content.includes(`(attachment:${id})`));
}

/**
 * The images of one edit. A new edit (the component that calls this mounts again, or
 * `saved` is called) starts with none: what went up before is attached by then and is
 * drawn from the issue.
 */
export function useEditorImages(paths: ImagePaths | undefined) {
  const [uploaded, setUploaded] = useState<readonly string[]>([]);
  const [busy, setBusy] = useState(false);

  const onImageUpload = async (file: File): Promise<ImageUploadResult> => {
    if (!paths) return { ok: false, message: '' };
    const result = await paths.upload(file);
    if (!result.ok) return { ok: false, message: imageFailureText(result.failure) };
    const { id } = result.data;
    setUploaded((ids) => [...ids, id]);
    return { ok: true, id };
  };

  const imageSrc = (id: string): string | null => {
    if (!paths) return null;
    return uploaded.includes(id) ? paths.temporaryHref(id) : (paths.attachedHref?.(id) ?? null);
  };

  return {
    /** An upload is on its way: the save waits, by its button and by Ctrl+Enter. */
    busy,
    /** Props of the MarkdownEditor. Nothing without `paths`: the editor is then what it was. */
    editor: paths ? { onImageUpload, imageSrc, onImageBusyChange: setBusy } : {},
    /** The `imageIds` of a save of `content`, to spread into its body. */
    idsFor: (content: string): ImageIds => {
      const ids = imageIdsToSend(uploaded, content);
      return ids.length > 0 ? { imageIds: ids } : {};
    },
    /** The save went through: the images it named are attached now. */
    saved: () => {
      setUploaded([]);
    },
  };
}
