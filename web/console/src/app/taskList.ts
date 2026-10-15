// The task list of one issue for the 작업 구역 of C-4 (6b-tasks screens.md 1절), and the open count
// the status buttons ask with (S6B-AC-86).
import { useLayoutEffect, useRef, useState } from 'react';
import { api, type Schemas } from '../api';
import { useLive, useLoad } from './hooks';
import { call } from './result';

type Task = Schemas['Task'];
type TaskList = Schemas['IssueTasks'];

/** Open tasks that are not deleted: what the list gives as `openCount`. */
export function openOf(items: readonly Task[]): number {
  return items.filter((t) => !t.deleted && t.status === 'OPEN').length;
}

/** The task list of one issue, and the ways the 작업 구역 changes it. */
export interface IssueTasksState {
  /** Null until the first answer. */
  list: TaskList | null;
  failed: boolean;
  /** Reads the list again. */
  read: () => Promise<void>;
  /** Puts the server's task in place of the one with its ID, or after the others when it is new. */
  put: (task: Task) => void;
}

/**
 * Reads the tasks of the issue `key` when the screen opens. A list read again replaces what was
 * put since; answers after the screen has gone are dropped.
 */
export function useIssueTasks(key: string): IssueTasksState {
  const live = useLive();
  const load = useLoad(() => call(api.GET('/api/console/issues/{key}/tasks', { params: { path: { key } } })), key);
  // Tasks put since the list was read, on top of that list.
  const [local, setLocal] = useState<{ from: TaskList; list: TaskList } | null>(null);
  const read = useRef(load.data);
  useLayoutEffect(() => {
    read.current = load.data;
  });
  const list = load.data === undefined ? null : local !== null && local.from === load.data ? local.list : load.data;

  return {
    list,
    failed: load.status === 'failed',
    read: async () => {
      await load.reload();
    },
    put: (task) => {
      if (!live()) return;
      setLocal((current) => {
        const from = read.current;
        if (from === undefined) return current;
        const base = current !== null && current.from === from ? current.list : from;
        const items = base.items.some((t) => t.id === task.id) ? base.items.map((t) => (t.id === task.id ? task : t)) : [...base.items, task];
        return { from, list: { items, openCount: openOf(items) } };
      });
    },
  };
}
