// The SLA part of C-4 (8a-sla screens.md 3절): a box of its own under the side values, one line for
// each goal. The minutes left are the server's as the issue was read; they change when it is read again.
import type { Schemas } from '../api';
import { slaWords } from './messages';
import { SlaBadge } from './SlaBadge';
import { goalText, runs, type GoalName } from './slaGoals';
import { screenLocale, t, tf } from './locale';

type SlaGoal = Schemas['SlaGoal'];

/** "10월 19일 (월) 오후 6:00": the due time itself, since the words after it already say how far it is. */
function dueText(iso: string): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return iso;
  return new Intl.DateTimeFormat(screenLocale(), {
    month: 'long',
    day: 'numeric',
    weekday: 'short',
    hour: 'numeric',
    minute: '2-digit',
  }).format(at);
}

/** "… 까지" in Korean, "by …" in English, around the `<time>`. */
function DueBy(props: { dueAt: string }) {
  const [before = '', after = ''] = t('sla.dueBy').split('{time}');
  return (
    <>
      {before}
      <time dateTime={props.dueAt}>{dueText(props.dueAt)}</time>
      {after}
    </>
  );
}

function GoalLine(props: { name: GoalName; goal: SlaGoal }) {
  const { goal } = props;
  const name = goalText(props.name);
  if (goal.state === null) return <li>{tf('sla.noGoal', { name })}</li>;
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
      {name}: {goal.dueAt !== null && <DueBy dueAt={goal.dueAt} />}
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
