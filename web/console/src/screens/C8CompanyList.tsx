// C-8 고객사 목록 (관리자, screens.md 4절). List and 만들기 (PROJ-1). Archived
// companies stay hidden until 보관된 고객사 보기 is on (1a screens.md 2절).
import { Badge, Button, Checkbox, ConfirmDialog, DataTable, Dialog, EmptyState, StatusBadge, TextField, type DataTableColumn } from '@popdesk/ui';
import { useLayoutEffect, useRef, useState, type SubmitEvent } from 'react';
import { Link } from 'react-router';
import { api, type Schemas } from '../api';
import { FieldSummary, SaveFailure } from '../app/bands';
import { useMainBusy } from '../app/busy';
import { useFieldErrors, useLoad } from '../app/hooks';
import { LoadFailed, PageTitle } from '../app/layout';
import { t, tf } from '../app/locale';
import { TEXT } from '../app/messages';
import { useLiveNavigate } from '../app/navigate';
import { PageNav } from '../app/PageNav';
import { PAGE_SIZE, usePageMove, usePageParam } from '../app/paging';
import { call, type Failure } from '../app/result';
import { useToast } from '../app/toastContext';

type Company = Schemas['Company'];

// Built while drawing, so the headers follow the screen language.
const columns = (): DataTableColumn<Company>[] => [
  { key: 'code', header: t('companies.code'), cell: (c) => <span className="pd-key">{c.code}</span>, sticky: true, width: '7rem' },
  { key: 'name', header: t('companies.name'), cell: (c) => <Link to={`/companies/${encodeURIComponent(c.code)}`}>{c.name}</Link> },
  { key: 'legalName', header: t('companies.legalName'), cell: (c) => c.legalName },
  { key: 'status', header: t('companies.status'), cell: (c) => <StatusBadge kind="project" status={c.status} /> },
  {
    key: 'requests',
    header: t('companies.requests'),
    // The cell holds the number alone; the link's name says what it counts. Nothing open: an empty cell, no link.
    cell: (c) =>
      c.pendingMembershipRequests > 0 ? (
        <Link
          className="pd-count-link"
          to={`/companies/${encodeURIComponent(c.code)}#membership-requests`}
          aria-label={tf('companies.requestsOf', { name: c.name, count: c.pendingMembershipRequests })}
        >
          <Badge tone="accent">{c.pendingMembershipRequests}</Badge>
        </Link>
      ) : null,
  },
];

const FORM_ID = 'create-company';

function CreateCompanyDialog(props: { open: boolean; onClose: () => void; onClosedWhileSaving: () => void }) {
  const navigate = useLiveNavigate();
  const toast = useToast();
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [legalName, setLegalName] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [askDiscard, setAskDiscard] = useState(false);
  const open = useRef(props.open);
  useLayoutEffect(() => {
    open.current = props.open;
  });
  const fields = useFieldErrors({
    name: { id: 'company-name', limits: { max: 100 } },
    code: { id: 'company-code', text: { INVALID_FORMAT: t('companies.codeFormat') } },
    legalName: { id: 'company-legal-name', limits: { max: 100 } },
  });

  const reset = () => {
    setName('');
    setCode('');
    setLegalName('');
    setFailure(null);
    setAskDiscard(false);
    fields.clear();
  };

  const close = () => {
    reset();
    props.onClose();
  };

  const requestClose = () => {
    if (!pending && (name !== '' || code !== '' || legalName !== '')) setAskDiscard(true);
    else close();
  };

  const submit = async (e: SubmitEvent) => {
    e.preventDefault();
    if (pending) return;
    setFailure(null);
    fields.clear();
    setPending(true);
    const result = await call(api.POST('/api/console/companies', { body: { name, code, legalName } }));
    setPending(false);
    if (!open.current) {
      // Closed while saving: the list shows the result (screens.md C-8 만들기 저장 중).
      props.onClosedWhileSaving();
      return;
    }
    if (result.ok) {
      toast(tf('companies.created', { name: result.data.name }));
      close();
      void navigate(`/companies/${encodeURIComponent(result.data.code)}`);
      return;
    }
    const f = result.failure;
    if (f.kind === 'invalid') fields.applyFields(f.fields, f.message);
    else if (f.kind === 'rejected' && f.code === 'COMPANY_CODE_TAKEN') fields.setError('code', t('companies.codeTaken'));
    else setFailure(f);
  };

  return (
    <>
      <Dialog
        open={props.open}
        title={t('companies.create')}
        closeLabel={TEXT.close}
        pending={pending}
        onClose={requestClose}
        footer={
          <>
            <Button variant="secondary" onClick={requestClose}>
              {TEXT.cancel}
            </Button>
            <Button type="submit" form={FORM_ID} pending={pending} pendingLabel={TEXT.sending}>
              {t('companies.createSubmit')}
            </Button>
          </>
        }
      >
        <form
          id={FORM_ID}
          className="pd-form"
          noValidate
          onSubmit={(e) => {
            void submit(e);
          }}
        >
          <FieldSummary summary={fields.summary} />
          <SaveFailure failure={failure} />
          <TextField
            id="company-name"
            label={t('companies.name')}
            value={name}
            onChange={setName}
            maxLength={100}
            helpText={t('companies.nameHelp')}
            {...(fields.errors.name ? { error: fields.errors.name } : {})}
          />
          <TextField
            id="company-code"
            label={t('companies.code')}
            value={code}
            onChange={(v) => {
              setCode(v.toUpperCase());
            }}
            maxLength={10}
            autoCapitalize="characters"
            spellCheck={false}
            helpText={t('companies.codeHelp')}
            {...(fields.errors.code ? { error: fields.errors.code } : {})}
          />
          <TextField
            id="company-legal-name"
            label={t('companies.legalName')}
            value={legalName}
            onChange={setLegalName}
            maxLength={100}
            helpText={t('companies.legalNameHelp')}
            {...(fields.errors.legalName ? { error: fields.errors.legalName } : {})}
          />
        </form>
      </Dialog>
      <ConfirmDialog
        open={askDiscard}
        title={t('companies.discardTitle')}
        message={t('companies.discardMessage')}
        confirmLabel={t('companies.discard')}
        cancelLabel={t('companies.keepWriting')}
        tone="danger"
        onConfirm={close}
        onCancel={() => {
          setAskDiscard(false);
        }}
      />
    </>
  );
}

export function C8CompanyList() {
  const page = usePageParam();
  const heading = useRef<HTMLHeadingElement>(null);
  const [creating, setCreating] = useState(false);
  const [showArchived, setShowArchived] = useState(false);
  const load = useLoad(
    () => call(api.GET('/api/console/companies', { params: { query: { page, size: PAGE_SIZE } } })),
    String(page),
  );
  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);
  const move = usePageMove(load.data, heading);
  const data = load.data;
  const outOfRange = data !== undefined && data.items.length === 0 && page > 1;
  // The list API has no status filter, so the page is thinned here: a page can show fewer rows than its size.
  const rows = (data?.items ?? []).filter((c) => showArchived || c.status !== 'ARCHIVED');
  const onlyArchived = data !== undefined && data.items.length > 0 && rows.length === 0;

  return (
    <div className="pd-screen">
      <div className="pd-screen-head">
        <PageTitle titleRef={heading}>{t('companies.title')}</PageTitle>
        <Button
          onClick={() => {
            setCreating(true);
          }}
        >
          {t('companies.create')}
        </Button>
      </div>
      <Checkbox label={t('companies.showArchived')} checked={showArchived} onChange={setShowArchived} />
      {load.status === 'failed' && (
        <LoadFailed
          onRetry={() => {
            void load.reload();
          }}
        />
      )}
      {(load.status !== 'failed' || data) && (
        <DataTable
          caption={tf('companies.caption', { count: data?.totalElements ?? 0 })}
          columns={columns()}
          rows={rows}
          rowKey={(c) => c.code}
          status={firstLoad ? 'loading' : 'ready'}
          skeletonRows={load.slow ? 8 : 0}
          density="compact"
          empty={
            outOfRange ? (
              <EmptyState title={t('companies.noneOnPage')} action={<Link to="/companies">{t('companies.toFirstPage')}</Link>} />
            ) : onlyArchived ? (
              <EmptyState title={t('companies.onlyArchived')} description={t('companies.onlyArchivedHelp')} />
            ) : (
              <EmptyState title={t('companies.empty')} />
            )
          }
        />
      )}
      {data && <PageNav path="/companies" page={data.page} pageCount={data.totalPages} onPageChange={move.onPageChange} />}
      <p role="status" className="pd-sr-only">
        {move.announcement}
      </p>
      <CreateCompanyDialog
        open={creating}
        onClose={() => {
          setCreating(false);
        }}
        onClosedWhileSaving={() => {
          void load.reload();
        }}
      />
    </div>
  );
}
