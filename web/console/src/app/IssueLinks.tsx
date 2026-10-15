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
import { failureText, rateLimitedText, TEXT } from './messages';
import { call, type Failure, type Result } from './result';
import { useToLogin } from './sessionContext';

type Issue = Schemas['Issue'];
type IssueLink = Schemas['IssueLink'];

const EMPTY_TEXT = '연결된 문의가 없습니다.';
const UNLINK_FAILED_TEXT = '연결을 바꾸지 못했습니다. 다시 해 보세요.';
const LINK_FAILED_TEXT = '연결하지 못했습니다. 다시 해 보세요.';
const NOT_FOUND_TEXT = '찾을 수 없는 문의입니다.';
const SELF_TEXT = '이 문의와는 이을 수 없습니다.';

/** What the server's refusals of a new link say in the dialog (screens.md 1절). */
const LINK_REFUSALS: Readonly<Record<string, string>> = {
  LINK_TO_SELF: SELF_TEXT,
  LINK_EXISTS: '이미 이어진 문의입니다.',
  LINK_DUPLICATE_CHAIN: '중복은 한 단계만 이을 수 있습니다. 원본이 이미 다른 문의의 중복이거나, 중복이 될 문의가 이미 원본이거나 다른 원본이 있습니다.',
  LINK_LIMIT_EXCEEDED: '연결은 문의마다 50개까지입니다.',
};

function linkFailureText(failure: Failure): string {
  if (failure.kind === 'rejected') return LINK_REFUSALS[failure.code] ?? LINK_FAILED_TEXT;
  if (failure.kind === 'notFound') return NOT_FOUND_TEXT;
  if (failure.kind === 'rateLimited') return rateLimitedText(failure.seconds);
  return LINK_FAILED_TEXT;
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
      setUnlinkError({ key: otherKey, text: result.failure.kind === 'rateLimited' ? rateLimitedText(result.failure.seconds) : UNLINK_FAILED_TEXT });
    }
  };

  return (
    <section className="pd-detail-links" aria-labelledby={headingId}>
      <h2 id={headingId} className="pd-section-title" tabIndex={-1}>
        {items !== undefined && items.length > 0 ? `연결된 문의 ${String(items.length)}` : '연결된 문의'}
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
          <p className="pd-muted">{EMPTY_TEXT}</p>
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
            문의 잇기
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
      <div className="pd-link-row">
        <div className="pd-link-main">
          <p className="pd-link-head">
            <Badge tone="neutral">{LINK_KIND_WORDS[props.link.type]}</Badge>
            <Link to={`/issues/${encodeURIComponent(other.key)}`} className="pd-link-key">
              {other.key}
            </Link>
            <StatusBadge kind="issue" status={other.status} audience="agent" />
          </p>
          <p className="pd-link-title">{other.title}</p>
          <p className="pd-link-company pd-muted">{companyOf(other)}</p>
        </div>
        {props.canUnlink && (
          // Named by the key: every line has one (screens.md 1절).
          <button
            type="button"
            className="pd-button"
            data-variant="quiet"
            data-size="sm"
            aria-label={`${other.key} 연결 끊기`}
            aria-disabled={props.pending ? true : undefined}
            onClick={() => {
              if (!props.pending) props.onUnlink();
            }}
          >
            <span className="pd-button-text">끊기</span>
          </button>
        )}
      </div>
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
      setMiss(SELF_TEXT);
      return;
    }
    const result = await call(api.GET('/api/console/issues/{key}', { params: { path: { key } } }));
    if (!live() || mine !== asking.current) return;
    if (result.ok) {
      if (result.data.key === props.issueKey) setMiss(SELF_TEXT);
      else setFound(result.data);
    } else if (result.failure.kind === 'unauthenticated') {
      toLogin();
    } else {
      setMiss(result.failure.kind === 'notFound' ? NOT_FOUND_TEXT : failureText(result.failure));
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
      title="문의 잇기"
      closeLabel={TEXT.close}
      pending={pending}
      onClose={props.onClose}
      footer={
        <>
          <Button variant="secondary" onClick={props.onClose}>
            그만두기
          </Button>
          <Button
            disabled={found === null}
            pending={pending}
            pendingLabel={TEXT.sending}
            onClick={() => {
              void send();
            }}
          >
            잇기
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
          <TextField label="문의 키" value={draft} onChange={changeKey} placeholder="ACME-12" autoComplete="off" autoCapitalize="characters" spellCheck={false} />
          <Button type="submit" variant="secondary">
            찾기
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
            <p className="pd-link-head">
              <span className="pd-strong">{found.key}</span>
              <StatusBadge kind="issue" status={found.status} audience="agent" />
            </p>
            <p className="pd-link-title">{found.title}</p>
            <p className="pd-link-company pd-muted">{companyOf(found)}</p>
          </div>
        )}
        <RadioGroup legend="종류" options={LINK_TYPE_CHOICES} value={type} onChange={setType} />
      </div>
    </Dialog>
  );
}
