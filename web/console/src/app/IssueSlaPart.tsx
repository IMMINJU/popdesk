// The SLA part of C-4 (8a-sla screens.md 3절): a box of its own under the side values, one line for
// each goal. The minutes left are the server's as the issue was read; they change when it is read again.
import { RelativeTime } from '@popdesk/ui';
import type { Schemas } from '../api';
import { slaWords } from './messages';
import { SlaBadge } from './SlaBadge';
import { runs, type GoalName } from './slaGoals';

type SlaGoal = Schemas['SlaGoal'];

function GoalLine(props: { name: GoalName; goal: SlaGoal }) {
  const { name, goal } = props;
  if (goal.state === null) return <li>{name}: 목표 없음</li>;
  if (!runs(goal)) {
    // 지킴 or 못 지킴: the badge is the words.
    return (
      <li>
        {name}: <SlaBadge state={goal.state} />
      </li>
    );
  }
  return (
    <li>
      {name}: {goal.dueAt !== null && <RelativeTime dateTime={goal.dueAt} />} 까지
      {goal.remainingMinutes !== null && `, ${slaWords(goal.remainingMinutes)}`} <SlaBadge state={goal.state} />
    </li>
  );
}

export function IssueSlaPart(props: { sla: Schemas['IssueSla'] }) {
  return (
    <section className="pd-sla-part" aria-labelledby="issue-sla-title">
      <h2 id="issue-sla-title" className="pd-sla-part-title">
        SLA
      </h2>
      <ul>
        <GoalLine name="첫 응답" goal={props.sla.firstResponse} />
        <GoalLine name="해결" goal={props.sla.resolution} />
      </ul>
    </section>
  );
}
