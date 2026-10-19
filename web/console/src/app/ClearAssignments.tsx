// 담당 비우기 in the dialogs that take an agent out: 배정 빼기 (C-9) and 계정 정지
// (C-10) (3-issues screens.md 2절, S3-AC-85). The dialog asks how many open issues
// the person is the assignee of, and offers to clear them. The number only tells;
// it never holds the dialog's button back. Slice 6b adds the open tasks.
import { Checkbox, Skeleton } from '@popdesk/ui';
import type { OpenAssignments } from './openAssignments';
import { t, tf } from './locale';

export interface ClearAssignmentsProps {
  /** The person taken out, e.g. "이상담". */
  name: string;
  open: OpenAssignments;
  /** `company`: the issues of this company only (배정 빼기). `all`: of every company (정지). */
  scope: 'company' | 'all';
  checked: boolean;
  onChange: (checked: boolean) => void;
}

export function ClearAssignments(props: ClearAssignmentsProps) {
  const { open } = props;
  if (open === 'loading') {
    return (
      <p aria-hidden="true">
        <Skeleton shape="line" width="70%" />
      </p>
    );
  }
  const tasks = open === 'failed' ? 0 : open.tasks;
  if (open !== 'failed' && open.issues === 0 && tasks === 0) return null;
  const here = props.scope === 'company';
  return (
    <>
      {/* "N건" is text, not a link: once taken out, the person is no choice of the list's filter. */}
      {open === 'failed' ? (
        <p>{t('clearAssignments.failed')}</p>
      ) : (
        open.issues > 0 && <p>{tf(here ? 'clearAssignments.issuesHere' : 'clearAssignments.issues', { name: props.name, count: open.issues })}</p>
      )}
      {/* Slice 6b: the open tasks, after the issues (6b-tasks screens.md 4절). */}
      {tasks > 0 && <p>{tf(here ? 'clearAssignments.tasksHere' : 'clearAssignments.tasks', { name: props.name, count: tasks })}</p>}
      <Checkbox
        label={tasks > 0 ? t('clearAssignments.clearBoth') : t('clearAssignments.clearIssues')}
        description={t('clearAssignments.help')}
        checked={props.checked}
        onChange={props.onChange}
      />
    </>
  );
}
