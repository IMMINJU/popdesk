// P-4 문의 등록 (screens.md 3절). The issue is made first; then the picked files
// go up one by one (7절). A file that fails does not undo the issue: P-5 shows
// which files did not go up.
import {
  Alert,
  Button,
  Checkbox,
  ConfirmDialog,
  EmptyState,
  FileUpload,
  InboxIcon,
  MarkdownEditor,
  PriorityPicker,
  Select,
  Skeleton,
  TextField,
  type FileRejectReason,
  type Priority,
  type UploadItem,
} from '@popdesk/ui';
import { useEffect, useLayoutEffect, useRef, useState, type ReactNode, type SubmitEvent } from 'react';
import { api, type Schemas } from '../api';
import { ATTACHMENT_LIMITS, REJECT_TEXT, uploadAttachment, uploadFailureText, type UploadFailure } from '../app/attachments';
import { FieldSummary, RateLimitBand, SaveFailure } from '../app/bands';
import { useMainBusy } from '../app/busy';
import { useCooldown, useFieldErrors, useLeaveGuard, useLoad } from '../app/hooks';
import { LoadFailed, PageTitle } from '../app/layout';
import { TEXT } from '../app/messages';
import { useLiveNavigate } from '../app/navigate';
import { call, type Failure, type Result } from '../app/result';
import { useToast } from '../app/toastContext';

type Company = Schemas['Company'];

const NOT_ACTIVE_TEXT = '이 고객사에는 지금 문의를 올릴 수 없습니다.';
const NOT_MEMBER_TEXT = '이 고객사에 문의를 올릴 수 없습니다.';
const COMPANY_ID = 'issue-company';

/** A file row the screen keeps until the issue is made (FileUpload). */
interface Upload extends UploadItem {
  file?: File;
}

export function P4IssueCreate() {
  const navigate = useLiveNavigate();
  const toast = useToast();

  const load = useLoad(async (): Promise<Result<Company[]>> => {
    const result = await call(api.GET('/api/portal/companies', { params: { query: { size: 100 } } }));
    if (!result.ok) return result;
    return { ok: true, data: result.data.items, response: result.response };
  }, 'companies');
  useMainBusy(load.status === 'loading' && load.data === undefined);

  const [companyCode, setCompanyCode] = useState('');
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [priority, setPriority] = useState<Priority>('NORMAL');
  const [isPrivate, setPrivate] = useState(false);
  const [uploads, setUploads] = useState<Upload[]>([]);
  const nextUpload = useRef(0);

  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [band, setBand] = useState<string | null>(null);
  const cooldown = useCooldown();
  const companyRef = useRef<HTMLSelectElement>(null);
  const focusCompany = useRef(false);
  const fields = useFieldErrors({
    companyCode: { id: COMPANY_ID },
    title: { id: 'issue-title', limits: { min: 1, max: 200 } },
    content: { id: 'issue-content', limits: { max: 10000 } },
    priority: { id: 'issue-priority' },
  });

  // Set once the issue is made and its files are done; the move to P-5 follows.
  const [created, setCreated] = useState<{ key: string; failures: UploadFailure[] } | null>(null);
  const dirty = created === null && (title.trim() !== '' || content.trim() !== '' || uploads.length > 0);
  const blocker = useLeaveGuard(dirty);

  useEffect(() => {
    if (!created) return;
    void navigate(`/issues/${encodeURIComponent(created.key)}`, { state: { uploadFailures: created.failures } });
  }, [created, navigate]);

  useLayoutEffect(() => {
    if (!focusCompany.current) return;
    focusCompany.current = false;
    companyRef.current?.focus();
  });

  const companies = load.data;
  const only = companies?.length === 1 ? companies[0] : undefined;
  const chosen = only ? only.code : companyCode;

  const sendFiles = async (key: string): Promise<UploadFailure[]> => {
    const failures: UploadFailure[] = [];
    for (const row of uploads) {
      if (row.state !== 'selected' || !row.file) continue;
      setUploads((current) => current.map((u) => (u.id === row.id ? { ...u, state: 'uploading' } : u)));
      const result = await uploadAttachment(key, row.file);
      if (!result.ok) failures.push({ name: row.name, reason: uploadFailureText(result.failure) });
      setUploads((current) => current.map((u) => (u.id === row.id ? { ...u, state: result.ok ? 'done' : 'failed' } : u)));
    }
    return failures;
  };

  const submit = async (e: SubmitEvent) => {
    e.preventDefault();
    if (pending || cooldown.active) return;
    setFailure(null);
    setBand(null);
    cooldown.clearEnded();
    fields.clear();
    setPending(true);
    const result = await call(
      api.POST('/api/portal/issues', {
        body: { companyCode: chosen, title, content, priority, private: isPrivate },
      }),
    );
    if (result.ok) {
      const key = result.data.key;
      const tried = uploads.filter((u) => u.state === 'selected').length;
      const failures = await sendFiles(key);
      // One notice for the issue and, with files, how many went up (screens.md P-4).
      const done = `문의를 올렸습니다(${key})`;
      toast(tried === 0 ? done : `${done}. 첨부 ${String(tried)}개 가운데 ${String(tried - failures.length)}개 올림`);
      setCreated({ key, failures });
      return;
    }
    setPending(false);
    const f = result.failure;
    if (f.kind === 'invalid') {
      fields.applyFields(f.fields, f.message);
    } else if (f.kind === 'rateLimited') {
      cooldown.start(f.seconds);
    } else if (f.kind === 'rejected' && f.code === 'COMPANY_NOT_ACTIVE') {
      setBand(NOT_ACTIVE_TEXT);
      focusCompany.current = true;
    } else if (f.kind === 'notFound') {
      // Left the company in the meantime: ask for the list again.
      setBand(NOT_MEMBER_TEXT);
      void load.reload().then((reloaded) => {
        if (reloaded?.ok && !reloaded.data.some((c) => c.code === companyCode)) setCompanyCode('');
      });
    } else {
      setFailure(f);
    }
  };

  const onSelect = (accepted: File[], rejected: { file: File; reason: FileRejectReason }[]) => {
    const rows: Upload[] = [];
    for (const { file, reason } of rejected) {
      nextUpload.current += 1;
      rows.push({ id: `u${String(nextUpload.current)}`, name: file.name, size: file.size, state: 'rejected', error: REJECT_TEXT[reason] });
    }
    for (const file of accepted) {
      nextUpload.current += 1;
      rows.push({ id: `u${String(nextUpload.current)}`, name: file.name, size: file.size, state: 'selected', file });
    }
    setUploads((current) => [...current, ...rows]);
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
  } else if (companies?.length === 0) {
    body = <EmptyState icon={<InboxIcon size={20} />} title={TEXT.noMembership} />;
  } else {
    let companyField: ReactNode;
    if (!companies) {
      companyField = (
        <div className="pd-field">
          <span className="pd-label">고객사</span>
          {load.slow && (
            <span aria-hidden="true">
              <Skeleton shape="block" height="var(--field-height)" />
            </span>
          )}
        </div>
      );
    } else if (only) {
      // One membership: chosen and not a choice, so Tab goes straight to the title.
      companyField = (
        <div className="pd-field">
          <span className="pd-label">고객사</span>
          <p className="pd-static-value">{only.name}</p>
        </div>
      );
    } else {
      companyField = (
        <Select
          ref={companyRef}
          id={COMPANY_ID}
          label="고객사"
          placeholder="고객사를 고르세요"
          options={companies.map((c) => ({ value: c.code, label: c.name }))}
          value={companyCode}
          onChange={setCompanyCode}
          {...(fields.errors.companyCode ? { error: fields.errors.companyCode } : {})}
        />
      );
    }
    body = (
      <form
        className="pd-form pd-create-form"
        noValidate
        onSubmit={(e) => {
          void submit(e);
        }}
      >
        <FieldSummary summary={fields.summary} />
        {band && <Alert tone="error">{band}</Alert>}
        <SaveFailure failure={failure} />
        <RateLimitBand {...cooldown} />
        {companyField}
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
          minHeight={420}
          maxLength={10000}
          showCount
          placeholder="무엇이 어떻게 안 되는지, 언제부터인지 적어 주세요."
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
        <FileUpload
          label="첨부"
          items={uploads}
          onSelect={onSelect}
          onRemove={(id) => {
            setUploads((current) => current.filter((u) => u.id !== id));
          }}
          limits={ATTACHMENT_LIMITS}
          helpText="파일 하나 10MB, 모두 합해 50MB까지. 문의를 올린 뒤 하나씩 올립니다."
          removeLabel="빼기"
          dropHint="또는 여기로 끌어 놓으세요"
        />
        <div className="pd-form-actions pd-create-actions">
          <Button type="submit" pending={pending} pendingLabel={TEXT.sending} blocked={cooldown.active}>
            올리기
          </Button>
          <Button
            variant="secondary"
            onClick={() => {
              void navigate('/issues');
            }}
          >
            {TEXT.cancel}
          </Button>
        </div>
      </form>
    );
  }

  return (
    <div className="pd-screen pd-create">
      <div className="pd-screen-head">
        <PageTitle>문의 등록</PageTitle>
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
