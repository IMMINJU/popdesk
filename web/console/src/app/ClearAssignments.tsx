// 담당 비우기 in the dialogs that take an agent out: 배정 빼기 (C-9) and 계정 정지
// (C-10) (3-issues screens.md 2절, S3-AC-85). The dialog asks how many open issues
// the person is the assignee of, and offers to clear them. The number only tells;
// it never holds the dialog's button back. Slice 6b adds the open tasks.
import { Checkbox, Skeleton } from '@popdesk/ui';
import type { OpenAssignments } from './openAssignments';

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
  const where = props.scope === 'company' ? '이 고객사에서 ' : '';
  return (
    <>
      {/* "N건" is text, not a link: once taken out, the person is no choice of the list's filter. */}
      {open === 'failed' ? (
        <p>담당인 문의 수를 확인하지 못했습니다.</p>
      ) : (
        open.issues > 0 && <p>{`${props.name} 님이 ${where}담당인 열린 문의가 ${String(open.issues)}건 있습니다.`}</p>
      )}
      {/* Slice 6b: the open tasks, after the issues (6b-tasks screens.md 4절). */}
      {tasks > 0 && <p>{`${props.name} 님이 ${where}담당인 열린 작업이 ${String(tasks)}건 있습니다.`}</p>}
      <Checkbox
        label={tasks > 0 ? '이 문의와 작업의 담당을 비웁니다' : '이 문의들의 담당을 비웁니다'}
        description="비우지 않으면 담당자로 남지만 문의를 볼 수 없습니다. 문의 목록의 '담당자가 볼 수 없음'으로 찾을 수 있습니다."
        checked={props.checked}
        onChange={props.onChange}
      />
    </>
  );
}
