// Uploading and opening attachments (screens.md P-4·P-5·C-4 첨부, ATT-1~5). The
// paths and the client are the app's own and come in as arguments.
import type { FileRejectReason, FileUploadLimits } from '@popdesk/ui';
import { t, texts } from './locale';
import { rateLimitedText, TEXT } from './messages';
import { call, type Failure, type Result } from './result';

/** A file that could not be sent after its issue was made; the issue's screen lists them (screens.md P-4 첨부 일부 실패). */
export interface UploadFailure {
  name: string;
  reason: string;
}

/** First-slice limits (ATT-2): 10MB a file, 50MB of public files an issue, names to 255. */
export const ATTACHMENT_LIMITS: FileUploadLimits = {
  maxFileBytes: 10 * 1024 * 1024,
  maxTotalBytes: 50 * 1024 * 1024,
  maxNameLength: 255,
};

export const REJECT_TEXT: Readonly<Record<FileRejectReason, string>> = texts({
  'too-large': 'attachments.tooLarge',
  'total-too-large': 'attachments.totalTooLarge',
  empty: 'attachments.empty',
  'name-too-long': 'attachments.nameTooLong',
});

/** Why an upload failed, for its file row. 413 has its own text (UX-11); other failures never use it. */
export function uploadFailureText(failure: Failure): string {
  if (failure.kind === 'tooLarge') return TEXT.tooLarge;
  if (failure.kind === 'rateLimited') return rateLimitedText(failure.seconds);
  if (failure.kind === 'rejected') {
    const known: Record<string, string> = {
      FILE_TOO_LARGE: REJECT_TEXT['too-large'],
      FILE_EMPTY: REJECT_TEXT.empty,
      ISSUE_ATTACHMENTS_TOO_LARGE: REJECT_TEXT['total-too-large'],
      ATTACHMENT_QUOTA_EXCEEDED: t('attachments.quotaExceeded'),
      ISSUE_CLOSED: t('attachments.issueClosed'),
    };
    return known[failure.code] ?? failure.message;
  }
  if (failure.kind === 'invalid') return failure.message;
  if (failure.kind === 'forbidden') return failure.code === 'CSRF_TOKEN_INVALID' ? TEXT.stale : TEXT.forbidden;
  if (failure.kind === 'unauthenticated') return TEXT.saveSessionEnded;
  if (failure.kind === 'notFound') return TEXT.notFound;
  return TEXT.server;
}

/** Address of one attachment of an issue under `base` (the app's own issues path). */
export function attachmentAddress(base: string, issueKey: string, id: string): string {
  return `${base}/${encodeURIComponent(issueKey)}/attachments/${encodeURIComponent(id)}`;
}

/**
 * One file as the `file` part of a multipart body (the issue first, then files one by one).
 * `post` is the app's own upload call: it sends `body` with `bodySerializer`.
 */
export function uploadFile<A>(
  file: File,
  post: (body: { file: string }, bodySerializer: () => FormData) => Promise<{ data?: A; error?: unknown; response: Response }>,
): Promise<Result<A>> {
  const form = new FormData();
  form.append('file', file, file.name);
  return call(post({ file: file.name }, () => form));
}

/**
 * Sum of the files that count against the 50MB issue limit. A deleted file never
 * counts, and neither does one that `left` picks out.
 */
export function countedBytes<A extends { deleted: boolean; size: number }>(items: readonly A[], left?: (item: A) => boolean): number {
  return items.filter((a) => !a.deleted && !left?.(a)).reduce((sum, a) => sum + a.size, 0);
}
