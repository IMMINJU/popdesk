import type { AccountStatus, Audience, CompanyStatus, IssueStatus, ProjectStatus } from '../types';
import { ACCOUNT_STATUS_WORDS, COMPANY_STATUS_WORDS, ISSUE_STATUS_WORDS, PROJECT_STATUS_WORDS } from '../words';

/**
 * Dot and text. Issue status has two word sets: customer words
 * ("접수됨") and agent words ("접수"). Project, company and account status use
 * success, warning and neutral (tokens.md 1절). `company` carries the slice 1a
 * words (사용 중, 중지, 보관).
 */
export type StatusBadgeProps =
  | { kind: 'issue'; status: IssueStatus; audience: Audience }
  | { kind: 'project'; status: ProjectStatus }
  | { kind: 'company'; status: CompanyStatus }
  | { kind: 'account'; status: AccountStatus };

/** Color group of the dot: issue values have their own, the rest use success, warning and neutral. */
function tone(props: StatusBadgeProps): string {
  if (props.kind === 'issue') return props.status.toLowerCase().replace('_', '-');
  if (props.status === 'ACTIVE') return 'success';
  if (props.status === 'SUSPENDED') return 'warning';
  return 'neutral';
}

function word(props: StatusBadgeProps): string {
  if (props.kind === 'issue') return ISSUE_STATUS_WORDS[props.status][props.audience];
  if (props.kind === 'project') return PROJECT_STATUS_WORDS[props.status];
  if (props.kind === 'company') return COMPANY_STATUS_WORDS[props.status];
  return ACCOUNT_STATUS_WORDS[props.status];
}

/** Hollow dot for ended states (종료됨) and accounts not accepted yet (tokens.md 7절). */
function hollow(props: StatusBadgeProps): boolean {
  return (props.kind === 'issue' && props.status === 'CLOSED') || (props.kind === 'account' && props.status === 'INVITED');
}

export function StatusBadge(props: StatusBadgeProps) {
  return (
    <span className="pd-status" data-pd="StatusBadge" data-kind={props.kind} data-tone={tone(props)}>
      <span className="pd-status-dot" data-hollow={hollow(props) ? 'true' : undefined} aria-hidden="true" />
      <span>{word(props)}</span>
    </span>
  );
}
