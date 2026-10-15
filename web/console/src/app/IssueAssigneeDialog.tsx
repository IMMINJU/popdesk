// C-4 담당자 바꾸기 (3-issues screens.md 2절). The screen mounts it anew for every
// opening, so an answer that comes after it closed finds nothing to change (S3-AC-87).
// Slice 6b: the same dialog changes a task's assignee (6b-tasks screens.md 1절); what is saved
// and where its answer goes is the caller's.
import { Alert, Button, Dialog, RadioGroup, type Option } from '@popdesk/ui';
import { useEffect, useState } from 'react';
import { api, type Schemas } from '../api';
import { SaveFailure } from './bands';
import { useLive } from './hooks';
import { LoadFailed } from './layout';
import { rateLimitedText, TEXT } from './messages';
import { call, type Failure, type Result } from './result';
import { useSession, useToLogin } from './sessionContext';

type Issue = Schemas['Issue'];
type Person = Schemas['Person'];

const NONE = 'none';
const ROLE_WORDS: Record<Person['role'], string> = { CUSTOMER: '고객', AGENT: '상담원', ADMIN: '관리자' };

export interface IssueAssigneeDialogProps {
  issue: Issue;
  onClose: () => void;
  /** The server took the change. `person` is the new assignee, null when it was cleared. */
  onChanged: (person: Person | null) => void;
}

export function IssueAssigneeDialog(props: IssueAssigneeDialogProps) {
  const { issue } = props;
  const save = (person: Person | null): Promise<Result<unknown>> => {
    const path = { params: { path: { key: issue.key } } };
    return person === null
      ? call(api.DELETE('/api/console/issues/{key}/assignee', path))
      : call(api.PUT('/api/console/issues/{key}/assignee', { ...path, body: { accountId: person.accountId } }));
  };
  return <AssigneeDialog title="담당자" companyCode={issue.companyCode} assignee={issue.assignee} save={save} onClose={props.onClose} onChanged={props.onChanged} />;
}

export interface AssigneeDialogProps {
  title: string;
  /** The candidates are the assignees of this company. */
  companyCode: string;
  /** Who is the assignee now. */
  assignee: Person | null;
  /** Sends the change; `person` is null to clear it. */
  save: (person: Person | null) => Promise<Result<unknown>>;
  onClose: () => void;
  /** The server took the change while the dialog was open. */
  onChanged: (person: Person | null) => void;
}

/** Picks an assignee among the company's candidates, or nobody. */
export function AssigneeDialog(props: AssigneeDialogProps) {
  const { me } = useSession();
  const toLogin = useToLogin();
  const live = useLive();
  const current = props.assignee ? String(props.assignee.accountId) : NONE;
  const [candidates, setCandidates] = useState<{ ok: boolean; items: Person[] } | null>(null);
  const [asked, setAsked] = useState(0);
  const [picked, setPicked] = useState(current);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [notEligible, setNotEligible] = useState(false);

  useEffect(() => {
    let now = true;
    void call(api.GET('/api/console/companies/{code}/assignees', { params: { path: { code: props.companyCode } } })).then((result) => {
      if (!now) return;
      if (result.ok) setCandidates({ ok: true, items: result.data.items });
      else if (result.failure.kind === 'unauthenticated') toLogin();
      else setCandidates({ ok: false, items: [] });
    });
    return () => {
      now = false;
    };
    // Asked when the dialog opens and each time `asked` grows.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.companyCode, asked]);

  const save = async (value: string) => {
    if (pending) return;
    const person = candidates?.items.find((p) => String(p.accountId) === value) ?? null;
    setFailure(null);
    // Chosen before the candidates were read again, and no longer one of them.
    setNotEligible(value !== NONE && person === null);
    if (value !== NONE && person === null) return;
    setPending(true);
    const result = await props.save(person);
    if (!live()) return;
    setPending(false);
    if (result.ok) {
      props.onChanged(person);
      return;
    }
    if (result.failure.kind === 'invalid') {
      // Suspended or taken off the company while the dialog was open: the candidates are read again.
      setNotEligible(true);
      setAsked((n) => n + 1);
    } else {
      setFailure(result.failure);
    }
  };

  const options: Option[] = [
    { value: NONE, label: '담당 없음' },
    ...(candidates?.items ?? []).map((p) => ({ value: String(p.accountId), label: p.name, description: ROLE_WORDS[p.role] })),
  ];
  const mine = candidates?.items.find((p) => p.accountId === me?.id);

  return (
    <Dialog
      open
      title={props.title}
      closeLabel={TEXT.close}
      pending={pending}
      onClose={props.onClose}
      footer={
        <>
          <Button variant="secondary" onClick={props.onClose}>
            {TEXT.cancel}
          </Button>
          <Button
            disabled={picked === current || !candidates?.ok}
            pending={pending}
            pendingLabel={TEXT.sending}
            onClick={() => {
              void save(picked);
            }}
          >
            저장
          </Button>
        </>
      }
    >
      <div className="pd-form">
        {notEligible && <Alert tone="error">이 사람은 지금 담당자가 될 수 없습니다</Alert>}
        {failure?.kind === 'rateLimited' ? <Alert tone="error">{rateLimitedText(failure.seconds)}</Alert> : <SaveFailure failure={failure} />}
        {candidates === null && <p className="pd-muted">후보를 불러오는 중…</p>}
        {candidates?.ok === false && (
          <LoadFailed
            onRetry={() => {
              setCandidates(null);
              setAsked((n) => n + 1);
            }}
          />
        )}
        {candidates?.ok && (
          <>
            {mine && mine.accountId !== props.assignee?.accountId && (
              <div>
                <Button
                  variant="secondary"
                  size="sm"
                  blocked={pending}
                  onClick={() => {
                    void save(String(mine.accountId));
                  }}
                >
                  나에게 맡기기
                </Button>
              </div>
            )}
            <RadioGroup legend="담당자 고르기" legendHidden options={options} value={picked} onChange={setPicked} />
          </>
        )}
      </div>
    </Dialog>
  );
}
