// Uploading and opening attachments (screens.md P-5·C-4 첨부, ATT-1~5): the shared
// code with the console's paths.
import { attachmentAddress, countedBytes, uploadFile } from '@popdesk/app/attachments';
import type { ImagePaths } from '@popdesk/app/bodyImages';
import { api, type Schemas } from '../api';
import { call, type Result } from './result';

export { ATTACHMENT_LIMITS, REJECT_TEXT, uploadFailureText } from '@popdesk/app/attachments';
export { IMAGE_SAVE_TEXT, IMAGE_TEXT, useEditorImages } from '@popdesk/app/bodyImages';

export type Attachment = Schemas['Attachment'];

export function attachmentHref(issueKey: string, id: string): string {
  return attachmentAddress('/api/console/issues', issueKey, id);
}

/** One file as the `file` part of a multipart body (the issue first, then files one by one). */
export function uploadAttachment(issueKey: string, file: File): Promise<Result<Attachment>> {
  return uploadFile(file, (body, bodySerializer) => api.POST('/api/console/issues/{key}/attachments', { params: { path: { key: issueKey } }, body, bodySerializer }));
}

/** `HEAD` before a download, so a gone file shows a band instead of an error page. */
export function checkAttachment(issueKey: string, id: string): Promise<Result<undefined>> {
  return call(api.HEAD('/api/console/issues/{key}/attachments/{id}', { params: { path: { key: issueKey, id } } }));
}

/** Sum of the public files that count against the 50MB issue limit. */
export function publicBytes(items: readonly Attachment[]): number {
  return countedBytes(items, (a) => a.internal);
}

/**
 * What an editor needs to take content images (slice 5): the console's upload and paths.
 * With `issueKey`, an image attached before is drawn from that issue; without (a new
 * issue), only what went up in the edit is drawn.
 */
export function imagePaths(issueKey?: string): ImagePaths {
  return {
    upload: (file) => uploadFile(file, (body, bodySerializer) => api.POST('/api/console/images', { body, bodySerializer })),
    temporaryHref: (id) => `/api/console/images/${encodeURIComponent(id)}`,
    ...(issueKey === undefined ? {} : { attachedHref: (id: string) => attachmentHref(issueKey, id) }),
  };
}
