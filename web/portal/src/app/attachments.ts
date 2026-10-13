// Uploading and opening attachments (screens.md P-4·P-5 첨부, ATT-1~5): the shared
// code with the portal's paths.
import { attachmentAddress, countedBytes, uploadFile, type ImagePaths } from '@popdesk/app/attachments';
import { api, type Schemas } from '../api';
import { call, type Result } from './result';

export { ATTACHMENT_LIMITS, IMAGE_SAVE_TEXT, IMAGE_TEXT, REJECT_TEXT, uploadFailureText, useEditorImages, type UploadFailure } from '@popdesk/app/attachments';

export type Attachment = Schemas['Attachment'];

export function attachmentHref(issueKey: string, id: string): string {
  return attachmentAddress('/api/portal/issues', issueKey, id);
}

/** One file as the `file` part of a multipart body (the issue first, then files one by one). */
export function uploadAttachment(issueKey: string, file: File): Promise<Result<Attachment>> {
  return uploadFile(file, (body, bodySerializer) => api.POST('/api/portal/issues/{key}/attachments', { params: { path: { key: issueKey } }, body, bodySerializer }));
}

/** `HEAD` before a download, so a gone file shows a band instead of an error page. */
export function checkAttachment(issueKey: string, id: string): Promise<Result<undefined>> {
  return call(api.HEAD('/api/portal/issues/{key}/attachments/{id}', { params: { path: { key: issueKey, id } } }));
}

/** Sum of the public files that count against the 50MB issue limit. */
export function publicBytes(items: readonly Attachment[]): number {
  return countedBytes(items);
}

/**
 * What an editor needs to take content images (slice 5): the portal's upload and paths.
 * With `issueKey`, an image attached before is drawn from that issue; without (a new
 * issue), only what went up in the edit is drawn.
 */
export function imagePaths(issueKey?: string): ImagePaths {
  return {
    upload: (file) => uploadFile(file, (body, bodySerializer) => api.POST('/api/portal/images', { body, bodySerializer })),
    temporaryHref: (id) => `/api/portal/images/${encodeURIComponent(id)}`,
    ...(issueKey === undefined ? {} : { attachedHref: (id: string) => attachmentHref(issueKey, id) }),
  };
}
