// The open issues a person is the assignee of, asked by the dialogs that take an
// agent out (ClearAssignments.tsx, S3-AC-85).
import { useEffect, useState } from 'react';
import { call, type Result } from './result';

/** `loading`, `failed`, or the number of open issues. */
export type OpenAssignments = 'loading' | 'failed' | number;

/**
 * Asks once, when the dialog mounts; `ask` null asks nothing and counts as 0
 * (a customer is nobody's assignee). The dialogs are mounted anew for every
 * opening, so a number that comes after closing shows nowhere (S3-AC-87).
 */
export function useOpenAssignments(ask: (() => Promise<{ data?: { issues: number }; error?: unknown; response: Response }>) | null): OpenAssignments {
  const [answer, setAnswer] = useState<OpenAssignments>(ask === null ? 0 : 'loading');
  useEffect(() => {
    if (ask === null) return undefined;
    let current = true;
    void call(ask()).then((result: Result<{ issues: number }>) => {
      if (current) setAnswer(result.ok ? result.data.issues : 'failed');
    });
    return () => {
      current = false;
    };
    // Once for each dialog.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return answer;
}

/** "담당 N건을 비웠습니다" after the toast of the dialog, when the server cleared any. */
export function clearedText(issues: number | undefined): string {
  return issues !== undefined && issues > 0 ? `. 담당 ${String(issues)}건을 비웠습니다` : '';
}
