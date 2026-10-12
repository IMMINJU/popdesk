// 담당 비우기 in the dialogs that take an agent out: 배정 빼기 (C-9) and 계정 정지
// (C-10) (3-issues screens.md 2절, S3-AC-85). The dialog asks how many open issues
// the person is the assignee of, and offers to clear them. The number only tells;
// it never holds the dialog's button back.
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
  if (open === 0) return null;
  if (open === 'loading') {
    return (
      <p aria-hidden="true">
        <Skeleton shape="line" width="70%" />
      </p>
    );
  }
  return (
    <>
      {/* "N건" is text, not a link: once taken out, the person is no choice of the list's filter. */}
      <p>
        {open === 'failed'
          ? '담당인 문의 수를 확인하지 못했습니다.'
          : `${props.name} 님이 ${props.scope === 'company' ? '이 고객사에서 ' : ''}담당인 열린 문의가 ${String(open)}건 있습니다.`}
      </p>
      <Checkbox
        label="이 문의들의 담당을 비웁니다"
        description="비우지 않으면 담당자로 남지만 문의를 볼 수 없습니다. 문의 목록의 '담당자가 볼 수 없음'으로 찾을 수 있습니다."
        checked={props.checked}
        onChange={props.onChange}
      />
    </>
  );
}
