// C-19 대리 등록 (3-issues screens.md 1절). An agent or admin files an issue in a
// customer's name: company, customer, how it came in, then the fields of the
// portal's form. No files here; they go up on the issue afterwards.
import {
  Alert,
  Button,
  Checkbox,
  ConfirmDialog,
  EmptyState,
  InboxIcon,
  MarkdownEditor,
  PriorityPicker,
  RadioGroup,
  Select,
  TextField,
  type Priority,
} from '@popdesk/ui';
import { useEffect, useRef, useState, type ReactNode, type SubmitEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api, type Schemas } from '../api';
import { IMAGE_SAVE_TEXT, imagePaths, useEditorImages } from '../app/attachments';
import { FieldSummary, RateLimitBand, SaveFailure } from '../app/bands';
import { useMainBusy } from '../app/busy';
import { useCooldown, useFieldErrors, useLeaveGuard, useLive, useLoad } from '../app/hooks';
import { MY_ISSUES_HREF } from '../app/issueFilters';
import { LoadFailed, PageTitle } from '../app/layout';
import { t } from '../app/locale';
import { TEXT } from '../app/messages';
import { useLiveNavigate } from '../app/navigate';
import { ReporterFinder, type Reporter } from '../app/ReporterFinder';
import { call, type Failure, type Result } from '../app/result';
import { useSession } from '../app/sessionContext';
import { useToast } from '../app/toastContext';

type Company = Schemas['Company'];
type Channel = Schemas['CreateIssueRequest']['channel'];

const COMPANY_ID = 'issue-company';
const REPORTER_ID = 'issue-reporter';

/** A new `Idempotency-Key` (S3-AC-84). `getRandomValues`, since `randomUUID` needs HTTPS. */
function newKey(): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, '0')).join('');
}

export function C19IssueCreate() {
  const { me } = useSession();
  const navigate = useLiveNavigate();
  const toast = useToast();
  const live = useLive();
  const [params] = useSearchParams();

  const load = useLoad(async (): Promise<Result<Company[]>> => {
    const result = await call(api.GET('/api/console/companies', { params: { query: { size: 100 } } }));
    if (!result.ok) return result;
    return { ok: true, data: result.data.items, response: result.response };
  }, 'companies');
  useMainBusy(load.status === 'loading' && load.data === undefined);

  const [picked, setPicked] = useState<string | null>(null);
  const [reporter, setReporter] = useState<Reporter | null>(null);
  const [channel, setChannel] = useState<Channel>('PHONE');
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [priority, setPriority] = useState<Priority>('NORMAL');
  const [isPrivate, setPrivate] = useState(false);
  const [reporterReloads, setReporterReloads] = useState(0);
  // No issue yet: only the images uploaded here are drawn, from their temporary address.
  const images = useEditorImages(imagePaths());

  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [band, setBand] = useState<string | null>(null);
  const cooldown = useCooldown();
  const fields = useFieldErrors({
    companyCode: { id: COMPANY_ID, text: { REQUIRED: t('issueOnBehalf.companyRequired') } },
    reporterId: { id: REPORTER_ID, text: { REQUIRED: t('issueOnBehalf.reporterRequired'), UNKNOWN_VALUE: t('issueOnBehalf.reporterUnknown') } },
    channel: { id: 'issue-channel' },
    title: { id: 'issue-title', limits: { min: 1, max: 200 } },
    content: { id: 'issue-content', limits: { max: 10000 } },
    priority: { id: 'issue-priority' },
  });

  // The key is made when the form opens. The same body goes again with the same key; a changed body takes a new one.
  const idempotency = useRef<{ key: string; sent: string | null }>({ key: '', sent: null });

  const [created, setCreated] = useState<string | null>(null);
  const dirty = created === null && (title.trim() !== '' || content.trim() !== '');
  const blocker = useLeaveGuard(dirty);
  useEffect(() => {
    if (created !== null) void navigate(`/issues/${encodeURIComponent(created)}`);
  }, [created, navigate]);

  const companies = load.data;
  const open = companies?.filter((c) => c.status === 'ACTIVE') ?? [];
  // The company the issue list was filtered by, or the only one there is to choose.
  const asked = params.get('companyCode');
  const preset = open.find((c) => c.code === asked)?.code ?? (open.length === 1 ? (open[0]?.code ?? '') : '');
  const companyCode = picked ?? preset;
  const company = open.find((c) => c.code === companyCode);

  const send = async (body: Schemas['CreateIssueRequest'], retried: boolean): Promise<void> => {
    const text = JSON.stringify(body);
    if (idempotency.current.key === '' || (idempotency.current.sent !== null && idempotency.current.sent !== text)) idempotency.current.key = newKey();
    idempotency.current.sent = text;
    const result = await call(api.POST('/api/console/issues', { params: { header: { 'Idempotency-Key': idempotency.current.key } }, body }));
    if (!live()) return;
    if (result.ok) {
      toast(t('issueOnBehalf.created'));
      setCreated(result.data.key);
      return;
    }
    const f = result.failure;
    if (f.kind === 'rejected' && f.code === 'IDEMPOTENCY_KEY_REUSED') {
      // The key met another body. A new key, once; a second 409 goes to the band.
      idempotency.current = { key: newKey(), sent: null };
      if (!retried) {
        await send(body, true);
        return;
      }
    }
    setPending(false);
    if (f.kind === 'invalid') {
      fields.applyFields(f.fields, f.message);
      if (f.fields.some((p) => p.field === 'reporterId')) {
        // The customer left the company or was suspended meanwhile: choose again from a new list.
        setReporter(null);
        setReporterReloads((n) => n + 1);
      }
    } else if (f.kind === 'rateLimited') {
      cooldown.start(f.seconds);
    } else if (f.kind === 'rejected' && f.code === 'COMPANY_NOT_ACTIVE') {
      setBand(t('issueOnBehalf.companyNotActive'));
    } else if (f.kind === 'notFound') {
      // Taken off the company while choosing: ask for the companies again.
      setBand(t('issueOnBehalf.companyNotFound'));
      void load.reload().then((reloaded) => {
        if (live() && reloaded?.ok && !reloaded.data.some((c) => c.code === body.companyCode && c.status === 'ACTIVE')) {
          setPicked('');
          setReporter(null);
        }
      });
    } else {
      setFailure(f);
    }
  };

  const submit = (e: SubmitEvent) => {
    e.preventDefault();
    if (pending || cooldown.active || images.busy) return;
    setFailure(null);
    setBand(null);
    cooldown.clearEnded();
    fields.clear();
    if (companyCode === '' || reporter === null) {
      fields.applyFields(
        [...(companyCode === '' ? [{ field: 'companyCode', code: 'REQUIRED' }] : []), { field: 'reporterId', code: 'REQUIRED' }],
        '',
      );
      return;
    }
    setPending(true);
    void send({ companyCode, reporterId: reporter.accountId, channel, title, content, priority, private: isPrivate, ...images.idsFor(content) }, false);
  };

  let body: ReactNode;
  if (load.status === 'failed' && !companies) {
    body = (
      <LoadFailed
        onRetry={() => {
          void load.reload();
        }}
      />
    );
  } else if (!companies) {
    // The form comes with its companies: a company chosen in an empty list would be lost.
    body = null;
  } else if (open.length === 0) {
    // Nothing to choose: no form. An agent with no company reads what C-3 says.
    body = (
      <EmptyState
        icon={<InboxIcon size={20} />}
        title={me?.role === 'AGENT' && companies.length === 0 ? t('issueOnBehalf.noCompaniesAgent') : t('issueOnBehalf.noActiveCompanies')}
        action={<Link to={MY_ISSUES_HREF}>문의 목록으로</Link>}
      />
    );
  } else {
    body = (
      <form className="pd-form pd-create-form" noValidate onSubmit={submit}>
        <FieldSummary summary={fields.summary} />
        {band && <Alert tone="error">{band}</Alert>}
        <SaveFailure failure={failure} known={{ IDEMPOTENCY_KEY_REUSED: t('issueOnBehalf.keyReused'), ...IMAGE_SAVE_TEXT }} />
        <RateLimitBand {...cooldown} />
        <Select
          id={COMPANY_ID}
          label="고객사"
          placeholder={t('issueOnBehalf.companyPlaceholder')}
          options={open.map((c) => ({ value: c.code, label: c.name }))}
          value={companyCode}
          onChange={(code) => {
            setPicked(code);
            // The customer belongs to the company left behind.
            setReporter(null);
          }}
          {...(fields.errors.companyCode ? { error: fields.errors.companyCode } : {})}
        />
        <div id={REPORTER_ID} className="pd-form">
          <ReporterFinder
            key={companyCode}
            id={`${REPORTER_ID}-search`}
            companyCode={company ? companyCode : ''}
            value={reporter}
            onChange={setReporter}
            reloads={reporterReloads}
            emptyAction={me?.role === 'ADMIN' && company ? <Link to={`/companies/${encodeURIComponent(company.code)}`}>{t('issueOnBehalf.toCompany')}</Link> : null}
            {...(fields.errors.reporterId ? { error: fields.errors.reporterId } : {})}
          />
        </div>
        <RadioGroup<Channel>
          id="issue-channel"
          legend={t('issueOnBehalf.channel')}
          orientation="horizontal"
          options={[
            { value: 'PHONE', label: t('issueOnBehalf.channelPhone') },
            { value: 'OTHER', label: t('issueOnBehalf.channelOther') },
          ]}
          value={channel}
          onChange={setChannel}
          {...(fields.errors.channel ? { error: fields.errors.channel } : {})}
        />
        <TextField
          id="issue-title"
          label="제목"
          value={title}
          onChange={setTitle}
          maxLength={200}
          {...(fields.errors.title ? { error: fields.errors.title } : {})}
        />
        <MarkdownEditor
          id="issue-content"
          label="내용"
          value={content}
          onChange={setContent}
          minHeight={240}
          maxLength={10000}
          showCount
          placeholder="무엇이 어떻게 안 되는지, 언제부터인지 적어 주세요."
          helpText={t('issueOnBehalf.noFiles')}
          {...images.editor}
          {...(fields.errors.content ? { error: fields.errors.content } : {})}
        />
        <div id="issue-priority">
          <PriorityPicker
            legend="우선순위"
            value={priority}
            onChange={setPriority}
            {...(fields.errors.priority ? { error: fields.errors.priority } : {})}
          />
        </div>
        <Checkbox label="비공개" description="같은 회사 동료에게 보이지 않습니다" checked={isPrivate} onChange={setPrivate} />
        {reporter && <p className="pd-muted">{`${reporter.name} 님(${reporter.email})의 이름으로 올립니다. 이 고객에게 메일로 알립니다.`}</p>}
        <div className="pd-form-actions">
          <Button
            variant="secondary"
            onClick={() => {
              void navigate(MY_ISSUES_HREF);
            }}
          >
            {TEXT.cancel}
          </Button>
          <Button type="submit" pending={pending} pendingLabel={TEXT.sending} blocked={cooldown.active || images.busy}>
            {t('issueOnBehalf.submit')}
          </Button>
        </div>
      </form>
    );
  }

  return (
    <div className="pd-screen pd-create">
      <div className="pd-screen-head">
        <PageTitle>{t('issueOnBehalf.title')}</PageTitle>
      </div>
      {body}
      <ConfirmDialog
        open={blocker.state === 'blocked'}
        title={TEXT.leaveTitle}
        message={TEXT.leaveMessage}
        confirmLabel={TEXT.leave}
        cancelLabel={TEXT.stay}
        tone="danger"
        onConfirm={() => {
          blocker.proceed?.();
        }}
        onCancel={() => {
          blocker.reset?.();
        }}
      />
    </div>
  );
}
