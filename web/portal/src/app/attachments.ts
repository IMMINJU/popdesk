// Uploading and opening attachments (screens.md P-4·P-5 첨부, ATT-1~5): the shared
// code with the portal's paths.
import { attachmentAddress, countedBytes, uploadFile } from '@popdesk/app/attachments';
import { api, type Schemas } from '../api';
import { call, type Result } from './result';

export { ATTACHMENT_LIMITS, REJECT_TEXT, uploadFailureText, type UploadFailure } from '@popdesk/app/attachments';

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
