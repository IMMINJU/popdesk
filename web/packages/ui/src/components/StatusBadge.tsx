import type { AccountStatus, Audience, IssueStatus, ProjectStatus } from '../types';

/**
 * Dot and text. Issue status has two word sets: customer words
 * ("접수됨") and agent words ("접수"). Project and account status use
 * success, warning and neutral (tokens.md 1절).
 */
export type StatusBadgeProps =
  | { kind: 'issue'; status: IssueStatus; audience: Audience }
  | { kind: 'project'; status: ProjectStatus }
  | { kind: 'account'; status: AccountStatus };

export function StatusBadge(props: StatusBadgeProps) {
  return (
    <span data-pd="StatusBadge" data-kind={props.kind} data-status={props.status}>
      {props.status}
    </span>
  );
}
