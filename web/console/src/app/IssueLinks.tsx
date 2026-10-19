// C-4 연결 구역 (6c-links screens.md 1절): the issues this one is linked to, in the order they were
// linked, "끊기" on each line and "문의 잇기" at the end. A closed issue keeps its list and loses both.
//
// Late answers (S6C-AC-55): the screen is mounted anew for every issue, so an answer for the issue
// left behind finds nothing to change. "문의 잇기" is mounted anew for every opening: a link made
// after it closed still redraws the list and the history, a failure or a found issue is dropped.
import { Alert, Badge, Button, Dialog, RadioGroup, StatusBadge, TextField, WarningIcon } from '@popdesk/ui';
import { useId, useRef, useState, type SubmitEvent } from 'react';
import { Link } from 'react-router';
import { api, type Schemas } from '../api';
import { useLive, useLoad } from './hooks';
import { LoadFailed } from './layout';
import { LINK_KIND_WORDS, LINK_TYPE_CHOICES, type LinkType } from './linkWords';
import { t, texts, tf } from './locale';
import { failureText, rateLimitedText, TEXT } from './messages';
import { call, type Failure, type Result } from './result';
import { useToLogin } from './sessionContext';

type Issue = Schemas['Issue'];
type IssueLink = Schemas['IssueLink'];

const LINK_TEXT = texts({
  empty: 'issueLinks.empty',
  unlinkFailed: 'issueLinks.unlinkFailed',
  linkFailed: 'issueLinks.linkFailed',
  notFound: 'issueLinks.notFound',
  self: 'issueLinks.self',
});

/** What the server's refusals of a new link say in the dialog (screens.md 1절). */
const LINK_REFUSALS: Readonly<Record<string, string>> = texts({
  LINK_TO_SELF: 'issueLinks.self',
  LINK_EXISTS: 'issueLinks.exists',
  LINK_DUPLICATE_CHAIN: 'issueLinks.chain',
  LINK_LIMIT_EXCEEDED: 'issueLinks.limit',
});

function linkFailureText(failure: Failure): string {
  if (failure.kind === 'rejected') return LINK_REFUSALS[failure.code] ?? LINK_TEXT.linkFailed;
  if (failure.kind === 'notFound') return LINK_TEXT.notFound;
  if (failure.kind === 'rateLimited') return rateLimitedText(failure.seconds);
  return LINK_TEXT.linkFailed;
}

function isClosedRefusal(failure: Failure): boolean {
  return failure.kind === 'rejected' && failure.code === 'ISSUE_CLOSED';
}

/** "베타상사 (BETA)": the company of a linked or found issue. */
function companyOf(i: { companyCode: string; companyName: string }): string {
  return `${i.companyName} (${i.companyCode})`;
}

export interface IssueLinksProps {
  issue: Issue;
  /** A change was refused because the issue was closed meanwhile: reads the issue again. */
  onStale: () => Promise<unknown>;
  /** A link was made or removed: the history is read again. */
  onChanged: () => void;
}

export function IssueLinks(props: IssueLinksProps) {
  const { issue } = props;
  const live = useLive();
  const toLogin = useToLogin();
  const headingId = useId();
  const closed = issue.status === 'CLOSED';
  const load = useLoad(() => call(api.GET('/api/console/issues/{key}/links', { params: { path: { key: issue.key } } })), issue.key);
  const items = load.data?.items;

  // Counts openings of "문의 잇기": the key that makes every opening a new dialog.
  const [linking, setLinking] = useState<number | null>(null);
  const openings = useRef(0);
  const [unlinking, setUnlinking] = useState<string | null>(null);
  const [unlinkError, setUnlinkError] = useState<{ key: string; text: string } | null>(null);

  const focusHeading = () => {
    document.getElementById(headingId)?.focus();
  };

  /** The issue was closed meanwhile: read it again; its buttons go, so focus goes to the section. */
  const stale = async () => {
    await props.onStale();
    if (live()) focusHeading();
  };

  /** Sends a new link; a success redraws the list and the history even after the dialog closed. */
  const create = async (issueKey: string, type: LinkType): Promise<Result<IssueLink>> => {
    const opening = openings.current;
    const result = await call(api.POST('/api/console/issues/{key}/links', { params: { path: { key: issue.key } }, body: { issueKey, type } }));
    if (!live()) return result;
    const open = opening === openings.current;
    if (result.ok) {
      if (open) setLinking(null);
      void load.reload();
      props.onChanged();
    } else if (result.failure.kind === 'unauthenticated') {
      toLogin();
    } else if (isClosedRefusal(result.failure) && open) {
      setLinking(null);
      await stale();
    }
    return result;
  };

  const unlink = async (otherKey: string) => {
    if (unlinking !== null) return;
    setUnlinking(otherKey);
    setUnlinkError(null);
    const result = await call(api.DELETE('/api/console/issues/{key}/links/{otherKey}', { params: { path: { key: issue.key, otherKey } } }));
    if (!live()) return;
    setUnlinking(null);
    if (result.ok) {
      props.onChanged();
      await load.reload();
      if (live()) focusHeading();
    } else if (result.failure.kind === 'notFound') {
      // Someone removed it first: the list read again leaves the line out.
      void load.reload();
    } else if (result.failure.kind === 'unauthenticated') {
      toLogin();
    } else if (isClosedRefusal(result.failure)) {
      await stale();
    } else {
      setUnlinkError({ key: otherKey, text: result.failure.kind === 'rateLimited' ? rateLimitedText(result.failure.seconds) : LINK_TEXT.unlinkFailed });
    }
  };

  return (
    <section className="pd-detail-links" aria-labelledby={headingId}>
      <h2 id={headingId} className="pd-section-title" tabIndex={-1}>
        {items !== undefined && items.length > 0 ? tf('issueLinks.headingCount', { count: items.length }) : t('issueLinks.heading')}
      </h2>
      {items === undefined && load.status === 'failed' && (
        <LoadFailed
          onRetry={() => {
            void load.reload();
          }}
        />
      )}
      {items !== undefined &&
        (items.length === 0 ? (
          <p className="pd-muted">{LINK_TEXT.empty}</p>
        ) : (
          <ul className="pd-link-list">
            {items.map((l) => (
              <LinkLine
                key={l.issue.key}
                link={l}
                canUnlink={!closed}
                pending={unlinking === l.issue.key}
                error={unlinkError?.key === l.issue.key ? unlinkError.text : null}
                onUnlink={() => {
                  void unlink(l.issue.key);
                }}
              />
            ))}
          </ul>
        ))}
      {items !== undefined && !closed && (
        <div>
          <Button
            variant="secondary"
            size="sm"
            onClick={() => {
              openings.current += 1;
              setLinking(openings.current);
            }}
          >
            {t('issueLinks.link')}
          </Button>
        </div>
      )}
      {linking !== null && (
        <LinkDialog
          key={linking}
          issueKey={issue.key}
          create={create}
          onClose={() => {
            openings.current += 1;
            setLinking(null);
          }}
        />
      )}
    </section>
  );
}

/** One link: its kind, the other issue's key, status, title and company, and "끊기". */
function LinkLine(props: { link: IssueLink; canUnlink: boolean; pending: boolean; error: string | null; onUnlink: () => void }) {
  const other = props.link.issue;
  return (
    <li className="pd-link">
      <div className="pd-link-head">
        <span className="pd-link-tags">
          <Badge tone="neutral">{LINK_KIND_WORDS[props.link.type]}</Badge>
          <Link to={`/issues/${encodeURIComponent(other.key)}`} className="pd-link-key">
            {other.key}
          </Link>
          <StatusBadge kind="issue" status={other.status} audience="agent" />
        </span>
        {props.canUnlink && (
          // Named by the key: every line has one (screens.md 1절).
          <button
            type="button"
            className="pd-button"
            data-variant="quiet"
            data-size="sm"
            aria-label={tf('issueLinks.unlinkOf', { key: other.key })}
            aria-disabled={props.pending ? true : undefined}
            onClick={() => {
              if (!props.pending) props.onUnlink();
            }}
          >
            <span className="pd-button-text">{t('issueLinks.unlink')}</span>
          </button>
        )}
      </div>
      <p className="pd-link-title">{other.title}</p>
      <p className="pd-link-company pd-muted">{companyOf(other)}</p>
      {props.error !== null && <Alert tone="error">{props.error}</Alert>}
    </li>
  );
}

/** "문의 잇기": finds the other issue by its key, then links it with the kind picked. */
function LinkDialog(props: { issueKey: string; create: (issueKey: string, type: LinkType) => Promise<Result<IssueLink>>; onClose: () => void }) {
  const live = useLive();
  const toLogin = useToLogin();
  const [draft, setDraft] = useState('');
  const [found, setFound] = useState<Issue | null>(null);
  const [miss, setMiss] = useState<string | null>(null);
  // Counts the searches and every change of the key: a found issue for an older one is dropped.
  const asking = useRef(0);
  const [type, setType] = useState<LinkType>('RELATED');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const changeKey = (value: string) => {
    asking.current += 1;
    setDraft(value);
    setFound(null);
    setMiss(null);
    setError(null);
  };

  const find = async () => {
    const key = draft.trim().toUpperCase();
    asking.current += 1;
    const mine = asking.current;
    setFound(null);
    setMiss(null);
    setError(null);
    if (key === '') return;
    if (key === props.issueKey) {
      setMiss(LINK_TEXT.self);
      return;
    }
    const result = await call(api.GET('/api/console/issues/{key}', { params: { path: { key } } }));
    if (!live() || mine !== asking.current) return;
    if (result.ok) {
      if (result.data.key === props.issueKey) setMiss(LINK_TEXT.self);
      else setFound(result.data);
    } else if (result.failure.kind === 'unauthenticated') {
      toLogin();
    } else {
      setMiss(result.failure.kind === 'notFound' ? LINK_TEXT.notFound : failureText(result.failure));
    }
  };

  const send = async () => {
    if (pending || found === null) return;
    setPending(true);
    setError(null);
    const result = await props.create(found.key, type);
    if (!live() || result.ok) return;
    setPending(false);
    if (result.failure.kind !== 'unauthenticated' && !isClosedRefusal(result.failure)) setError(linkFailureText(result.failure));
  };

  return (
    <Dialog
      open
      title={t('issueLinks.link')}
      closeLabel={TEXT.close}
      pending={pending}
      onClose={props.onClose}
      footer={
        <>
          <Button variant="secondary" onClick={props.onClose}>
            {t('comment.keep')}
          </Button>
          <Button
            disabled={found === null}
            pending={pending}
            pendingLabel={TEXT.sending}
            onClick={() => {
              void send();
            }}
          >
            {t('issueLinks.linkSubmit')}
          </Button>
        </>
      }
    >
      <div className="pd-form">
        {error !== null && <Alert tone="error">{error}</Alert>}
        <form
          className="pd-link-search"
          noValidate
          onSubmit={(e: SubmitEvent) => {
            e.preventDefault();
            void find();
          }}
        >
          <TextField label={t('issueLinks.issueKey')} value={draft} onChange={changeKey} placeholder="ACME-12" autoComplete="off" autoCapitalize="characters" spellCheck={false} />
          <Button type="submit" variant="secondary">
            {t('issueLinks.find')}
          </Button>
        </form>
        {miss !== null && (
          <p className="pd-field-error" role="alert">
            <WarningIcon size={14} />
            <span>{miss}</span>
          </p>
        )}
        {found !== null && (
          <div className="pd-link-found">
            <p className="pd-link-tags">
              <span className="pd-strong">{found.key}</span>
              <StatusBadge kind="issue" status={found.status} audience="agent" />
            </p>
            <p className="pd-link-title">{found.title}</p>
            <p className="pd-link-company pd-muted">{companyOf(found)}</p>
          </div>
        )}
        <RadioGroup legend={t('issueLinks.kind')} options={LINK_TYPE_CHOICES} value={type} onChange={setType} />
      </div>
    </Dialog>
  );
}
