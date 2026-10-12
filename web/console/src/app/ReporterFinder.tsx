// Picking the customer an issue is filed for (C-19, 3-issues screens.md 1절). Not
// AccountFinder: this one reads the customers of one company who can file there,
// lists the first 20 by name before anything is typed, and writes a line as "이름 (이메일)".
import { RadioGroup, TextField, type Option } from '@popdesk/ui';
import { useEffect, useState, type ReactNode } from 'react';
import { api, type Schemas } from '../api';
import { LoadFailed } from './layout';
import { t } from './locale';
import { call } from './result';
import { useToLogin } from './sessionContext';

export type Reporter = Schemas['CompanyReporter'];

/** The search goes out this long after the last key. */
const SEARCH_DELAY_MS = 300;

export interface ReporterFinderProps {
  /** `id` of the search box, for the form's focus. */
  id: string;
  /** The company whose customers are listed. Empty: closed until a company is chosen. */
  companyCode: string;
  value: Reporter | null;
  onChange: (reporter: Reporter) => void;
  /** 칸 오류, e.g. "고객을 골라 주세요." */
  error?: string;
  /** A new number asks for the list again (a customer the server no longer takes). */
  reloads: number;
  /** Shown after the text for a company with no customer who can file, e.g. a link to the company. */
  emptyAction?: ReactNode;
}

function reporterLine(reporter: { name: string; email: string }): string {
  return `${reporter.name} (${reporter.email})`;
}

/**
 * A search box and the customers it finds as radios. The screen gives it a new
 * `key` for each company, so nothing of the company before stays; an answer for
 * an older text, or one that comes after the finder has gone, is dropped (S3-AC-87).
 */
export function ReporterFinder(props: ReporterFinderProps) {
  const toLogin = useToLogin();
  const [query, setQuery] = useState('');
  const [found, setFound] = useState<{ asked: string; items: Reporter[]; failed: boolean } | null>(null);
  const [retries, setRetries] = useState(0);
  const q = query.trim();
  const { companyCode, reloads } = props;
  const asked = [q, String(reloads), String(retries)].join('\n');

  useEffect(() => {
    if (companyCode === '') return undefined;
    let current = true;
    const ask = () => {
      void call(
        api.GET('/api/console/companies/{code}/reporters', { params: { path: { code: companyCode }, query: { ...(q === '' ? {} : { q }), size: 20 } } }),
      ).then((result) => {
        if (!current) return;
        if (result.ok) setFound({ asked, items: result.data.items, failed: false });
        else if (result.failure.kind === 'unauthenticated') toLogin();
        else setFound({ asked, items: [], failed: true });
      });
    };
    // The first list comes at once; a typed text waits for the typing to stop.
    const timer = setTimeout(ask, q === '' ? 0 : SEARCH_DELAY_MS);
    return () => {
      current = false;
      clearTimeout(timer);
    };
    // `asked` holds q, reloads and retries.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [companyCode, asked]);

  const closed = companyCode === '';
  const answer = !closed && found?.asked === asked ? found : null;
  const items = answer?.items ?? [];
  // The chosen customer stays a choice while the text finds others.
  const listed = props.value && !items.some((r) => r.accountId === props.value?.accountId) ? [props.value, ...items] : items;
  const options: Option[] = listed.map((r) => ({ value: String(r.accountId), label: reporterLine(r) }));

  let note: ReactNode = null;
  if (closed) note = <p className="pd-muted">{t('issueOnBehalf.reporterClosed')}</p>;
  else if (answer === null) note = <p className="pd-muted">{t('issueOnBehalf.reporterFinding')}</p>;
  else if (answer.failed) {
    note = (
      <LoadFailed
        onRetry={() => {
          setRetries((n) => n + 1);
        }}
      />
    );
  } else if (items.length === 0) {
    note =
      q === '' ? (
        <p className="pd-muted">
          {t('issueOnBehalf.noReporters')} {props.emptyAction}
        </p>
      ) : (
        <p className="pd-muted">{t('issueOnBehalf.reporterNotFound')}</p>
      );
  }

  return (
    <>
      <TextField
        id={props.id}
        label={t('issueOnBehalf.reporterSearch')}
        type="search"
        value={query}
        onChange={setQuery}
        maxLength={100}
        spellCheck={false}
        disabled={closed}
        // Without choices the error stands under the box.
        {...(props.error !== undefined && options.length === 0 ? { error: props.error } : {})}
      />
      {note}
      {options.length > 0 && (
        <RadioGroup
          legend={t('issueOnBehalf.reporter')}
          options={options}
          value={props.value ? String(props.value.accountId) : null}
          onChange={(value) => {
            const picked = listed.find((r) => String(r.accountId) === value);
            if (picked) props.onChange(picked);
          }}
          {...(props.error === undefined ? {} : { error: props.error })}
        />
      )}
    </>
  );
}
