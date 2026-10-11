// Finding an account by a part of its name or email (S1A-AC-50). C-9 소속 고객
// 더하기 and the C-12 한 사람 filter draw the same search box and choices.
import { RadioGroup, TextField, type Option } from '@popdesk/ui';
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { api, type Schemas } from '../api';
import { call, type Failure } from './result';

type Account = Schemas['Account'];

/** The search goes out this long after the last key. */
const SEARCH_DELAY_MS = 300;

export interface AccountFinderProps {
  /** Name of the choices, e.g. "더할 고객". */
  legend: string;
  /** Only accounts of this role. Default: every role. */
  role?: Account['role'];
  /** The chosen choice: an account ID as text, or the value of a `fixed` choice. */
  value: string | null;
  onChange: (value: string, account: Account | null) => void;
  /** Choices above the found accounts, there before anything is typed, e.g. "시스템". */
  fixed?: readonly Option[];
  /** A note under an account, and whether it can be chosen. */
  describe?: (account: Account) => { description?: string; disabled?: boolean };
  /** Shown before anything is typed. */
  idleText: string;
  /** Shown when the search found nothing. */
  emptyText: string;
  /** 묶음 오류, e.g. "더할 고객을 고르세요." */
  error?: string;
  /** New accounts came for what was typed: the choice made among the old ones no longer shows. */
  onFound?: () => void;
  onFailure: (failure: Failure) => void;
}

/**
 * A search box and the accounts it finds as radios. Nothing is listed until
 * something is typed; the search waits for the typing to stop. An answer for
 * an older text, or one that comes after the finder has gone, is dropped.
 */
export function AccountFinder(props: AccountFinderProps) {
  const [query, setQuery] = useState('');
  const [found, setFound] = useState<{ q: string; items: Account[] } | null>(null);
  const q = query.trim();
  const { role } = props;
  const handlers = useRef({ onFound: props.onFound, onFailure: props.onFailure });
  useLayoutEffect(() => {
    handlers.current = { onFound: props.onFound, onFailure: props.onFailure };
  });

  useEffect(() => {
    if (q === '') return undefined;
    let current = true;
    const timer = setTimeout(() => {
      void call(api.GET('/api/console/accounts', { params: { query: { ...(role === undefined ? {} : { role }), q, size: 20 } } })).then((result) => {
        if (!current) return;
        if (result.ok) {
          setFound({ q, items: result.data.items });
          handlers.current.onFound?.();
        } else {
          setFound({ q, items: [] });
          handlers.current.onFailure(result.failure);
        }
      });
    }, SEARCH_DELAY_MS);
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [q, role]);

  const results = q !== '' && found?.q === q ? found.items.filter((a) => role === undefined || a.role === role) : null;
  const options: Option[] = [
    ...(props.fixed ?? []),
    ...(results ?? []).map((a) => ({ value: String(a.id), label: `${a.name} · ${a.email}`, ...props.describe?.(a) })),
  ];

  return (
    <>
      <TextField label="이름·이메일로 찾기" type="search" value={query} onChange={setQuery} maxLength={100} spellCheck={false} />
      {results === null ? (
        <p className="pd-muted">{q === '' ? props.idleText : '찾는 중…'}</p>
      ) : (
        results.length === 0 && <p className="pd-muted">{props.emptyText}</p>
      )}
      {options.length > 0 && (
        <RadioGroup
          legend={props.legend}
          options={options}
          value={props.value}
          onChange={(value) => {
            props.onChange(value, results?.find((a) => String(a.id) === value) ?? null);
          }}
          {...(props.error === undefined ? {} : { error: props.error })}
        />
      )}
    </>
  );
}
