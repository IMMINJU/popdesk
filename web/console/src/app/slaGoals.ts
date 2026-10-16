// The SLA goals of an issue as C-3 and C-4 show them (8a-sla screens.md 2·3절). The state and the
// minutes left are the server's; the screen only picks which goal to show and its badge.
import type { Schemas } from '../api';

type IssueSla = Schemas['IssueSla'];
type SlaGoal = Schemas['SlaGoal'];
export type GoalName = '첫 응답' | '해결';

const RANK: Readonly<Record<string, number>> = { BREACHED: 0, WARNING: 1, OK: 2 };

/** True while the goal's clock runs. */
export function runs(goal: SlaGoal): boolean {
  return (goal.state === 'OK' || goal.state === 'WARNING' || goal.state === 'BREACHED') && goal.remainingMinutes !== null;
}

/**
 * The worse running goal: 넘김 before 곧 넘김 before 괜찮음, and the earlier due time when both are
 * alike (acceptance.md 5절 12). Null when no goal runs.
 */
export function worseGoal(sla: IssueSla | null): { name: GoalName; goal: SlaGoal } | null {
  if (sla === null) return null;
  const running = [
    { name: '첫 응답' as const, goal: sla.firstResponse },
    { name: '해결' as const, goal: sla.resolution },
  ].filter((g) => runs(g.goal));
  running.sort((a, b) => {
    const rank = (RANK[a.goal.state ?? ''] ?? 3) - (RANK[b.goal.state ?? ''] ?? 3);
    return rank !== 0 ? rank : Date.parse(a.goal.dueAt ?? '') - Date.parse(b.goal.dueAt ?? '');
  });
  return running[0] ?? null;
}
