// Content images of an editor (5-attachments screens.md 1·2절, S5-AC-54~58): which
// images went up in this edit, where each is drawn from, and which IDs a save sends.
// The upload call and the paths are the app's own and come in as arguments.
import type { ImageUploadResult } from '@popdesk/ui';
import { useState } from 'react';
import { uploadFailureText } from './attachments';
import { texts } from './locale';
import type { Failure, Result } from './result';

export const IMAGE_TEXT = texts({
  notImage: 'images.notImage',
  tooMany: 'images.tooMany',
  stale: 'images.stale',
  inline: 'images.inline',
});

/** For `known` of a save's failure band: the save named an image the server no longer takes (S5-AC-58). */
export const IMAGE_SAVE_TEXT = texts({ TEMPORARY_IMAGE_INVALID: 'images.stale' });

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
  const [saves, setSaves] = useState(0);

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
    editor: paths ? { onImageUpload, imageSrc, onImageBusyChange: setBusy, imageNoticesKey: saves } : {},
    /** The `imageIds` of a save of `content`, to spread into its body. */
    idsFor: (content: string): ImageIds => {
      const ids = imageIdsToSend(uploaded, content);
      return ids.length > 0 ? { imageIds: ids } : {};
    },
    /** The save went through: the images it named are attached now, and what failed to go up before is no longer said. */
    saved: () => {
      setUploaded([]);
      setSaves((n) => n + 1);
    },
  };
}
