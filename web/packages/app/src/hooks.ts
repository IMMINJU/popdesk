// Hooks every screen of both apps uses for the states of screens.md 2절. The
// texts are the ones both apps share (messages.ts) unless others come in.
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useBlocker } from 'react-router';
import type { FieldError } from '@popdesk/ui';
import { fieldSummaryTitle, fieldText, TEXT, type FieldLimits } from './messages';
import type { Failure, FieldProblem, Result } from './result';
import { useToLogin } from './sessionContext';

/** A fast answer shows no skeleton (screens.md 2절 처음 불러옴). */
export const SKELETON_DELAY_MS = 300;

export interface Load<T> {
  /** `loading` until the first answer; later reloads keep `ready` and the old data. */
  status: 'loading' | 'ready' | 'failed';
  data: T | undefined;
  failure: Failure | undefined;
  /** Loading for longer than 300ms: time to show the skeleton. */
  slow: boolean;
  reload: () => Promise<Result<T> | undefined>;
}

/**
 * Loads data when `key` changes. A 401 goes to the login screen. The skeleton
 * flag turns on only after 300ms.
 */
export function useLoad<T>(load: () => Promise<Result<T>>, key: string): Load<T> {
  const toLogin = useToLogin();
  // The answer remembers the key it belongs to; a newer key means loading again.
  const [answer, setAnswer] = useState<{ key: string; ok: boolean; data: T | undefined; failure: Failure | undefined } | null>(null);
  const [slowKey, setSlowKey] = useState<string | null>(null);
  const latest = useRef(0);
  const loadRef = useRef(load);
  const toLoginRef = useRef(toLogin);
  const keyRef = useRef(key);
  useLayoutEffect(() => {
    loadRef.current = load;
    toLoginRef.current = toLogin;
    keyRef.current = key;
  });

  const run = useCallback(async (): Promise<Result<T> | undefined> => {
    latest.current += 1;
    const mine = latest.current;
    const forKey = keyRef.current;
    const result = await loadRef.current();
    if (mine !== latest.current) return undefined;
    if (!result.ok && result.failure.kind === 'unauthenticated') {
      toLoginRef.current();
      return result;
    }
    setAnswer((current) =>
      result.ok
        ? { key: forKey, ok: true, data: result.data, failure: undefined }
        : { key: forKey, ok: false, data: current?.data, failure: result.failure },
    );
    return result;
  }, []);

  useEffect(() => {
    const timer = setTimeout(() => {
      setSlowKey(key);
    }, SKELETON_DELAY_MS);
    void run().finally(() => {
      clearTimeout(timer);
    });
    return () => {
      clearTimeout(timer);
    };
  }, [key, run]);

  const current = answer?.key === key ? answer : null;
  const status: Load<T>['status'] = current === null ? (answer?.data === undefined ? 'loading' : 'ready') : current.ok ? 'ready' : 'failed';
  return {
    status,
    data: answer?.data,
    failure: current?.failure,
    slow: current === null && slowKey === key,
    reload: run,
  };
}

/**
 * Retry-After countdown (screens.md 2절 429). `left` falls every second on the
 * screen; the band reads its first text only once. `ended` turns on once when
 * sending is possible again.
 */
export function useCooldown() {
  const [until, setUntil] = useState(0);
  const [initial, setInitial] = useState(0);
  const [now, setNow] = useState(() => Date.now());
  const [ended, setEnded] = useState(false);

  useEffect(() => {
    if (until === 0) return undefined;
    const timer = setInterval(() => {
      const current = Date.now();
      setNow(current);
      if (current >= until) {
        clearInterval(timer);
        setUntil(0);
        setEnded(true);
      }
    }, 1000);
    return () => {
      clearInterval(timer);
    };
  }, [until]);

  const start = useCallback((seconds: number) => {
    const current = Date.now();
    setNow(current);
    setInitial(seconds);
    setUntil(current + seconds * 1000);
    setEnded(false);
  }, []);

  const left = until === 0 ? 0 : Math.max(0, Math.ceil((until - now) / 1000));
  const clearEnded = useCallback(() => {
    setEnded(false);
  }, []);
  return { active: until !== 0, left, initial, ended, start, clearEnded };
}

/**
 * Asks before leaving with unsent text (UX-4): `beforeunload` for the browser,
 * a blocker for moves inside the app. The screen draws the dialog from `blocker`.
 */
export function useLeaveGuard(dirty: boolean) {
  useEffect(() => {
    if (!dirty) return undefined;
    const ask = (e: BeforeUnloadEvent) => {
      e.preventDefault();
    };
    window.addEventListener('beforeunload', ask);
    return () => {
      window.removeEventListener('beforeunload', ask);
    };
  }, [dirty]);
  return useBlocker(({ currentLocation, nextLocation }) => dirty && currentLocation.pathname !== nextLocation.pathname);
}

export type { FieldLimits };

/** The texts for field errors. The object must keep its identity between renders. */
export interface FieldWords {
  fieldText: (code: string, limits?: FieldLimits) => string;
  fieldSummaryTitle: (count: number) => string;
}

const FIELD_WORDS: FieldWords = { fieldText, fieldSummaryTitle };

export interface FieldSpec {
  /** `id` of the input, for focus and the summary link. */
  id: string;
  limits?: FieldLimits;
  /** Own text for a field code, e.g. `{ INVALID_FORMAT: '…' }`. */
  text?: Partial<Record<string, string>>;
}

export interface FieldErrors<F extends string> {
  errors: Partial<Record<F, string>>;
  /** Summary band above the form, when the server named fields. */
  summary: { title: string; items: FieldError[]; serverMessage?: string } | null;
  /** Applies a 400 `fields` answer in form order and focuses the first invalid field. */
  applyFields: (fields: FieldProblem[], serverMessage: string) => void;
  /** One field error from the screen (409, a local check), with focus. */
  setError: (field: F, text: string) => void;
  clear: () => void;
}

/**
 * Field errors for one form (screens.md 2절 400 fields). `specs` lists the
 * form's fields in order; the keys are the API field names.
 */
export function useFieldErrors<F extends string>(specs: Record<F, FieldSpec>, words: FieldWords = FIELD_WORDS): FieldErrors<F> {
  const [errors, setErrors] = useState<Partial<Record<F, string>>>({});
  const [summary, setSummary] = useState<FieldErrors<F>['summary']>(null);
  const focusId = useRef<string | null>(null);
  const specsRef = useRef(specs);
  useLayoutEffect(() => {
    specsRef.current = specs;
  });

  // Layout effect: focus moves in the same commit that shows the errors.
  useLayoutEffect(() => {
    if (focusId.current === null) return;
    const el = document.getElementById(focusId.current);
    focusId.current = null;
    // A group (fieldset) takes focus on its first control.
    const target = el?.matches('input, select, textarea, button, [tabindex]') ? el : el?.querySelector<HTMLElement>('input, select, textarea');
    target?.focus();
  });

  const applyFields = useCallback((fields: FieldProblem[], serverMessage: string) => {
    const current = specsRef.current;
    const order = Object.keys(current) as F[];
    const next: Partial<Record<F, string>> = {};
    const items: FieldError[] = [];
    let unknownField = false;
    for (const problem of fields) {
      if (!(problem.field in current)) unknownField = true;
    }
    for (const field of order) {
      const problem = fields.find((p) => p.field === field);
      if (!problem) continue;
      const spec = current[field];
      const text = spec.text?.[problem.code] ?? words.fieldText(problem.code, spec.limits);
      next[field] = text;
      items.push({ fieldId: spec.id, message: text });
    }
    setErrors(next);
    setSummary({
      title: words.fieldSummaryTitle(fields.length),
      items,
      ...(unknownField && serverMessage ? { serverMessage } : {}),
    });
    const first = items[0];
    if (first) focusId.current = first.fieldId;
  }, [words]);

  const setError = useCallback((field: F, text: string) => {
    setErrors({ [field]: text } as Partial<Record<F, string>>);
    setSummary(null);
    focusId.current = specsRef.current[field].id;
  }, []);

  const clear = useCallback(() => {
    setErrors({});
    setSummary(null);
  }, []);

  return { errors, summary, applyFields, setError, clear };
}

/**
 * Tells whether the screen is still there. An answer that comes after the
 * screen has gone is dropped (S1A-AC-58), the way useToLogin drops a late 401.
 */
export function useLive(): () => boolean {
  const mounted = useRef(false);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  return useCallback(() => mounted.current, []);
}

/** Focuses `ref` once, when `ready` first turns true (screens.md 2절 접근성: focus to h1). */
export function useFocusWhenReady(ref: { current: HTMLElement | null }, ready: boolean) {
  const done = useRef(false);
  // Layout effect: focus moves in the commit that shows the heading, so it never
  // lands later on top of a control the user already picked.
  useLayoutEffect(() => {
    if (!ready || done.current) return;
    done.current = true;
    ref.current?.focus();
  }, [ready, ref]);
}

/**
 * Sets `document.title`; the screen title goes first, then the app's `displayName` (screens.md 2절 접근성).
 * Without `displayName`: "popdesk" for now. Slice 9 (C-9) makes it the branding name of
 * `useBranding()`, "popdesk" without a `BrandingProvider`, so the callers stay as they are.
 */
export function useDocumentTitle(title: string | null, displayName: string = TEXT.displayName) {
  useEffect(() => {
    if (title !== null) document.title = `${title} - ${displayName}`;
  }, [title, displayName]);
}
