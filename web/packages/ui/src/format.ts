// Small text formats the components share.

/** Bytes as "512 B", "2.0 KB", "9.5 MB". */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${String(bytes)} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** Length in Unicode code points, as the server counts it (conventions.md "Text input"). */
export function codePoints(text: string): number {
  return Array.from(text).length;
}
