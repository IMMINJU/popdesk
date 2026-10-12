// "알릴 사람" of the comment forms (4-talk screens.md 2절 C-4): who is mentioned, and the
// candidates the server offers for a public or an internal comment. Drawn by MentionPicker.
import { useCallback, useRef, useState } from 'react';
import { api, type Schemas } from '../api';
import { useLive } from './hooks';
import { t, tf } from './locale';
import { call } from './result';

export type Person = Schemas['Person'];

/** The server takes ten at most (S4-AC-21). */
export const MENTION_LIMIT = 10;

export interface MentionCandidates {
  internal: boolean;
  status: 'loading' | 'ready' | 'failed';
  items: Person[];
}

export interface Mentions {
  /** The picked accounts, in the order they were picked. */
  ids: number[];
  /** Picked accounts differ from the ones it started with. */
  changed: boolean;
  /** The comment turned public or internal: the candidates of the other kind are dropped. */
  setKind: (internal: boolean) => void;
  /** After a send that worked: nobody picked, folded. */
  reset: () => void;
  /** A 400 on `mentions`: says so under the picker, and reads the candidates again for `UNKNOWN_VALUE`. */
  refused: (code: string) => void;
  /** For `MentionPicker` only. */
  view: {
    picked: readonly Person[];
    open: boolean;
    candidates: MentionCandidates | null;
    notice: string | undefined;
    reporterId: number;
    setOpen: (open: boolean) => void;
    pick: (ids: string[]) => void;
    retry: () => void;
  };
}

function sameIds(a: readonly Person[], b: readonly Person[]): boolean {
  return a.length === b.length && a.every((p) => b.some((q) => q.accountId === p.accountId));
}

/**
 * The state of one "알릴 사람". The candidates are read when it first opens, and again when the
 * kind of the comment changes while it is open; folded, the change only drops what was read.
 * A picked person the new candidates do not hold is taken out. An answer for a kind the comment
 * no longer has is dropped (S4-AC-63, 69).
 */
export function useMentions(issueKey: string, startInternal: boolean, reporterId: number, initial: readonly Person[] = []): Mentions {
  const live = useLive();
  const [picked, setPicked] = useState<readonly Person[]>(initial);
  const [open, setOpenState] = useState(false);
  const [candidates, setCandidates] = useState<MentionCandidates | null>(null);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const kind = useRef(startInternal);
  const latest = useRef(0);
  const [started, setStarted] = useState<readonly Person[]>(initial);

  const read = useCallback(
    (internal: boolean) => {
      latest.current += 1;
      const mine = latest.current;
      setCandidates({ internal, status: 'loading', items: [] });
      void call(api.GET('/api/console/issues/{key}/mention-candidates', { params: { path: { key: issueKey }, query: { internal } } })).then((result) => {
        if (!live() || mine !== latest.current) return;
        if (!result.ok) {
          setCandidates({ internal, status: 'failed', items: [] });
          return;
        }
        const items = result.data.items;
        setCandidates({ internal, status: 'ready', items });
        setPicked((now) => now.filter((p) => items.some((c) => c.accountId === p.accountId)));
      });
    },
    [issueKey, live],
  );

  const setOpen = (next: boolean) => {
    setOpenState(next);
    if (next && (candidates === null || candidates.internal !== kind.current || candidates.status === 'failed')) read(kind.current);
  };

  const setKind = (internal: boolean) => {
    if (internal === kind.current) return;
    kind.current = internal;
    // Whatever is on its way belongs to the other kind.
    latest.current += 1;
    setCandidates(null);
    const customer = internal ? picked.find((p) => p.accountId === reporterId) : undefined;
    setNotice(customer ? tf('mentions.customerDropped', { name: customer.name }) : undefined);
    if (customer) setPicked(picked.filter((p) => p !== customer));
    if (open) read(internal);
  };

  const pick = (ids: string[]) => {
    const known = [...picked, ...(candidates?.items ?? [])];
    setPicked(ids.flatMap((id) => known.find((p) => String(p.accountId) === id) ?? []));
    setNotice(undefined);
  };

  const refused = (code: string) => {
    if (code === 'TOO_MANY') {
      setNotice(t('mentions.max'));
      return;
    }
    setNotice(t('mentions.unknown'));
    read(kind.current);
  };

  const reset = () => {
    setPicked([]);
    setOpenState(false);
    setNotice(undefined);
    setStarted([]);
  };

  return {
    ids: picked.map((p) => p.accountId),
    changed: !sameIds(picked, started),
    setKind,
    reset,
    refused,
    view: {
      picked,
      open,
      candidates,
      notice,
      reporterId,
      setOpen,
      pick,
      retry: () => {
        read(kind.current);
      },
    },
  };
}
